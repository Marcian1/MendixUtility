package nanoprofiler.implementation;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.logging.LogLevel;
import com.mendix.logging.LogMessage;
import com.mendix.logging.LogSubscriber;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IProfiler;
import com.mendix.thirdparty.org.json.JSONObject;

import nanoprofiler.proxies.AutoNanoProfilerConfig;
import nanoprofiler.proxies.AutoNanoProfilerStatus;
import nanoprofiler.proxies.LogLevels;
import nanoprofiler.proxies.constants.Constants;

/**
 */
public class LogSubscriberForNanoProfiler extends LogSubscriber  {
	private static Map<String, LogSubscriberForNanoProfiler> subscribers = new HashMap<String, LogSubscriberForNanoProfiler>();
	private static final ILogNode logger = Core.getLogger("NanoProfiler");

	private static boolean setUncaughtExceptionHandler = Constants.getMonitorRuntimeLoggerThreadDeath();
	private static Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
	private static Throwable uncaughtException;
	private static Date uncaughtExceptionTime;

	private Timer myTimer;
	private AutoNanoProfilerConfig myConfig;
	private boolean isStarted = false;
	private boolean isRegistered = false;
	private Date lastProcessTime;
	private String lastProcessResult = "Never";
	private Date lastErrorTime;
	private String lastErrorInfo = "Nothing";
	private long lastStatusSyncTime = 0L;
	private Map<String, Stack<ProfilerStackElement>> profilerStacks = new LinkedHashMap<String, Stack<ProfilerStackElement>>(16, (float) 0.75, true) {
		protected boolean removeEldestEntry(Map.Entry eldest) {
			return size() > 100;
		}
	};
	private Map<String/*mfName*/, MicroflowStatistics> microflowStatisticsMap = new HashMap<String, MicroflowStatistics>();
	private Map<String/*mfNameWithHash*/, Map<String/*sectionName*/, ActionStatistics>> actionStatisticsMap = new HashMap<String, Map<String, ActionStatistics>>();

	/**
	 */
	public static synchronized LogSubscriberForNanoProfiler getInstance(String configName) throws Exception {
		LogSubscriberForNanoProfiler instance = subscribers.get(configName);
		if (instance == null) {
			instance = new LogSubscriberForNanoProfiler(configName, LogLevel.NONE);
			subscribers.put(configName, instance);
		}
		return instance;
	}

	/**
	 */
	public static synchronized void shutdownAll() throws Exception {
		for (Map.Entry<String, LogSubscriberForNanoProfiler> entry : subscribers.entrySet()) {
			LogSubscriberForNanoProfiler instance = entry.getValue();
			instance.stop();
			Core.getLogger("MicroflowEngine").subscribe(instance, LogLevel.NONE);
			Core.getLogger("ConnectionBus_Retrieve").subscribe(instance, LogLevel.NONE);
			Core.getLogger("ConnectionBus_Update").subscribe(instance, LogLevel.NONE);
		}
		return;
	}

	/*
	 */
	private static String getServerIP() throws UnknownHostException {
		InetAddress ipAdd;
		ipAdd = InetAddress.getLocalHost();
		return ipAdd.getHostAddress();
	}

	/**
	 */
	LogSubscriberForNanoProfiler(String configName, final LogLevel logLevel) {
		super(LogSubscriberForNanoProfiler.class.getName() + "_" + configName, logLevel);
	}

	/**
	 */
	public synchronized void configure(AutoNanoProfilerConfig config) {
		myConfig = config;
		if (!isRegistered) {
			// This have to be registered before node.subscribe().
			Core.registerLogSubscriber(this);
			isRegistered = true;
		}
		if (Core.getProfiler() instanceof MyIProfilerImple) {
			// If already registered, unregister first.
			Core.unregisterProfiler();
		} 
		Core.getLogger("MicroflowEngine").subscribe(this, LogLevel.NONE);
		Core.getLogger("ConnectionBus_Retrieve").subscribe(this, LogLevel.NONE);
		Core.getLogger("ConnectionBus_Update").subscribe(this, LogLevel.NONE);
		Core.getLogger("TwoWaySql").subscribe(this, LogLevel.NONE);

		if (myConfig.getIsEnable()) {
			if (config.getTraceActions()) {
				Core.getLogger("MicroflowEngine").subscribe(this, LogLevel.TRACE);
			} else {
				Core.getLogger("MicroflowEngine").subscribe(this, LogLevel.DEBUG);
			}
			if (config.getCountSqls()) {
				if (myConfig.getUseIProfiler()) {
					try {
						MyIProfilerImple myIProfiler = new MyIProfilerImple();
						myIProfiler.setParent(this);
						Core.registerProfiler(myIProfiler);
						logger.debug("use Core.registerProfiler() to count sql.");
					} catch (Exception e) {
						logger.warn("Core.registerProfiler() failed.", e);
					}
				} else {
					Core.getLogger("ConnectionBus_Retrieve").subscribe(this, LogLevel.DEBUG);
					Core.getLogger("ConnectionBus_Update").subscribe(this, LogLevel.DEBUG);
					logger.debug("use ConnectionBus_Retrieve/Update log to count sql.");
				}
				Core.getLogger("TwoWaySql").subscribe(this, LogLevel.DEBUG);
			}
		}
	}

	/**
	 */
	public boolean isStarted() {
		return isStarted;
	}

	/**
	 */
	public synchronized void start() {
		isStarted = true;
		myTimer = new Timer(true);
		myTimer.scheduleAtFixedRate(new MyTask(), 1000, 1000);
	}

	/**
	 */
	public synchronized void stop() {
		isStarted = false;
		myTimer.cancel();
		myTimer = null;
	}

	/*
	 */
	@Override
	public void processMessage(final LogMessage logMessage) {
		if (isStarted && myConfig.getIsEnable()) {
			if (setUncaughtExceptionHandler) {
				synchronized(this.getClass()) {
					if (uncaughtExceptionHandler == null) {
						uncaughtExceptionHandler = new MyUncaughtExceptionHandler();
						Thread.currentThread().setUncaughtExceptionHandler(uncaughtExceptionHandler);
						logger.info(myConfig.getConfigName() + ": Start monitoring runtime logger thread's death.");
						setUncaughtExceptionHandler = false;		// only once.
					}
				}
			}

			synchronized (this) {
				try {
					if ("MicroflowEngine".equals(logMessage.node.name())) {
						lastProcessTime = Calendar.getInstance().getTime();
						// log messages are like as bellow.
						//	[1711332165774-8] Starting execution of microflow 'SaferOQL.ACT_XPathQueryToolExecute'
						//	[1711332165774-8] Executing activity: {"current_activity":{"type":"Start"},"name":"SaferOQL.ACT_XPathQueryToolExecute","type":"Microflow"}
						//	[1711332165774-8] Executing activity: {"current_activity":{"caption":"Change 'Query' (CurrentPage)","type":"Change"},"name":"SaferOQL.ACT_XPathQueryToolExecute","type":"Microflow"}
						//	[1711332165774-8] Finished execution of microflow 'SaferOQL.ACT_XPathQueryToolExecute'
						if (logMessage.message != null) {
							String message = logMessage.message.toString();
							//logger.info("□ " + message);

							String reg4mf = "\\[(.*?)\\] (Starting|Finished) execution of microflow '(.*?)'";
							Pattern pat4mf = Pattern.compile(reg4mf);
							Matcher mat4mf = pat4mf.matcher(message);
							if (mat4mf.find()) {
								String seqnum = mat4mf.group(1);
								String sttfin = mat4mf.group(2);
								String mfname = mat4mf.group(3);
								//logger.info("■ "+ seqnum + ":" + sttfin + ":" + mfname);
								if (isTarget(mfname, seqnum) && mfname.indexOf(".nested.") < 0) {
									Stack<ProfilerStackElement> profilerStack = getProfilerStack(seqnum);
									if (sttfin.equals("Starting")) {
										NanoProfiler profiler = new NanoProfiler(mfname + " by AutoMode[" + myConfig.getConfigName() + "]", myConfig.getCountSqls(), myConfig.getShowMemoryUsage());
										profiler.tick(null, "[Start]");
										profilerStack.push(new ProfilerStackElement(mfname, profiler));
									} else {
										// Finished
										if (!profilerStack.isEmpty()) {
											String callHierarchy = "";
											if (myConfig.getRecordStatistics()) {
												callHierarchy = getCallHierarchy(profilerStack);	// Get CallHierarchy before pop.
											}
											ProfilerStackElement profilerStackElement = profilerStack.pop();
											NanoProfiler profiler = profilerStackElement.getProfiler();
											profiler.tick(null, "[End]");
											if (myConfig.getReportThresholdMillis() == 0L //
													|| profiler.getTotalNanos() > (myConfig.getReportThresholdMillis() * 1000 * 1000)) {
												String report = profiler.report(null);
												doLog(myConfig.getReportingLogLevel(), report);
												if (myConfig.getRecordStatistics()) {
													recordStatistics(mfname, callHierarchy, profiler);
												}
											}
											if (!profilerStack.isEmpty()) {
												ProfilerStackElement callerProfilerStackElement = profilerStack.peek();
												callerProfilerStackElement.getProfiler().addSubsSelectSqlCount(profilerStackElement.getProfiler().getTotalSelectSqlCount());
												callerProfilerStackElement.getProfiler().addSubsUpdateSqlCount(profilerStackElement.getProfiler().getTotalUpdateSqlCount());
											}
										}
										// If it became empty, remove the ProfilerStack from the Map.
										if (profilerStack.isEmpty()) {
											removeProfilerStack(seqnum);
										}
									}
								}
							} else {
								//String reg4ac = "\\[(.*?)\\] Executing activity: \\{\"current_activity\":\\{\"caption\":\"(.*?)\",.*?\"name\":\"(.*?)\".*?";
								String reg4ac = "\\[(.*?)\\] Executing activity: \\{(.*?)\"current_activity\":\\{\"caption\":\"(.*?)\",.*?\"name\":\"(.*?)\".*?";
								Pattern pat4ac = Pattern.compile(reg4ac);
								Matcher mat4ac = pat4ac.matcher(message);
								if (mat4ac.find()) {
									// before execute action
									String seqnum = mat4ac.group(1);
									String acid = mat4ac.group(2);
									String caption = mat4ac.group(3);
									String mfname = mat4ac.group(4).replaceFirst("\\.nested\\..*", "");
									//logger.info("■■ "+ seqnum + ":" + mfname + ":" + caption);
									Stack<ProfilerStackElement> profilerStack = getProfilerStack(seqnum);
									if (!profilerStack.isEmpty() && caption.length() > 0) {
										ProfilerStackElement profilerStackElement = profilerStack.peek();
										if (mfname.equals(profilerStackElement.getMicroflowName())) {
											NanoProfiler profiler = profilerStackElement.getProfiler();
											if (acid != null && acid.length() > 7) {
												caption = caption + "(" + acid.substring(acid.length() - 6).substring(0, 4) + ")";
											}
											profiler.tick(null, "[" + caption + "]");
										}
									}
								}
							}

							lastProcessResult = "Success";
						}
					} else if ("ConnectionBus_Retrieve".equals(logMessage.node.name())) {
						if (logMessage.message != null) {
							String message = logMessage.message.toString();
							if (message.indexOf("SQL@") >= 0) {
								for (Stack<ProfilerStackElement> st : profilerStacks.values()) {
									if (!st.isEmpty()) {
										st.peek().getProfiler().tickSelectSql(null, (myConfig.getCountSqls()&&myConfig.getShowSqlStatements())? message.replaceFirst("SQL@.*?:", ""): null);
									}
								}
							}
						}
					} else if ("ConnectionBus_Update".equals(logMessage.node.name())) {
						if (logMessage.message != null) {
							String message = logMessage.message.toString();
							if (message.indexOf("SQL@") >= 0) {
								for (Stack<ProfilerStackElement> st : profilerStacks.values()) {
									if (!st.isEmpty()) {
										st.peek().getProfiler().tickUpdateSql(null, (myConfig.getCountSqls()&&myConfig.getShowSqlStatements())? message.replaceFirst("SQL@.*?:", ""): null);
									}
								}
							}
						}
					} else if ("TwoWaySql".equals(logMessage.node.name())) {
						if (logMessage.message != null) {
							String message = logMessage.message.toString();
							if (message.indexOf("PreparedSql=") >= 0) {
								for (Stack<ProfilerStackElement> st : profilerStacks.values()) {
									if (!st.isEmpty()) {
										String sql = message.replaceFirst(".*PreparedSql=\n", "").replaceAll("\\s(\\s)*", " ").replaceFirst("BindVariables", ":BindVariables");
										if (message.indexOf("updateByTwoWaySql") >= 0) {
											st.peek().getProfiler().tickUpdateSql(null, (myConfig.getCountSqls()&&myConfig.getShowSqlStatements())? sql: null);
										} else {
											st.peek().getProfiler().tickSelectSql(null, (myConfig.getCountSqls()&&myConfig.getShowSqlStatements())? sql: null);
										}
									}
								}
							}
						}
					}

				} catch (Exception e) {
					lastProcessResult = "Failure";
					lastErrorTime = Calendar.getInstance().getTime();
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw);
					e.printStackTrace(pw);
					lastErrorInfo = sw.toString();

					//logger.error(lastErrorInfo);
					logger.trace(lastErrorInfo);

				} finally {

				}
			}
		}
	}

	/**
	 *
	 */
	private Stack<ProfilerStackElement> getProfilerStack(String seqnum) {
		Stack<ProfilerStackElement> profilerStack = profilerStacks.get(seqnum);
		if (profilerStack == null) {
			profilerStack = new Stack<ProfilerStackElement>();
			profilerStacks.put(seqnum, profilerStack);
		}
		return profilerStack;
	}

	/**
	 *
	 */
	private void removeProfilerStack(String seqnum) {
		profilerStacks.remove(seqnum);
	}

	/*
	 *
	 */
	public static boolean isTarget(String includeRegex, String excludeRegex, String mfname, Boolean isRecursive) {
		boolean included = true;

		if (isRecursive == null || isRecursive == false) {		// if (isRecursive == true) then included.
			// check included
			if (includeRegex != null) {
				String includes = includeRegex.trim();
				if (includes.length() > 0) {
					included = Pattern.compile(includes).matcher(mfname).find();
				}
			}
		}

		// check excluded
		if (included && excludeRegex != null) {
			String excludes = excludeRegex.trim();
			if (excludes.length() > 0) {
				included = !Pattern.compile(excludes).matcher(mfname).find();
			}
		}

		return included;
	}

	/*
	 *
	 */
	private boolean isTarget(String mfname, String seqnum) {
		return isTarget(myConfig.getIncludeMicroflowsRegexp(), myConfig.getExcludeMicroflowsRegexp(), mfname, isRecursive(seqnum));
	}

	/*
	 *
	 */
	private boolean isRecursive(String seqnum) {
		if (!myConfig.getApplyRecursively()) {
			return false;
		} else {
			Stack<ProfilerStackElement> profilerStack = profilerStacks.get(seqnum);
			if (profilerStack != null && profilerStack.size() >= 1) {
				return true;
			} else {
				return false;
			}
		}
	}

	/**
	 */
	private static void doLog(LogLevels level, String message) {
		switch (level) {
		case TRACE:
			logger.trace(message);
			break;
		case DEBUG:
			logger.debug(message);
			break;
		case INFO:
			logger.info(message);
			break;
		case WARNING:
			logger.warn(message);
			break;
		case ERROR:
			logger.error(message);
			break;
		case CRITICAL:
			logger.critical(message);
			break;
		}
	}

	private String getCallHierarchy(Stack<ProfilerStackElement> profilerStack) {
		StringBuilder sb = new StringBuilder();
		StringBuilder sb2 = new StringBuilder();
		for (ProfilerStackElement pse : profilerStack) {
			if (sb.length() > 0) {
				sb.append("/");
				sb2.append("  ");
			}
			sb.append(pse.getMicroflowName());
		}
		return sb2.append(sb).toString();
	}

	/**
	 */
	private void recordStatistics(String mfname, String callHierarchy, NanoProfiler profiler) {
		MicroflowStatistics mfStat = microflowStatisticsMap.get(callHierarchy);
		if (mfStat == null) {
			mfStat = new MicroflowStatistics(mfname, callHierarchy);
			microflowStatisticsMap.put(callHierarchy, mfStat);
		}
		mfStat.record(profiler.getTotalNanos(), profiler.getTotalSelectSqlCount(), profiler.getTotalUpdateSqlCount() //
				, profiler.getSubsSelectSqlCount(), profiler.getSubsUpdateSqlCount());

		if (myConfig.getTraceActions()) {
			String mfnameWithHash = mfname + " (0x" + Integer.toHexString(profiler.getSectionNamesHash()) + ")";
			Map<String, ActionStatistics> acStatMap = actionStatisticsMap.get(mfnameWithHash);
			if (acStatMap == null) {
				acStatMap = new LinkedHashMap<String, ActionStatistics>();
				actionStatisticsMap.put(mfnameWithHash, acStatMap);
			}
			for (SectionTime sectionTime : profiler.getSectionTimes()) {
				ActionStatistics acStat = acStatMap.get(sectionTime.getSectionName());
				if (acStat == null) {
					acStat = new ActionStatistics(mfnameWithHash, sectionTime.getSectionName());
					acStatMap.put(sectionTime.getSectionName(), acStat);
				}
				acStat.record(sectionTime.getExecutedCount() ,sectionTime.getAccumulatedNanos(), sectionTime.getSelectSqlCount(), sectionTime.getUpdateSqlCount(), sectionTime.getSqlMap());
			}
		}
	}

	/**
	 */
	private synchronized String getMicroflowStatisticsAsCSV() throws UnknownHostException {
		TreeSet<String> callHierarchySet = new TreeSet<String>(new Comparator<String>() {
			  @Override
			  public int compare(String a, String b) {
			    return a.trim().compareTo(b.trim());
			  }
			});
		callHierarchySet.addAll(microflowStatisticsMap.keySet());
		StringBuilder sb = new StringBuilder();
		sb.append("ip,configName,microflowName,callHierarchy,recordedCount,totalNanos,averagelNanos,minNanos,maxNanos");
		if (myConfig.getCountSqls()) {
			sb.append(",totalSelSqlCnt,totalUpdSqlCnt,subsSelSqlCnt,subsUpdSqlCnt");
		}
		sb.append("\r\n");
		for (String ch : callHierarchySet) {
			MicroflowStatistics ms = microflowStatisticsMap.get(ch);
			sb.append(getServerIP());
			sb.append(",").append(myConfig.getConfigName());
			sb.append(",").append(ms.getMicroflowName());
			sb.append(",").append(ms.getCallHierarchy());
			sb.append(",").append(ms.getRecordedCount());
			sb.append(",").append(ms.getTotalNanos());
			sb.append(",").append(ms.getTotalNanos()/ms.getRecordedCount());
			sb.append(",").append(ms.getMinNanos());
			sb.append(",").append(ms.getMaxNanos());
			if (myConfig.getCountSqls()) {
				sb.append(",").append(ms.getTotalSelectSqlCount());
				sb.append(",").append(ms.getTotalUpdateSqlCount());
				sb.append(",").append(ms.getSubsSelectSqlCount());
				sb.append(",").append(ms.getSubsUpdateSqlCount());
			}
			sb.append("\r\n");
		}
		return sb.toString();
	}


	/**
	 */
	private synchronized String getActionStatisticsAsCSV() throws UnknownHostException {
		TreeSet<String> microflowNameSet = new TreeSet<String>(new Comparator<String>() {
			  @Override
			  public int compare(String a, String b) {
			    return a.trim().compareTo(b.trim());
			  }
			});
		microflowNameSet.addAll(actionStatisticsMap.keySet());
		StringBuilder sb = new StringBuilder();
		sb.append("ip,configName,microflowName (sectionNamesHash),seq,sectionName,executedCount,totalNanos,averagelNanos");
		if (myConfig.getCountSqls()) {
			sb.append(",totalSelSqlCnt,totalUpdSqlCnt");
			if (myConfig.getShowSqlStatements()) {
				sb.append(",RecordedSqls");
			}
		}
		sb.append("\r\n");
		for (String mfName : microflowNameSet) {
			Map<String, ActionStatistics> acStatMap =  actionStatisticsMap.get(mfName);
			int seq = 0;
			for (ActionStatistics as : acStatMap.values()) {
				sb.append(getServerIP());
				sb.append(",").append(myConfig.getConfigName());
				sb.append(",").append(as.getMicroflowName());
				sb.append(",").append(++seq);
				sb.append(",\"").append(as.getSectionName());
				sb.append("\",").append(as.getTotalExecutedCount());
				sb.append(",").append(as.getTotalNanos());
				sb.append(",").append(as.getTotalNanos()/as.getTotalExecutedCount());
				if (myConfig.getCountSqls()) {
					sb.append(",").append(as.getTotalSelectSqlCount());
					sb.append(",").append(as.getTotalUpdateSqlCount());
					if (myConfig.getShowSqlStatements()) {
						StringBuilder sqls = new StringBuilder();
						for (Entry<String, Long> entry : as.getSqlMap().entrySet()) {
							if (sqls.length() > 0) {
								sqls.append("\r\n");
							}
							sqls.append(entry.getValue()).append(" times of : ").append(entry.getKey().replaceFirst("SELECT .*? FROM", "SELECT ... FROM").replaceAll("\"", "\"\""));
						}
						sb.append(",\"").append(sqls.toString()).append("\"");
					}
				}
				sb.append("\r\n");
			}
		}
		return sb.toString();
	}

	/**
	 */
	private synchronized String getRunningMicroflowsAsText() throws UnknownHostException {
		StringBuilder sb = new StringBuilder();
		for (Entry<String, Stack<ProfilerStackElement>> ent : profilerStacks.entrySet()) {
			String executionId = ent.getKey();
			Stack<ProfilerStackElement> pseStack = ent.getValue();
			sb.append("##############");
			sb.append(" ip = ").append(getServerIP());
			sb.append(", configName = ").append(myConfig.getConfigName());
			sb.append(", executionId = ").append(executionId);
			sb.append(" ##############\n");
			int depth = 0;
			for (ProfilerStackElement pse : pseStack) {
				if (depth > 0 && myConfig.getTraceActions()) {
					sb.append("\n");
				}
				sb.append(pse.profiler.report(null));
				depth += 1;
			}
			sb.append("\n");
		}
		return sb.toString();
	}

	/*
	 */
	private class MyTask extends TimerTask {
		public void run() {
			if (isStarted) {
				Long currentTime = System.currentTimeMillis();
				if (currentTime > (lastStatusSyncTime + 30000)) {
					updateAutoNanoProfilerStatus();
					lastStatusSyncTime = currentTime;
				}
			}
		}
	}

	/*
	 */
	private void updateAutoNanoProfilerStatus() {
		// update SlackLoggerStatus entity
		// SlackLoggerStatusをリトリーブして、無ければ新規生成
		// 内容をprivate変数で最新化してupdateする
		IContext context = Core.createSystemContext();
		context.startTransaction();
		try {
			AutoNanoProfilerStatus status;
			List<IMendixObject> resultList = Core.createXPathQuery(
					"//NanoProfiler.AutoNanoProfilerStatus" + "[ConfigName = '" + myConfig.getConfigName()
							+ "'][IpAddress = '" + getServerIP() + "']")
					.execute(context);
			if (resultList.size() == 0) {
				status = new AutoNanoProfilerStatus(context);
				status.setIpAddress(getServerIP());
				status.setConfigName(myConfig.getConfigName());
			} else {
				status = AutoNanoProfilerStatus.initialize(context, resultList.get(0));
				if (status.getReconfigRequest()) {
					List<IMendixObject> configList = Core.createXPathQuery(
							"//NanoProfiler.AutoNanoProfilerConfig" + "[ConfigName = '" + myConfig.getConfigName() + "']")
							.execute(context);
					if (configList.size() == 1) {
						stop();
						configure(AutoNanoProfilerConfig.initialize(context, configList.get(0)));
						start();
					}
					status.setReconfigRequest(false);
					microflowStatisticsMap.clear();
					actionStatisticsMap.clear();
				}
			}
			status.setConfigVersionNum(myConfig.getConfigVersionNum());
			status.setLastProcessTime(lastProcessTime);
			status.setLastProcessResult(lastProcessResult);
			status.setLastErrorTime(lastErrorTime);
			if (uncaughtException != null) {
				// ロガースレッドが死んでるので原因を表示
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				uncaughtException.printStackTrace(pw);
				status.setLastErrorInfo(uncaughtExceptionTime.toString() + //
						"\nThe Mendix runtime logger thread terminated abnormally.\n" + sw.toString());
			} else {
				status.setLastErrorInfo(lastErrorInfo);
			}
			if (myConfig.getRecordStatistics()) {
				status.setMicroflowStatistics(getMicroflowStatisticsAsCSV());
				if (myConfig.getTraceActions()) {
					status.setActionStatistics(getActionStatisticsAsCSV());
				} else {
					status.setActionStatistics("");
				}
			} else {
				status.setMicroflowStatistics("");
				status.setActionStatistics("");
			}
			if (myConfig.getStateRunningMicroflows()) {
				status.setRunningMicroflows(getRunningMicroflowsAsText());
			} else {
				status.setRunningMicroflows("");
			}
			status.getMendixObject().setValue(context, "changedDate", new Timestamp(System.currentTimeMillis()));
			status.commit();
		} catch (Exception e) {
			logger.error("Status update failed:", e);
		} finally {
			context.endTransaction();
		}
	}

	/*
	 *
	 */
	private class ProfilerStackElement {
		String microflowName;
		NanoProfiler profiler;
		ProfilerStackElement(String m, NanoProfiler p) {
			this.microflowName = m;
			this.profiler = p;
		}

		String getMicroflowName() {
			return this.microflowName;
		}

		NanoProfiler getProfiler() {
			return profiler;
		}
	}
	
	/*
	 *
	 */
	class MyIProfilerImple implements IProfiler {
		
		private LogSubscriberForNanoProfiler parent;
		public void setParent(LogSubscriberForNanoProfiler _parent) {
			parent = _parent;
		}

		@Override
		public Object enterDatabase(String arg0, String arg1, String arg2, Long arg3) {
			// arg0 : SessionID
			// arg1 : ExecutionID on Context
			// arg2 : SQL
			// arg3 : ?
			synchronized (parent) {
				Stack<ProfilerStackElement> st = profilerStacks.get(arg1);
				if (arg2.startsWith("SELECT") || arg2.startsWith("select")) {
					st.peek().getProfiler().tickSelectSql(null, (myConfig.getCountSqls()&&myConfig.getShowSqlStatements())? arg2: null);
				} else {
					st.peek().getProfiler().tickUpdateSql(null, (myConfig.getCountSqls()&&myConfig.getShowSqlStatements())? arg2: null);
				}
			}
			return null;
		}

		//@Override
		public Object enterRuntime(String arg0, String arg1, String arg2, Set<String> arg3, JSONObject arg4,
				Long arg5) {
			return null;
		}

		@Override
		public void finishDatabase(Object arg0, Long arg1) {
		}

		@Override
		public void finishRuntime(Object arg0, Long arg1) {			
		}

		//@Override
		public void logClientData(JSONObject arg0, String arg1) {
		}

		@Override
		public void stop() {
		}		
	}
	
	/*
	 * 
	 */
	private class MyUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
		@Override
		public void uncaughtException(Thread t, Throwable e) {
			uncaughtException = e;
			lastErrorTime = Calendar.getInstance().getTime();
			uncaughtExceptionTime = Calendar.getInstance().getTime();
		}
	}

}
