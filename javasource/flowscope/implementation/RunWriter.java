package flowscope.implementation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

/** Saves finished runs on its own thread, so the request that was profiled never waits for the database. */
final class RunWriter {
    private static final ILogNode LOG = Core.getLogger("FlowScope");
    private static final int QUEUE_SIZE = 1000;

    private final LinkedBlockingQueue<CapturedRun> queue = new LinkedBlockingQueue<>(QUEUE_SIZE);
    private volatile long dropped;
    private volatile long lastDropWarning;
    private Thread thread;

    synchronized void ensureStarted() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "FlowScope-writer");
        thread.setDaemon(true);
        thread.start();
    }

    void offer(CapturedRun run) {
        if (!queue.offer(run)) {
            dropped++;
            long now = System.currentTimeMillis();
            if (now - lastDropWarning > 60_000) {
                lastDropWarning = now;
                LOG.warn("FlowScope writer queue is full; " + dropped + " run(s) dropped so far.");
            }
        }
    }

    private void loop() {
        while (true) {
            try {
                persist(queue.take());
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOG.warn("FlowScope could not save a run: " + t, t);
            }
        }
    }

    private static void persist(CapturedRun run) throws Throwable {
        IContext context = Core.createSystemContext();
        context.startTransaction();
        try {
            IMendixObject execution = Core.instantiate(context, "FlowScope.Execution");
            execution.setValue(context, "StartedAt", new Date(run.startedAtMs));
            execution.setValue(context, "MicroflowName", truncate(run.microflowName, 500));
            execution.setValue(context, "DurationSeconds", seconds(run.durationMs));
            execution.setValue(context, "SelectCount", run.selects);
            execution.setValue(context, "UpdateCount", run.updates);
            execution.setValue(context, "SubflowCount", run.subflows);
            execution.setValue(context, "HeapStartMB", run.heapStartMb);
            execution.setValue(context, "HeapEndMB", run.heapEndMb);
            execution.setValue(context, "HeapDeltaMB", run.heapEndMb - run.heapStartMb);
            execution.setValue(context, "ExecutionId", truncate(run.executionId, 100));
            execution.setValue(context, "NodeName", truncate(run.node, 100));
            execution.setValue(context, "IsExpanded", false);
            Core.commit(context, execution);

            List<IMendixObject> children = new ArrayList<>();
            for (CapturedRun.Step step : run.steps) {
                IMendixObject o = Core.instantiate(context, "FlowScope.ExecutionStep");
                o.setValue(context, "Seq", step.seq);
                o.setValue(context, "Depth", step.depth);
                o.setValue(context, "Kind", step.microflow ? "Microflow" : "Activity");
                o.setValue(context, "Name", truncate(step.name, 500));
                o.setValue(context, "DurationSeconds", seconds(step.durationMs));
                o.setValue(context, "SelectCount", step.selects);
                o.setValue(context, "UpdateCount", step.updates);
                o.setValue(context, "SharePercent", step.sharePercent);
                o.setValue(context, "FlowScope.ExecutionStep_Execution", execution.getId());
                children.add(o);
            }
            for (CapturedRun.Query query : run.queries) {
                IMendixObject o = Core.instantiate(context, "FlowScope.ExecutionQuery");
                o.setValue(context, "Statement", query.statement);
                o.setValue(context, "RunCount", query.runs);
                o.setValue(context, "AvgMs", BigDecimal.valueOf(query.totalMicros)
                        .divide(BigDecimal.valueOf(Math.max(1, query.runs) * 1000L), 2, RoundingMode.HALF_UP));
                o.setValue(context, "TotalSeconds", BigDecimal.valueOf(query.totalMicros).movePointLeft(6).setScale(3, RoundingMode.HALF_UP));
                o.setValue(context, "FlowScope.ExecutionQuery_Execution", execution.getId());
                children.add(o);
            }
            if (!children.isEmpty()) {
                Core.commit(context, children);
            }
            context.endTransaction();
        } catch (Throwable t) {
            if (context.isInTransaction()) {
                context.rollbackTransaction();
            }
            throw t;
        }
    }

    private static BigDecimal seconds(long ms) {
        return BigDecimal.valueOf(ms).movePointLeft(3).setScale(3, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
