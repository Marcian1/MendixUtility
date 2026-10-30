package flowscope.implementation;

import java.util.ArrayList;
import java.util.List;

/** A finished run, ready to be saved. Plain data, no Mendix types. */
final class CapturedRun {
    static final class Step {
        int seq;
        int depth;
        boolean microflow;
        String name;
        long durationMs;
        long selects;
        long updates;
        int sharePercent;
    }

    static final class Query {
        String statement;
        long runs;
        long totalMicros;
    }

    String microflowName;
    long startedAtMs;
    long durationMs;
    long selects;
    long updates;
    int subflows;
    long heapStartMb;
    long heapEndMb;
    String executionId;
    String node;
    final List<Step> steps = new ArrayList<>();
    final List<Query> queries = new ArrayList<>();
}
