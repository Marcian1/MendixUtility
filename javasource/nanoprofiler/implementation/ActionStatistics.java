package nanoprofiler.implementation;

import java.util.LinkedHashMap;
import java.util.Map;

public class ActionStatistics {
	private String microflowName;
	private String sectionName;
	private long totalExecutedCount = 0;
	private long totalNanos = 0;
	private long totalSelectSqlCount = 0;
	private long totalUpdateSqlCount = 0;
	private Map<String, Long> sqlMap = new LinkedHashMap<String, Long>();

	public ActionStatistics(String _microflowName, String _sectionName) {
		this.microflowName = _microflowName;
		this.sectionName = _sectionName;
	}

	public void record(long executedCount, long accumulatedNanos, long selectSqlCount, long updateSqlCount, Map<String, Long> _sqlMap) {
		totalExecutedCount += executedCount;
		totalNanos += accumulatedNanos;
		totalSelectSqlCount += selectSqlCount;
		totalUpdateSqlCount += updateSqlCount;
		if (_sqlMap != null) {
			for (Map.Entry<String, Long> entry : _sqlMap.entrySet()) {
				String sql = entry.getKey();
				Long count = entry.getValue();
				sqlMap.put(sql, sqlMap.getOrDefault(sql, 0L) + count);
			}
		}
	}	

	public String getMicroflowName() {
		return microflowName;
	}

	public String getSectionName() {
		return sectionName;
	}

	public long getTotalExecutedCount() {
		return totalExecutedCount;
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

	public Map<String, Long> getSqlMap() {
		return sqlMap;
	}

}
