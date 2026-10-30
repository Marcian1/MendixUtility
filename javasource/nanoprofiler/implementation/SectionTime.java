package nanoprofiler.implementation;

import java.util.LinkedHashMap;
import java.util.Map;

public class SectionTime
{
	private String profileName;
	private String sectionName;
	private long executedCount;
	private long accumulatedNanos;
	private long selectSqlCount;
	private long updateSqlCount;
	private Map<String, Long> sqlMap = new LinkedHashMap<String, Long>();

	public String getProfileName() {
		return profileName;
	}
	public void setProfileName(String profileName) {
		this.profileName = profileName;
	}
	public String getSectionName() {
		return sectionName;
	}
	public void setSectionName(String sectionName) {
		this.sectionName = sectionName;
	}
	public long getExecutedCount() {
		return executedCount;
	}
	public void setExecutedCount(long executedCount) {
		this.executedCount = executedCount;
	}
	public long getAccumulatedNanos() {
		return accumulatedNanos;
	}
	public void setAccumulatedNanos(long accumulatedNanos) {
		this.accumulatedNanos = accumulatedNanos;
	}
	public long getSelectSqlCount() {
		return selectSqlCount;
	}
	public void setSelectSqlCount(long selectSqlCount) {
		this.selectSqlCount = selectSqlCount;
	}
	public long getUpdateSqlCount() {
		return updateSqlCount;
	}
	public void setUpdateSqlCount(long updateSqlCount) {
		this.updateSqlCount = updateSqlCount;
	}
	public Map<String, Long> getSqlMap() {
		return sqlMap;
	}
	public void setSqlMap(Map<String, Long> sqlMap) {
		this.sqlMap = sqlMap;
	}

}
