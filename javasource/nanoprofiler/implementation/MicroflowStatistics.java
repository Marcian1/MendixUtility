package nanoprofiler.implementation;

public class MicroflowStatistics {
	private String microflowName;
	private String callHierarchy;
	private long recordedCount = 0;
	private long totalNanos = 0;
	private long totalSelectSqlCount = 0;
	private long totalUpdateSqlCount = 0;
	private long subsSelectSqlCount = 0;
	private long subsUpdateSqlCount = 0;
	private long minNanos = 0;
	private long maxNanos = 0;

	public MicroflowStatistics(String _microflowName, String _callHierarchy) {
		this.microflowName = _microflowName;
		this.callHierarchy = _callHierarchy;
	}

	public void record(long nanos, long selectSqlCount, long updateSqlCount, long _subsSelectSqlCount, long _subsUpdateSqlCount) {
		recordedCount += 1;;
		totalNanos += nanos;
		if (nanos > maxNanos) {
			maxNanos = nanos;
		}
		if (minNanos == 0 || nanos < minNanos) {
			minNanos = nanos;
		}
		totalSelectSqlCount += selectSqlCount;
		totalUpdateSqlCount += updateSqlCount;
		subsSelectSqlCount += _subsSelectSqlCount;
		subsUpdateSqlCount += _subsUpdateSqlCount;		
	}	

	public String getMicroflowName() {
		return microflowName;
	}

	public String getCallHierarchy() {
		return callHierarchy;
	}

	public long getRecordedCount() {
		return recordedCount;
	}

	public long getTotalNanos() {
		return totalNanos;
	}

	public long getTotalSelectSqlCount() {
		return totalSelectSqlCount;
	}

	public long getTotalUpdateSqlCount() {
		return totalUpdateSqlCount;
	}

	public long getSubsSelectSqlCount() {
		return subsSelectSqlCount;
	}

	public long getSubsUpdateSqlCount() {
		return subsUpdateSqlCount;
	}

	public long getMinNanos() {
		return minNanos;
	}

	public long getMaxNanos() {
		return maxNanos;
	}

}
