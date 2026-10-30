package flowscope.implementation;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.logging.LogLevel;
import com.mendix.logging.LogMessage;
import com.mendix.logging.LogSubscriber;
import com.mendix.systemwideinterfaces.core.IProfiler;
import com.mendix.thirdparty.org.json.JSONObject;

/**
 * Watches the runtime's microflow log and database calls and keeps the runs slower than the threshold.
 *
 * Two sources are joined by the request's execution id: the MicroflowEngine log says which microflow and
 * activity ran when, and the profiler hook says which statements ran and how long each took.
 */
public final class CaptureEngine {
    private static final ILogNode LOG = Core.getLogger("FlowScope");
    private static final CaptureEngine INSTANCE = new CaptureEngine();

    private static final Pattern MICROFLOW_LINE = Pattern.compile("^\\[([^\\]]+)\\] (Starting|Finished) execution of microflow '(.*)'$");
    private static final Pattern ACTIVITY_LINE = Pattern.compile("^\\[([^\\]]+)\\] Executing activity: (\\{.*\\})$", Pattern.DOTALL);
    private static final String NESTED = ".nested.";
    private static final int MAX_ACTIVE = 5000;
    private static final long CLOSE_DELAY_MS = 500;
    private static final long STALE_MS = 30 * 60 * 1000L;

    private final Map<String, RunState> active = new ConcurrentHashMap<>();
    private final Map<String, RunState> closing = new ConcurrentHashMap<>();
    private final RunWriter writer = new RunWriter();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FlowScope-finalizer");
        t.setDaemon(true);
        return t;
    });
    private final String node = hostName();

    private volatile boolean enabled;
    private volatile boolean sawLine;
    private volatile long thresholdMs = 1000;
    private volatile Pattern include;
    private volatile Pattern exclude;
    private LogSubscriber subscriber;
    private IProfiler profiler;
    private boolean sweeperStarted;

    private CaptureEngine() {
    }

    public static CaptureEngine get() {
        return INSTANCE;
    }

    /** Applies the settings. Safe to call again whenever they change. */
    public synchronized void configure(boolean enable, long threshold, String includeRegexp, String excludeRegexp) {
        this.thresholdMs = Math.max(0, threshold);
        this.include = compile(includeRegexp, "include");
        this.exclude = compile(excludeRegexp, "exclude");
        if (enable) {
            writer.ensureStarted();
            ensureSweeper();
            ensureSubscriber();
            Core.getLogger("MicroflowEngine").subscribe(subscriber, LogLevel.TRACE);
            ensureProfiler();
            this.sawLine = false;
            this.enabled = true;
            LOG.info("FlowScope capture is on: runs slower than " + thresholdMs + " ms are kept.");
        } else {
            this.enabled = false;
            if (subscriber != null) {
                Core.getLogger("MicroflowEngine").subscribe(subscriber, LogLevel.NONE);
            }
            releaseProfiler();
            active.clear();
            closing.clear();
            LOG.info("FlowScope capture is off.");
        }
    }

    private void ensureSubscriber() {
        if (subscriber != null) {
            return;
        }
        // Not "FlowScope": the runtime also keys log settings by name, and that is the name of our own log node.
        subscriber = new LogSubscriber("FlowScopeCapture", LogLevel.NONE) {
            @Override
            public void processMessage(LogMessage message) {
                try {
                    onLog(message);
                } catch (Throwable t) {
                    LOG.trace("FlowScope ignored a log line: " + t);
                }
            }
        };
        // Must be registered before it subscribes to a node.
        Core.registerLogSubscriber(subscriber);
    }

    private void ensureProfiler() {
        if (profiler != null && Core.getProfiler() == profiler) {
            return;
        }
        try {
            profiler = new DatabaseProfiler();
            Core.registerProfiler(profiler);
        } catch (Exception e) {
            profiler = null;
            LOG.warn("FlowScope could not register the database profiler (is another profiler, such as NanoProfiler's, using it?). "
                    + "Runs are still captured, without statements. " + e);
        }
    }

    private void releaseProfiler() {
        if (profiler != null && Core.getProfiler() == profiler) {
            Core.unregisterProfiler();
        }
        profiler = null;
    }

    private void ensureSweeper() {
        if (sweeperStarted) {
            return;
        }
        sweeperStarted = true;
        scheduler.scheduleWithFixedDelay(() -> active.values().removeIf(s -> s.ageMillis() > STALE_MS), 1, 1, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(this::keepSubscribed, 15, 15, TimeUnit.SECONDS);
    }

    /**
     * Changing the MicroflowEngine log level (admin API, cloud portal, mxcli) replaces our subscription, which
     * would stop capture without a sign. Look every few seconds and put it back.
     */
    private void keepSubscribed() {
        try {
            LogSubscriber s = subscriber;
            if (!enabled || s == null) {
                return;
            }
            LogLevel level = null;
            for (Map.Entry<ILogNode, LogLevel> e : s.getSubscriptions().entrySet()) {
                if ("MicroflowEngine".equals(e.getKey().name())) {
                    level = e.getValue();
                }
            }
            if (level != LogLevel.TRACE) {
                Core.getLogger("MicroflowEngine").subscribe(s, LogLevel.TRACE);
                LOG.warn("FlowScope found its microflow log subscription at level " + level
                        + " (the MicroflowEngine log level was probably changed) and set it back to TRACE.");
            }
        } catch (Throwable t) {
            LOG.trace("FlowScope subscription check failed: " + t);
        }
    }

    private static Pattern compile(String regexp, String which) {
        if (regexp == null || regexp.trim().isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(regexp.trim());
        } catch (PatternSyntaxException e) {
            LOG.warn("FlowScope ignores the " + which + " expression, it is not a valid regular expression: " + e.getDescription());
            return null;
        }
    }

    private boolean isTarget(String name) {
        Pattern in = include;
        Pattern out = exclude;
        return (in == null || in.matcher(name).find()) && (out == null || !out.matcher(name).find());
    }

    // ---- log lines -------------------------------------------------------------------------

    private void onLog(LogMessage message) {
        if (!enabled || message.message == null || !"MicroflowEngine".equals(message.node.name())) {
            return;
        }
        String text = message.message.toString();
        long ts = message.timestamp;
        if (!sawLine) {
            sawLine = true;
            LOG.info("FlowScope is receiving microflow log lines (first at level " + message.level + ").");
        }

        Matcher mf = MICROFLOW_LINE.matcher(text);
        if (mf.matches()) {
            String id = mf.group(1);
            String name = mf.group(3);
            if (name.contains(NESTED)) {
                return;
            }
            if ("Starting".equals(mf.group(2))) {
                onStart(id, name, ts);
            } else {
                onFinish(id, ts);
            }
            return;
        }
        Matcher act = ACTIVITY_LINE.matcher(text);
        if (act.matches()) {
            onActivity(act.group(1), act.group(2), ts);
        }
    }

    private void onStart(String id, String name, long ts) {
        RunState state = active.get(id);
        if (state == null) {
            if (active.size() >= MAX_ACTIVE) {
                return;
            }
            state = new RunState(id, name, ts, !isTarget(name), usedHeapMb());
            active.put(id, state);
        }
        state.startMicroflow(name, ts);
    }

    private void onFinish(String id, long ts) {
        RunState state = active.get(id);
        if (state == null) {
            return;
        }
        if (state.finishMicroflow(ts, usedHeapMb())) {
            active.remove(id, state);
            if (state.ignored) {
                return;
            }
            // Statements can still be reported a moment after the microflow ends.
            closing.put(id, state);
            final RunState finished = state;
            scheduler.schedule(() -> finalizeRun(finished), CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void onActivity(String id, String json, long ts) {
        RunState state = active.get(id);
        if (state == null || state.ignored) {
            return;
        }
        JSONObject o = new JSONObject(json);
        String owner = o.optString("name", "");
        if (owner.contains(NESTED) || !owner.equals(state.currentMicroflow())) {
            return;
        }
        JSONObject current = o.optJSONObject("current_activity");
        String type = current == null ? "" : current.optString("type", "");
        if ("Start".equals(type) || "End".equals(type)) {
            return;
        }
        if ("SubMicroflow".equals(type)) {
            // The microflow it calls gets its own line; this only ends the previous activity.
            state.boundary(ts);
            return;
        }
        String caption = current == null ? "" : current.optString("caption", "").trim();
        state.activity(ts, caption.isEmpty() ? friendly(type) : caption);
    }

    private static String friendly(String type) {
        switch (type) {
        case "ListLoop":
            return "Loop over list";
        case "RetrieveByXPath":
            return "Retrieve from database";
        case "":
            return "Activity";
        default:
            return type;
        }
    }

    private void finalizeRun(RunState state) {
        try {
            closing.remove(state.id, state);
            long duration = state.endTs() - state.startTs;
            if (duration <= thresholdMs) {
                return;
            }
            writer.offer(RunBuilder.build(state, node));
        } catch (Throwable t) {
            LOG.warn("FlowScope could not process a run: " + t, t);
        }
    }

    // ---- database calls --------------------------------------------------------------------

    private RunState stateFor(String executionId) {
        RunState s = active.get(executionId);
        return s != null ? s : closing.get(executionId);
    }

    private final class DatabaseProfiler implements IProfiler {
        @Override
        public Object enterDatabase(String sessionId, String executionId, String sql, Long startMs) {
            try {
                if (!enabled || executionId == null) {
                    return null;
                }
                RunState state = stateFor(executionId);
                if (state == null || state.ignored) {
                    return null;
                }
                RunState.SqlCall call = new RunState.SqlCall(startMs == null ? System.currentTimeMillis() : startMs, sql);
                state.addSql(call);
                return call;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public void finishDatabase(Object handle, Long endMs) {
            try {
                // A call that began before the hook was registered arrives without a handle.
                if (handle instanceof RunState.SqlCall) {
                    RunState.SqlCall call = (RunState.SqlCall) handle;
                    call.micros = Math.max(0, (System.nanoTime() - call.startNano) / 1000L);
                }
            } catch (Throwable ignored) {
                // The runtime's own database call must never fail because of the profiler.
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
