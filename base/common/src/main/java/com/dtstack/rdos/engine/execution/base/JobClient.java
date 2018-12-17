package com.dtstack.rdos.engine.execution.base;

import com.dtstack.rdos.commom.exception.RdosException;
import com.dtstack.rdos.common.util.MathUtil;
import com.dtstack.rdos.common.util.PublicUtil;
import com.dtstack.rdos.engine.execution.base.constrant.ConfigConstant;
import com.dtstack.rdos.engine.execution.base.pojo.JobResult;
import com.dtstack.rdos.engine.execution.base.pojo.ParamAction;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dtstack.rdos.engine.execution.base.queue.OrderObject;
import com.dtstack.rdos.engine.execution.base.enums.ComputeType;
import com.dtstack.rdos.engine.execution.base.enums.EJobType;
import com.dtstack.rdos.engine.execution.base.enums.RdosTaskStatus;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reason:
 * Date: 2017/2/21
 * Company: www.dtstack.com
 * @author xuchao
 */

public class JobClient extends OrderObject{

    private static final Logger logger = LoggerFactory.getLogger(JobClient.class);

    /**默认的优先级，值越小，优先级越高*/
    private static final int DEFAULT_PRIORITY_LEVEL_VALUE = 10;

    /**用户填写的优先级占的比重*/
    private static final int PRIORITY_LEVEL_WEIGHT = 100000;

    private JobClientCallBack jobClientCallBack;

    private List<JarFileInfo> attachJarInfos = Lists.newArrayList();

    private JarFileInfo coreJarInfo;

    private Properties confProperties;

    private String sql;

    private String taskParams;

    private String jobName;

    private String taskId;

    private String engineTaskId;

    private String applicationId;

    private EJobType jobType;

    private ComputeType computeType;

    private String engineType;

    private JobResult jobResult;

    /**externalPath 不为null则为从保存点恢复*/
    private String externalPath;

    /**提交MR执行的时候附带的执行参数*/
    private String classArgs;

    private int again = 1;

    private String groupName;

    private int priorityLevel = 0;

    private String pluginInfo;

    private long restartTime = 0;

    private long generateTime;

    /**
     * 任务失败是否重试
     */
    private boolean isFailRetry;

    /***
     * 获取engine上job执行的状态
     * @param jobIdentifier
     * @return
     */
    public static RdosTaskStatus getStatus(String engineType, String pluginInfo, JobIdentifier jobIdentifier) {
        return ClientOperator.getInstance().getJobStatus(engineType, pluginInfo, jobIdentifier);
    }

    public static String getEngineLog(String engineType, String pluginInfo, JobIdentifier jobIdentifier){
        return ClientOperator.getInstance().getEngineLog(engineType, pluginInfo, jobIdentifier);
    }

    public static String getInfoByHttp(String engineType, String path, String pluginInfo){
        return ClientOperator.getInstance().getEngineMessageByHttp(engineType, path, pluginInfo);
    }

    public static String getCheckpoints(String engineType, String pluginInfo, JobIdentifier jobIdentifier){
        return ClientOperator.getInstance().getCheckpoints(engineType, pluginInfo, jobIdentifier);
    }

    @Deprecated
    public JobClient() {

    }

    public JobClient(ParamAction paramAction) throws Exception {
        this.sql = paramAction.getSqlText();
        this.taskParams = paramAction.getTaskParams();
        this.jobName = paramAction.getName();
        this.taskId = paramAction.getTaskId();
        this.engineTaskId = paramAction.getEngineTaskId();
        this.applicationId = paramAction.getApplicationId();
        this.jobType = EJobType.getEJobType(paramAction.getTaskType());
        this.computeType = ComputeType.getType(paramAction.getComputeType());
        this.externalPath = paramAction.getExternalPath();
        this.engineType = paramAction.getEngineType();
        this.classArgs = paramAction.getExeArgs();
        this.restartTime = paramAction.getRestartTime();
        this.generateTime = paramAction.getGenerateTime();
        this.isFailRetry = paramAction.getIsFailRetry();
        if(paramAction.getPluginInfo() != null){
            this.pluginInfo = PublicUtil.objToString(paramAction.getPluginInfo());
        }
        if(taskParams != null){
            this.confProperties = PublicUtil.stringToProperties(taskParams);
        }
        if (paramAction.getPriority() <= 0){
            String valStr = confProperties == null ? null : confProperties.getProperty(ConfigConstant.CUSTOMER_PRIORITY_VAL);
            this.priorityLevel = valStr == null ? DEFAULT_PRIORITY_LEVEL_VALUE : MathUtil.getIntegerVal(valStr);
            //设置priority值, 值越小，优先级越高
            this.priority = paramAction.getGenerateTime() + priorityLevel * PRIORITY_LEVEL_WEIGHT;
        } else {
            priority = paramAction.getPriority();
        }
        this.groupName = paramAction.getGroupName();
        if (StringUtils.isBlank(groupName)){
            groupName = ConfigConstant.DEFAULT_GROUP_NAME;
        }
        //将任务id 标识为对象id
        this.id = taskId;

    }

    public ParamAction getParamAction(){
        ParamAction action = new ParamAction();
        action.setSqlText(sql);
        action.setTaskParams(taskParams);
        action.setName(jobName);
        action.setTaskId(taskId);
        action.setEngineTaskId(engineTaskId);
        action.setTaskType(jobType.getType());
        action.setComputeType(computeType.getType());
        action.setExternalPath(externalPath);
        action.setEngineType(engineType);
        action.setExeArgs(classArgs);
        action.setGroupName(groupName);
        action.setRestartTime(restartTime);
        action.setGenerateTime(generateTime);
        action.setPriority(priority);
        action.setApplicationId(applicationId);
        action.setIsFailRetry(isFailRetry);
        if(!Strings.isNullOrEmpty(pluginInfo)){
            try{
                action.setPluginInfo(PublicUtil.jsonStrToObject(pluginInfo, Map.class));
            }catch (Exception e){
                //不应该走到这个异常,这个数据本身是由map转换过来的
                logger.error("", e);
            }
        }
        return action;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getEngineTaskId() {
        return engineTaskId;
    }

    public void setEngineTaskId(String engineTaskId) {
        this.engineTaskId = engineTaskId;
    }


    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public EJobType getJobType() {
        return jobType;
    }

    public void setJobType(EJobType jobType) {
        this.jobType = jobType;
    }

    public JobResult getJobResult() {
        return jobResult;
    }

    public void setJobResult(JobResult jobResult) {
        this.jobResult = jobResult;
    }

    public ComputeType getComputeType() {
        return computeType;
    }

    public void setComputeType(ComputeType computeType) {
        this.computeType = computeType;
    }

    public String getEngineType() {
        return engineType;
    }

    public void setEngineType(String engineType) {
        this.engineType = engineType;
    }

    public Properties getConfProperties() {
        return confProperties;
    }

    public List<JarFileInfo> getAttachJarInfos() {
        return attachJarInfos;
    }

    public void setAttachJarInfos(List<JarFileInfo> attachJarInfos) {
        this.attachJarInfos = attachJarInfos;
    }

    public void addAttachJarInfo(JarFileInfo jarFileInfo){
        attachJarInfos.add(jarFileInfo);
    }

    public void doStatusCallBack(Integer status){
        if(jobClientCallBack == null){
            throw new RdosException("not set jobClientCallBak...");
        }
        jobClientCallBack.updateStatus(status);
    }

    public void setCallBack(JobClientCallBack jobClientCallBack) {
        this.jobClientCallBack = jobClientCallBack;
    }

    public String getSql() {
        return sql;
    }

    public String getTaskParams() {
        return taskParams;
    }

    public String getClassArgs() {
        return classArgs;
    }

    public void setClassArgs(String classArgs) {
        this.classArgs = classArgs;
    }

	public void stopJob() throws Exception {
        JobSubmitExecutor.getInstance().stopJob(this);
	}

	public int getAgain() {
		return again;
	}

	public void setAgain(int again) {
		this.again = again;
	}

    public void setSql(String sql) {
        this.sql = sql;
    }

    public void setTaskParams(String taskParams) {
        this.taskParams = taskParams;
    }

    public String getExternalPath() {
        return externalPath;
    }

    public void setExternalPath(String externalPath) {
        this.externalPath = externalPath;
    }

    public String getGroupName() {
        return groupName + "_" + computeType.name().toLowerCase();
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public int getPriorityLevel() {
        return priorityLevel;
    }

    public void setPriorityLevel(int priorityLevel) {
        this.priorityLevel = priorityLevel;
    }

    public String getPluginInfo() {
        return pluginInfo;
    }

    public void setPluginInfo(String pluginInfo) {
        this.pluginInfo = pluginInfo;
    }

    public JarFileInfo getCoreJarInfo() {
        return coreJarInfo;
    }

    public void setCoreJarInfo(JarFileInfo coreJarInfo) {
        this.coreJarInfo = coreJarInfo;
    }

    public long getRestartTime() {
        return restartTime;
    }

    public void setRestartTime(long restartTime) {
        this.restartTime = restartTime;
    }

    public long getGenerateTime() {
        return generateTime;
    }

    public void setGenerateTime(long generateTime) {
        this.generateTime = generateTime;
    }

    public int getJobPriority() {
        return Integer.MAX_VALUE - (int)(getPriority() / 1000);
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public boolean getIsFailRetry() {
        return isFailRetry;
    }

    public void setIsFailRetry(boolean failRetry) {
        isFailRetry = failRetry;
    }

    @Override
    public String toString() {
        return "JobClient{" +
                " jobClientCallBack=" + jobClientCallBack +
                ", attachJarInfos=" + attachJarInfos +
                ", coreJar=" + coreJarInfo +
                ", confProperties=" + confProperties +
                ", sql='" + sql +
                ", taskParams='" + taskParams +
                ", jobName='" + jobName+
                ", taskId='" + taskId +
                ", engineTaskId='" + engineTaskId +
                ", jobType=" + jobType +
                ", computeType=" + computeType +
                ", engineType='" + engineType +
                ", jobResult=" + jobResult +
                ", externalPath=" + externalPath +
                ", classArgs='" + classArgs +
                ", again=" + again +
                ", groupName=" + groupName +
                ", restartTime=" + restartTime +
                ", generateTime=" + generateTime +
                ", priority=" + priority +
                ", isFailRetry=" + isFailRetry +
                '}';
    }
}
