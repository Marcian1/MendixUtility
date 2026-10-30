package flowscope.implementation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns the raw events and statements of one run into the steps and queries that get saved. */
final class RunBuilder {
    static final int MAX_STEPS = 500;
    static final int MAX_QUERIES = 200;

    private RunBuilder() {
    }

    private static final class Row {
        final boolean microflow;
        final String name;
        final int depth;
        final int parent;
        final long start;
        long end = -1;
        long ownSelects;
        long ownUpdates;

        Row(boolean microflow, String name, int depth, int parent, long start) {
            this.microflow = microflow;
            this.name = name;
            this.depth = depth;
            this.parent = parent;
            this.start = start;
        }
    }

    static CapturedRun build(RunState state, String node) {
        List<RunState.Event> events = state.eventsCopy();
        List<RunState.SqlCall> calls = state.sqlsCopy();
        long endTs = state.endTs();
        long total = Math.max(1, endTs - state.startTs);

        List<Row> rows = new ArrayList<>();
        ArrayDeque<Integer> open = new ArrayDeque<>();
        Map<Integer, Integer> openActivity = new HashMap<>();
        int subflows = 0;
        boolean truncated = false;

        for (RunState.Event e : events) {
            Integer top = open.peek();
            switch (e.kind) {
            case MF_START:
                if (top != null) {
                    subflows++;
                }
                rows.add(new Row(true, e.name, open.size(), top == null ? -1 : top, e.ts));
                open.push(rows.size() - 1);
                break;
            case ACTIVITY:
                if (top == null) {
                    break;
                }
                closeActivity(rows, openActivity, top, e.ts);
                if (rows.size() >= MAX_STEPS) {
                    truncated = true;
                    break;
                }
                rows.add(new Row(false, e.name, open.size(), top, e.ts));
                openActivity.put(top, rows.size() - 1);
                break;
            case BOUNDARY:
                if (top != null) {
                    closeActivity(rows, openActivity, top, e.ts);
                }
                break;
            case MF_END:
                if (top != null) {
                    closeActivity(rows, openActivity, top, e.ts);
                    rows.get(top).end = e.ts;
                    open.pop();
                }
                break;
            default:
                break;
            }
        }
        while (!open.isEmpty()) {
            int top = open.pop();
            closeActivity(rows, openActivity, top, endTs);
            rows.get(top).end = endTs;
        }

        long selects = 0;
        long updates = 0;
        Map<String, CapturedRun.Query> byStatement = new LinkedHashMap<>();
        for (RunState.SqlCall call : calls) {
            if (call.micros < 0) {
                continue;
            }
            boolean select = SqlText.isSelect(call.sql);
            if (select) {
                selects++;
            } else {
                updates++;
            }
            int target = findRow(rows, call.startMs);
            if (target >= 0) {
                if (select) {
                    rows.get(target).ownSelects++;
                } else {
                    rows.get(target).ownUpdates++;
                }
            }
            String statement = SqlText.normalize(call.sql);
            CapturedRun.Query q = byStatement.get(statement);
            if (q == null) {
                q = new CapturedRun.Query();
                q.statement = statement;
                byStatement.put(statement, q);
            }
            q.runs++;
            q.totalMicros += call.micros;
        }

        long[] totalSelects = new long[rows.size()];
        long[] totalUpdates = new long[rows.size()];
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row r = rows.get(i);
            totalSelects[i] += r.ownSelects;
            totalUpdates[i] += r.ownUpdates;
            if (r.parent >= 0) {
                totalSelects[r.parent] += totalSelects[i];
                totalUpdates[r.parent] += totalUpdates[i];
            }
        }

        CapturedRun run = new CapturedRun();
        run.microflowName = state.rootName;
        run.startedAtMs = state.startTs;
        run.durationMs = total;
        run.selects = selects;
        run.updates = updates;
        run.subflows = subflows;
        run.heapStartMb = state.heapStartMb;
        run.heapEndMb = state.heapEndMb();
        run.executionId = state.id;
        run.node = node;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            CapturedRun.Step s = new CapturedRun.Step();
            s.seq = i + 1;
            s.depth = r.depth;
            s.microflow = r.microflow;
            s.name = r.name;
            long end = r.end < 0 ? endTs : r.end;
            s.durationMs = Math.max(0, end - r.start);
            s.selects = totalSelects[i];
            s.updates = totalUpdates[i];
            s.sharePercent = (int) Math.min(100, Math.round(100.0 * s.durationMs / total));
            run.steps.add(s);
        }
        if (!run.steps.isEmpty()) {
            run.steps.get(0).selects = selects;
            run.steps.get(0).updates = updates;
        }
        if (truncated) {
            CapturedRun.Step note = new CapturedRun.Step();
            note.seq = run.steps.size() + 1;
            note.depth = 1;
            note.name = "(more activities not shown, limit " + MAX_STEPS + ")";
            run.steps.add(note);
        }
        List<CapturedRun.Query> queries = new ArrayList<>(byStatement.values());
        queries.sort(Comparator.comparingLong((CapturedRun.Query q) -> q.totalMicros).reversed());
        run.queries.addAll(queries.size() > MAX_QUERIES ? queries.subList(0, MAX_QUERIES) : queries);
        return run;
    }

    private static void closeActivity(List<Row> rows, Map<Integer, Integer> openActivity, int microflowRow, long ts) {
        Integer idx = openActivity.remove(microflowRow);
        if (idx != null) {
            rows.get(idx).end = ts;
        }
    }

    /** The innermost activity running at that moment, else the innermost microflow. */
    private static int findRow(List<Row> rows, long ts) {
        int bestActivity = -1;
        int bestMicroflow = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            long end = r.end < 0 ? Long.MAX_VALUE : r.end;
            if (ts < r.start || ts > end) {
                continue;
            }
            if (r.microflow) {
                if (bestMicroflow < 0 || r.depth >= rows.get(bestMicroflow).depth) {
                    bestMicroflow = i;
                }
            } else if (bestActivity < 0 || r.depth >= rows.get(bestActivity).depth) {
                bestActivity = i;
            }
        }
        return bestActivity >= 0 ? bestActivity : bestMicroflow;
    }
}
