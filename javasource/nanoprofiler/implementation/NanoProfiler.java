package nanoprofiler.implementation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import com.mendix.systemwideinterfaces.core.IContext;

public class NanoProfiler {
	static ThreadLocal<Map<String, NanoProfiler>> profilerMap = new ThreadLocal<Map<String, NanoProfiler>>();

	public static Map<String, NanoProfiler> getProfilerMap() {
		if (profilerMap.get() == null) {
			profilerMap.set(new WeakHashMap<String, NanoProfiler>());
		}
		return profilerMap.get();
	}

	private String profileName;
	private String lastPointName;
	private String lastSectionName;
	private long startPointNano;
	private long lastPointNano;
	private Map<String, SectionTime> sectionTimeMap = new LinkedHashMap<String, SectionTime>();
	private Map<String, Long> sqlMap = new LinkedHashMap<String, Long>();
	private long totalNanos = 0;
	private long totalTicks = 0;

	private boolean countSqls = false;
	private boolean showMemoryUsage = false;
	private long selectSqlCount = 0;
	private long updateSqlCount = 0;
	private long totalSelectSqlCount = 0;
	private long totalUpdateSqlCount = 0;
	private long subsSelectSqlCount = 0;
	private long subsUpdateSqlCount = 0;
	private String memoryUsageAtStart = null;
	private String memoryUsageAtEnd = null;

	public NanoProfiler(String name) {
		this.profileName = name;
	}

	public NanoProfiler(String name, boolean countSqls, boolean showMemoryUsage) {
		this.profileName = name;
		this.countSqls = countSqls;
		this.showMemoryUsage = showMemoryUsage;
	}

	public void tick(IContext context, String pointName) {
		if (lastPointName == null) {
			this.lastPointName = pointName;
			this.lastPointNano = System.nanoTime();
			this.startPointNano = this.lastPointNano;
			if (showMemoryUsage) {
				memoryUsageAtStart = getMemoryUsageString();
			}
		} else {
			String sectionName = lastPointName + "-" + pointName;
			if ("[End]".equals(pointName) || "End".equals(pointName)) {
				lastSectionName = null;
				if (showMemoryUsage) {
					memoryUsageAtEnd = getMemoryUsageString();
				}
			} else {
				lastSectionName = sectionName;
			}
			SectionTime sectionTime = this.sectionTimeMap.get(sectionName);
			if (sectionTime == null) {
				sectionTime = new SectionTime();
				sectionTime.setProfileName(profileName);
				sectionTime.setSectionName(sectionName);
				this.sectionTimeMap.put(sectionName, sectionTime);
			}
			long currentNano = System.nanoTime();
			long sectionNanos = currentNano - this.lastPointNano;
			sectionTime.setExecutedCount(sectionTime.getExecutedCount() + 1);
			sectionTime.setAccumulatedNanos(sectionTime.getAccumulatedNanos() + sectionNanos);
			sectionTime.setSelectSqlCount(sectionTime.getSelectSqlCount() + selectSqlCount);
			sectionTime.setUpdateSqlCount(sectionTime.getUpdateSqlCount() + updateSqlCount);
			Map<String, Long> sectionSqlMap = sectionTime.getSqlMap();
			if (sqlMap.size() > 0) {
				for (String sql : sqlMap.keySet()) {
					Long count = sectionSqlMap.get(sql);
					if (count != null) {
						sectionSqlMap.put(sql, count + sqlMap.get(sql));
					} else {
						sectionSqlMap.put(sql, sqlMap.get(sql));
					}
				}
				sqlMap.clear();
			}

			totalSelectSqlCount += selectSqlCount;
			totalUpdateSqlCount += updateSqlCount;
			selectSqlCount = 0;
			updateSqlCount = 0;
			this.lastPointName = pointName;
			this.lastPointNano = currentNano;
			this.totalNanos += sectionNanos;
			this.totalTicks += 1;
		}
	}

	public void tickSelectSql(IContext context, String sql) {
		selectSqlCount += 1;
		recordSql(sql);
	}

	public void tickUpdateSql(IContext context, String sql) {
		updateSqlCount += 1;
		recordSql(sql);
	}

	private void recordSql(String sql) {
		if (sql != null) {
			Long count = sqlMap.get(sql);
			if (count != null) {
				sqlMap.put(sql, count+1);
			} else {
				sqlMap.put(sql, 1L);
			}
		}
	}

	public String report(IContext context) {
		StringBuilder sb = new StringBuilder();
		if (showMemoryUsage) {
			sb.append("#### memoryUsageAtStart(used/max) = ").append(memoryUsageAtStart).append("\n");
		}
		for (SectionTime sectionTime : sectionTimeMap.values()) {
			long accumulatedNanos = sectionTime.getAccumulatedNanos();
			sb.append("#### sectionName = ").append(sectionTime.getSectionName());
			if (sectionTime.getSectionName().equals(lastSectionName)) {
				sb.append("<@running>");
			}
			sb.append(" : executedCount = ").append(String.format("%,d", sectionTime.getExecutedCount())). //
				append(" : accumulatedNanos = ").append(String.format("%,d", accumulatedNanos)). //
				append(" : averageNanos = ")
					.append(String.format("%,d", accumulatedNanos / sectionTime.getExecutedCount())); //
			if (countSqls) {
				sb.append(" : sqlCount = ").append(String.format("%,d", sectionTime.getSelectSqlCount())).append("+").append(String.format("%,d", sectionTime.getUpdateSqlCount())); //
			}
			sb.append("\n");
			Map<String, Long> sectionSqlMap = sectionTime.getSqlMap();
			if (sectionSqlMap.size() > 0) {
				for (String sql : sectionSqlMap.keySet()) {
					sb.append("      ").append(sectionSqlMap.get(sql)).append(" times of : ").append(sql.replaceFirst("SELECT .*? FROM", "SELECT ... FROM")).append("\n");
				}
				//sectionSqlMap.clear();	for ActionStatistics's record() method, don't clear sqlMap here. and leave it to the GC to clear it.
			}
		}
		if (showMemoryUsage) {
			sb.append("#### memoryUsageAtEnd(used/max) = ").append(memoryUsageAtEnd).append("\n");
		}

		StringBuilder sb2 = new StringBuilder();
		sb2.append("### NanoProfiler Report: profileName=").append(this.profileName); //
		if (sectionTimeMap.size() > 0) {
				sb2.append(" : totalTicks = ").append(totalTicks). //
					append(" : totalNanos = ").append(String.format("%,d", totalNanos)); //
		}
		if (countSqls) {
			sb2.append(" : totalSqlCount = ").append(String.format("%,d", totalSelectSqlCount)).append("+").append(String.format("%,d", totalUpdateSqlCount)); //
		}
		if (lastSectionName != null || sectionTimeMap.size() == 0) {
			long duration = System.nanoTime() - startPointNano;
			sb2.append(" : duration = ").append(String.format("%,d", duration));
		}
		sb2.append("\n").append(sb);
		return sb2.toString();
	}

	public static String getMemoryUsageString() {
		long total = Runtime.getRuntime().totalMemory();
		long free = Runtime.getRuntime().freeMemory();
		long max = Runtime.getRuntime().maxMemory();
		long used = total - free;
		String logLine = String.format("%,d",used / 1024) + "KB / " + String.format("%,d", max / 1024) + "KB"; 
		return logLine;
	}

	public long getTotalNanos() {
		return totalNanos;
	}

	public long getTotalTicks() {
		return totalTicks;
	}

	public long getTotalSelectSqlCount() {
		return totalSelectSqlCount;
	}

	public long getTotalUpdateSqlCount() {
		return totalUpdateSqlCount;
	}

	public void addSubsSelectSqlCount(long _subsSelectSqlCount) {
		this.subsSelectSqlCount += _subsSelectSqlCount;
		//this.totalSelectSqlCount += _subsSelectSqlCount;
		this.selectSqlCount += _subsSelectSqlCount;		// mod 2025/04/22 
	}

	public void addSubsUpdateSqlCount(long _subsUpdateSqlCount) {
		this.subsUpdateSqlCount += _subsUpdateSqlCount;
		//this.totalUpdateSqlCount += _subsUpdateSqlCount;
		this.updateSqlCount += _subsUpdateSqlCount;		// mod 2025/04/22 
	}

	public long getSubsSelectSqlCount() {
		return subsSelectSqlCount;
	}

	public long getSubsUpdateSqlCount() {
		return subsUpdateSqlCount;
	}

	public int getSectionNamesHash() {
		StringBuilder sb = new StringBuilder();
		for (String sectionName : sectionTimeMap.keySet()) {
			sb.append(sectionName).append(',');
		}
		return sb.toString().hashCode();
	}

	public Collection<SectionTime> getSectionTimes() {
		return sectionTimeMap.values();
	}

}
