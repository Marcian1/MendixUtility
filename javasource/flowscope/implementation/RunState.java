package flowscope.implementation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Everything seen for one request while its entry-point microflow is running. Touched by several threads, so every access locks. */
final class RunState {
    enum Kind {
        MF_START, MF_END, ACTIVITY, BOUNDARY
    }

    static final class Event {
        final Kind kind;
        final long ts;
        final String name;

        Event(Kind kind, long ts, String name) {
            this.kind = kind;
            this.ts = ts;
            this.name = name;
        }
    }

    /** One database statement: started by enterDatabase, finished by finishDatabase. */
    static final class SqlCall {
        final long startMs;
        final long startNano = System.nanoTime();
        final String sql;
        volatile long micros = -1;

        SqlCall(long startMs, String sql) {
            this.startMs = startMs;
            this.sql = sql;
        }
    }

    final String id;
    final String rootName;
    final long startTs;
    final boolean ignored;
    final long heapStartMb;
    private long endTs;
    private long heapEndMb;
    private final ArrayDeque<String> stack = new ArrayDeque<>();
    private final List<Event> events = new ArrayList<>();
    private final List<SqlCall> sqls = new ArrayList<>();
    private final long createdNano = System.nanoTime();

    RunState(String id, String rootName, long startTs, boolean ignored, long heapStartMb) {
        this.id = id;
        this.rootName = rootName;
        this.startTs = startTs;
        this.ignored = ignored;
        this.heapStartMb = heapStartMb;
        this.endTs = startTs;
    }

    synchronized void startMicroflow(String name, long ts) {
        stack.push(name);
        if (!ignored) {
            events.add(new Event(Kind.MF_START, ts, name));
        }
    }

    /** Returns true when the entry-point microflow itself has finished. */
    synchronized boolean finishMicroflow(long ts, long heapMb) {
        if (stack.isEmpty()) {
            return true;
        }
        stack.pop();
        if (!ignored) {
            events.add(new Event(Kind.MF_END, ts, null));
        }
        if (stack.isEmpty()) {
            endTs = ts;
            heapEndMb = heapMb;
            return true;
        }
        return false;
    }

    synchronized String currentMicroflow() {
        return stack.peek();
    }

    synchronized void activity(long ts, String caption) {
        if (!ignored) {
            events.add(new Event(Kind.ACTIVITY, ts, caption));
        }
    }

    /** An activity that only marks a point in time (a sub-microflow call), so the previous activity ends there. */
    synchronized void boundary(long ts) {
        if (!ignored) {
            events.add(new Event(Kind.BOUNDARY, ts, null));
        }
    }

    synchronized void addSql(SqlCall call) {
        if (!ignored) {
            sqls.add(call);
        }
    }

    synchronized List<Event> eventsCopy() {
        return new ArrayList<>(events);
    }

    synchronized List<SqlCall> sqlsCopy() {
        return new ArrayList<>(sqls);
    }

    synchronized long endTs() {
        return endTs;
    }

    synchronized long heapEndMb() {
        return heapEndMb;
    }

    long ageMillis() {
        return (System.nanoTime() - createdNano) / 1_000_000L;
    }
}
