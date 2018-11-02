package com.dtstack.rdos.engine.execution.sparkyarn;

import com.dtstack.rdos.commom.exception.ExceptionUtil;
import com.dtstack.rdos.commom.exception.RdosException;
import com.dtstack.rdos.common.http.PoolHttpClient;
import com.dtstack.rdos.common.util.DtStringUtil;
import com.dtstack.rdos.common.util.PublicUtil;
import com.dtstack.rdos.engine.execution.base.AbsClient;
import com.dtstack.rdos.engine.execution.base.JarFileInfo;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.dtstack.rdos.engine.execution.base.JobParam;
import com.dtstack.rdos.engine.execution.base.enums.ComputeType;
import com.dtstack.rdos.engine.execution.base.enums.EJobType;
import com.dtstack.rdos.engine.execution.base.enums.RdosTaskStatus;
import com.dtstack.rdos.engine.execution.base.pojo.EngineResourceInfo;
import com.dtstack.rdos.engine.execution.base.pojo.JobResult;
import com.dtstack.rdos.engine.execution.base.util.HadoopConfTool;
import com.dtstack.rdos.engine.execution.sparkext.ClientExt;
import com.dtstack.rdos.engine.execution.sparkyarn.parser.AddJarOperator;
import com.dtstack.rdos.engine.execution.sparkyarn.util.HadoopConf;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.deploy.yarn.Client;
import org.apache.spark.deploy.yarn.ClientArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Created by softfly on 17/8/10.
 */
public class SparkYarnClient extends AbsClient {

    private static final Logger logger = LoggerFactory.getLogger(SparkYarnClient.class);

    private static final String HADOOP_USER_NAME = "HADOOP_USER_NAME";

    private static final String SPARK_YARN_MODE = "SPARK_YARN_MODE";

    private static final String HDFS_PREFIX = "hdfs://";

    private static final String HTTP_PREFIX = "http://";

    private static final String KEY_PRE_STR = "spark.";

    private static final String PYTHON_RUNNER_CLASS = "org.apache.spark.deploy.PythonRunner";

    private static final String CLUSTER_INFO_WS_FORMAT = "%s/ws/v1/cluster";

    /**如果请求 CLUSTER_INFO_WS_FORMAT 返回信息包含该特征则表示是alive*/
    private static final String ALIVE_WEB_FLAG = "clusterInfo";

    private List<String> webAppAddrList = Lists.newArrayList();

    private SparkYarnConfig sparkYarnConfig;

    private Configuration yarnConf;

    private YarnClient yarnClient;

    @Override
    public void init(Properties prop) throws Exception {
        String propStr = PublicUtil.objToString(prop);
        sparkYarnConfig = PublicUtil.jsonStrToObject(propStr, SparkYarnConfig.class);
        setHadoopUserName(sparkYarnConfig);
        initYarnConf(sparkYarnConfig);
        sparkYarnConfig.setDefaultFS(yarnConf.get(HadoopConfTool.FS_DEFAULTFS));
        System.setProperty(SPARK_YARN_MODE, "true");
        parseWebAppAddr();
        yarnClient = YarnClient.createYarnClient();
        yarnClient.init(yarnConf);
        yarnClient.start();
    }

    private void initYarnConf(SparkYarnConfig sparkConfig){
        HadoopConf customerConf = new HadoopConf();
        customerConf.initHadoopConf(sparkConfig.getHadoopConf());
        customerConf.initYarnConf(sparkConfig.getYarnConf());

        yarnConf = customerConf.getYarnConfiguration();
    }

    @Override
    protected JobResult processSubmitJobWithType(JobClient jobClient) {
        EJobType jobType = jobClient.getJobType();
        JobResult jobResult = null;
        if(EJobType.MR.equals(jobType)){
            jobResult = submitJobWithJar(jobClient);
        }else if(EJobType.SQL.equals(jobType)){
            jobResult = submitSqlJob(jobClient);
        }else if(EJobType.PYTHON.equals(jobType)){
            jobResult = submitPythonJob(jobClient);
        }
        return jobResult;
    }

    private JobResult submitJobWithJar(JobClient jobClient){
        setHadoopUserName(sparkYarnConfig);
        JobParam jobParam = new JobParam(jobClient);
        String mainClass = jobParam.getMainClass();
        //只支持hdfs
        String jarPath = jobParam.getJarPath();
        String appName = jobParam.getJobName();
        String exeArgsStr = jobParam.getClassArgs();

        if(!jarPath.startsWith(HDFS_PREFIX)){
            throw new RdosException("spark jar path protocol must be " + HDFS_PREFIX);
        }

        if(Strings.isNullOrEmpty(appName)){
            throw new RdosException("spark jar must set app name!");
        }


        String[] appArgs = new String[]{};
        if(org.apache.commons.lang3.StringUtils.isNotBlank(exeArgsStr)){
            appArgs = exeArgsStr.split("\\s+");
        }

        List<String> argList = new ArrayList<>();
        argList.add("--jar");
        argList.add(jarPath);
        argList.add("--class");
        argList.add(mainClass);

        for(String appArg : appArgs) {
            argList.add("--arg");
            argList.add(appArg);
        }

        ClientArguments clientArguments = new ClientArguments(argList.toArray(new String[argList.size()]));
        SparkConf sparkConf = buildBasicSparkConf();
        sparkConf.setAppName(appName);
        fillExtSparkConf(sparkConf, jobClient.getConfProperties());

        ApplicationId appId = null;

        try {
            ClientExt clientExt = new ClientExt(clientArguments, yarnConf, sparkConf);
            clientExt.setSparkYarnConfig(sparkYarnConfig);
            appId = clientExt.submitApplication(jobClient.getJobPriority());
            return JobResult.createSuccessResult(appId.toString());
        } catch(Exception ex) {
            logger.info("", ex);
            return JobResult.createErrorResult("submit job get unknown error\n" + ExceptionUtil.getErrorMessage(ex));
        }

    }

    private JobResult submitPythonJob(JobClient jobClient){
        setHadoopUserName(sparkYarnConfig);
        JobParam jobParam = new JobParam(jobClient);
        //.py .egg .zip 存储的hdfs路径
        String pyFilePath = jobParam.getJarPath();
        String appName = jobParam.getJobName();
        String exeArgsStr = jobParam.getClassArgs();

        if(Strings.isNullOrEmpty(pyFilePath)){
            return JobResult.createErrorResult("exe python file can't be null.");
        }

        if(Strings.isNullOrEmpty(appName)){
            return JobResult.createErrorResult("an application name must be set in your configuration");
        }

        ApplicationId appId = null;

        List<String> argList = new ArrayList<>();
        argList.add("--primary-py-file");
        argList.add(pyFilePath);

        argList.add("--class");
        argList.add(PYTHON_RUNNER_CLASS);

        String[] appArgs = new String[]{};
        if(org.apache.commons.lang3.StringUtils.isNotBlank(exeArgsStr)){
            appArgs = exeArgsStr.split("\\s+");
        }

        for(String appArg : appArgs) {
            argList.add("--arg");
            argList.add(appArg);
        }

        String pythonExtPath = sparkYarnConfig.getSparkPythonExtLibPath();
        if(Strings.isNullOrEmpty(pythonExtPath)){
            return JobResult.createErrorResult("engine node.yml setting error, " +
                    "commit spark python job need to set param of sparkPythonExtLibPath.");
        }

        SparkConf sparkConf = buildBasicSparkConf();
        sparkConf.set("spark.submit.pyFiles", pythonExtPath);
        sparkConf.setAppName(appName);
        fillExtSparkConf(sparkConf, jobClient.getConfProperties());

        try {
            ClientArguments clientArguments = new ClientArguments(argList.toArray(new String[argList.size()]));
            appId = new Client(clientArguments, yarnConf, sparkConf).submitApplication();
            return JobResult.createSuccessResult(appId.toString());
        } catch(Exception ex) {
            logger.info("", ex);
            return JobResult.createErrorResult("submit job get unknown error\n" + ExceptionUtil.getErrorMessage(ex));
        }
    }

    /**
     * 执行spark 批处理sql
     * @param jobClient
     * @return
     */
    private JobResult submitSparkSqlJobForBatch(JobClient jobClient){
        setHadoopUserName(sparkYarnConfig);

        Map<String, Object> paramsMap = new HashMap<>();

        String zipSql = DtStringUtil.zip(jobClient.getSql());
        paramsMap.put("sql", zipSql);
        paramsMap.put("appName", jobClient.getJobName());

        String sqlExeJson = null;
        try{
            sqlExeJson = PublicUtil.objToString(paramsMap);
            sqlExeJson = URLEncoder.encode(sqlExeJson, Charsets.UTF_8.name());
        }catch (Exception e){
            logger.error("", e);
            throw new RdosException("get unexpected exception:" + e.getMessage());
        }

        List<String> argList = new ArrayList<>();
        argList.add("--jar");
        argList.add(sparkYarnConfig.getSparkSqlProxyPath());
        argList.add("--class");
        argList.add(sparkYarnConfig.getSparkSqlProxyMainClass());
        argList.add("--arg");
        argList.add(sqlExeJson);


        ClientArguments clientArguments = new ClientArguments(argList.toArray(new String[argList.size()]));
        SparkConf sparkConf = buildBasicSparkConf();
        sparkConf.setAppName(jobClient.getJobName());
        fillExtSparkConf(sparkConf, jobClient.getConfProperties());

        ApplicationId appId = null;

        try {
            ClientExt clientExt = new ClientExt(clientArguments, yarnConf, sparkConf);
            clientExt.setSparkYarnConfig(sparkYarnConfig);
            appId = clientExt.submitApplication(jobClient.getJobPriority());
            return JobResult.createSuccessResult(appId.toString());
        } catch(Exception ex) {
            return JobResult.createErrorResult("submit job get unknown error\n" + ExceptionUtil.getErrorMessage(ex));
        }

    }

    private SparkConf buildBasicSparkConf(){

        SparkConf sparkConf = new SparkConf();
        sparkConf.remove("spark.jars");
        sparkConf.remove("spark.files");
        sparkConf.set("spark.yarn.archive", sparkYarnConfig.getSparkYarnArchive());
        sparkConf.set("spark.yarn.queue", sparkYarnConfig.getQueue());
        SparkConfig.initDefautlConf(sparkConf);
        return sparkConf;
    }

    /**
     * 通过提交的paramsOperator 设置sparkConf
     * 解析传递过来的参数不带spark.前面缀的
     * @param sparkConf
     * @param confProperties
     */
    private void fillExtSparkConf(SparkConf sparkConf, Properties confProperties){

        if(confProperties == null){
            return;
        }

        for(Map.Entry<Object, Object> param : confProperties.entrySet()){
            String key = (String) param.getKey();
            String val = (String) param.getValue();
            if(!key.contains(KEY_PRE_STR)){
                key = KEY_PRE_STR + key;
            }
            sparkConf.set(key, val);
        }
    }


    private JobResult submitSparkSqlJobForStream(JobClient jobClient){
        throw new RdosException("not support spark sql job for stream type.");
    }

    private JobResult submitSqlJob(JobClient jobClient) {

        ComputeType computeType = jobClient.getComputeType();
        if(computeType == null){
            throw new RdosException("need to set compute type.");
        }

        switch (computeType){
            case BATCH:
                return submitSparkSqlJobForBatch(jobClient);
            case STREAM:
                return submitSparkSqlJobForStream(jobClient);
            default:
                //do nothing

        }

        throw new RdosException("not support for compute type :" + computeType);

    }

    @Override
    public JobResult cancelJob(String jobId) {
        try {
            ApplicationId appId = ConverterUtils.toApplicationId(jobId);
            yarnClient.killApplication(appId);
            return JobResult.createSuccessResult(jobId);
        } catch (Exception e) {
            logger.error("", e);
            return JobResult.createErrorResult(e.getMessage());
        }
    }

    @Override
    public RdosTaskStatus getJobStatus(String jobId) throws IOException {
        if(StringUtils.isEmpty(jobId)){
            return null;
        }
        ApplicationId appId = ConverterUtils.toApplicationId(jobId);
        try {
            ApplicationReport report = yarnClient.getApplicationReport(appId);
            YarnApplicationState applicationState = report.getYarnApplicationState();
            switch(applicationState) {
                case KILLED:
                    return RdosTaskStatus.KILLED;
                case NEW:
                case NEW_SAVING:
                    return RdosTaskStatus.CREATED;
                case SUBMITTED:
                    //FIXME 特殊逻辑,认为已提交到计算引擎的状态为等待资源状态
                    return RdosTaskStatus.WAITCOMPUTE;
                case ACCEPTED:
                    return RdosTaskStatus.SCHEDULED;
                case RUNNING:
                    return RdosTaskStatus.RUNNING;
                case FINISHED:
                    //state 为finished状态下需要兼顾判断finalStatus.
                    FinalApplicationStatus finalApplicationStatus = report.getFinalApplicationStatus();
                    if(finalApplicationStatus == FinalApplicationStatus.FAILED){
                        return RdosTaskStatus.FAILED;
                    }else if(finalApplicationStatus == FinalApplicationStatus.SUCCEEDED){
                        return RdosTaskStatus.FINISHED;
                    }else if(finalApplicationStatus == FinalApplicationStatus.KILLED){
                        return RdosTaskStatus.KILLED;
                    }else{
                        return RdosTaskStatus.RUNNING;
                    }

                case FAILED:
                    return RdosTaskStatus.FAILED;
                default:
                    throw new RdosException("Unsupported application state");
            }
        } catch (YarnException e) {
            logger.error("", e);
            return RdosTaskStatus.NOTFOUND;
        }

    }

    @Override
    public String getJobMaster() {
        //解析config,获取web-address
        String aliveWebAddr = null;
        for(String addr : webAppAddrList){
            String response = null;
            String reqUrl = String.format(CLUSTER_INFO_WS_FORMAT, addr);
            try{
                response = PoolHttpClient.get(reqUrl);
                if(response.contains(ALIVE_WEB_FLAG)){
                    aliveWebAddr = addr;
                    break;
                }
            }catch (Exception e){
                continue;
            }
        }

        if(!Strings.isNullOrEmpty(aliveWebAddr)){
            webAppAddrList.remove(aliveWebAddr);
            webAppAddrList.add(0, aliveWebAddr);
        }

        return aliveWebAddr;
    }


    private void parseWebAppAddr() {
        Iterator<Map.Entry<String, String>> iterator = yarnConf.iterator();
        List<String> tmpWebAppAddr = Lists.newArrayList();

        while(iterator.hasNext()) {
            Map.Entry<String,String> entry = iterator.next();
            String key = entry.getKey();
            String value = entry.getValue();

            if(key.contains("yarn.resourcemanager.webapp.address.")){
                if(!value.startsWith(HTTP_PREFIX)){
                    value = HTTP_PREFIX + value.trim();
                }
                tmpWebAppAddr.add(value);
            } else if(key.startsWith("yarn.resourcemanager.hostname.")) {
                String rm = key.substring("yarn.resourcemanager.hostname.".length());
                String addressKey = "yarn.resourcemanager.address." + rm;

                webAppAddrList.add(HTTP_PREFIX + value + ":" + YarnConfiguration.DEFAULT_RM_WEBAPP_PORT);
                if(yarnConf.get(addressKey) == null) {
                    yarnConf.set(addressKey, value + ":" + YarnConfiguration.DEFAULT_RM_PORT);
                }
            }
        }

        if(tmpWebAppAddr.size() != 0){
            webAppAddrList = tmpWebAppAddr;
        }
    }

    @Override
    public String getMessageByHttp(String path) {
        String reqUrl = path;
        if(!path.startsWith(HTTP_PREFIX)){
            reqUrl = String.format("%s%s", getJobMaster(), path);
        }

        try {
            return PoolHttpClient.get(reqUrl);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String getJobLog(String jobId) {
        ApplicationId applicationId = ConverterUtils.toApplicationId(jobId);
        SparkJobLog sparkJobLog = new SparkJobLog();

        try {
            ApplicationReport applicationReport = yarnClient.getApplicationReport(applicationId);
            String msgInfo = applicationReport.getDiagnostics();
            sparkJobLog.addAppLog(jobId, msgInfo);
        } catch (Exception e) {
            logger.error("", e);
            sparkJobLog.addAppLog(jobId, "get log from yarn err:" + e.getMessage());
        }

        return sparkJobLog.toString();
    }

    @Override
    public EngineResourceInfo getAvailSlots() {

        SparkYarnResourceInfo resourceInfo = new SparkYarnResourceInfo();
        try {
            EnumSet<YarnApplicationState> enumSet = EnumSet.noneOf(YarnApplicationState.class);
            enumSet.add(YarnApplicationState.ACCEPTED);
            List<ApplicationReport> acceptedApps = yarnClient.getApplications(enumSet);
            if (acceptedApps.size() > sparkYarnConfig.getYarnAccepterTaskNumber()){
                logger.warn("yarn 资源不足，任务等待提交");
                return resourceInfo;
            }
            List<NodeReport> nodeReports = yarnClient.getNodeReports(NodeState.RUNNING);
            float capacity = 1;
            if (!sparkYarnConfig.getElasticCapacity()){
                capacity = getQueueRemainCapacity(1,yarnClient.getRootQueueInfos());
            }
            resourceInfo.setCapacity(capacity);
            for(NodeReport report : nodeReports){
                Resource capability = report.getCapability();
                Resource used = report.getUsed();
                int totalMem = capability.getMemory();
                int totalCores = capability.getVirtualCores();
                int usedMem = used.getMemory();
                int usedCores = used.getVirtualCores();
                int freeCores = totalCores - usedCores;
                int freeMem = totalMem - usedMem;

                resourceInfo.addNodeResource(new EngineResourceInfo.NodeResourceDetail(report.getNodeId().toString(), totalCores,usedCores,freeCores, totalMem,usedMem,freeMem));
            }
        } catch (Exception e) {
            logger.error("", e);
        }

        return resourceInfo;
    }

    private float getQueueRemainCapacity(float coefficient, List<QueueInfo> queueInfos){
        float capacity = 0;
        for (QueueInfo queueInfo : queueInfos){
            if (CollectionUtils.isNotEmpty(queueInfo.getChildQueues())) {
                float subCoefficient = queueInfo.getCapacity() * coefficient;
                capacity = getQueueRemainCapacity(subCoefficient, queueInfo.getChildQueues());
            }
            if (sparkYarnConfig.getQueue().equals(queueInfo.getQueueName())){
                capacity = coefficient * queueInfo.getCapacity() * (1 - queueInfo.getCurrentCapacity());
            }
            if (capacity>0){
                return capacity;
            }
        }
        return capacity;
    }

    public void setHadoopUserName(SparkYarnConfig sparkYarnConfig){
        if(Strings.isNullOrEmpty(sparkYarnConfig.getHadoopUserName())){
            return;
        }

        UserGroupInformation.setThreadLocalData(HADOOP_USER_NAME, sparkYarnConfig.getHadoopUserName());
    }

    @Override
    public void beforeSubmitFunc(JobClient jobClient) {
        String sql = jobClient.getSql();
        List<String> sqlArr = DtStringUtil.splitIgnoreQuota(sql, ';');
        if(sqlArr.size() == 0){
            return;
        }

        List<String> sqlList = Lists.newArrayList(sqlArr);
        Iterator<String> sqlItera = sqlList.iterator();

        while (sqlItera.hasNext()){
            String tmpSql = sqlItera.next();
            if(AddJarOperator.verific(tmpSql)){
                sqlItera.remove();
                JarFileInfo jarFileInfo = AddJarOperator.parseSql(tmpSql);

                if(jobClient.getJobType() == EJobType.SQL){
                    //SQL当前不允许提交jar包,自定义函数已经在web端处理了。
                }else{
                    //非sql任务只允许提交一个附件包
                    jobClient.setCoreJarInfo(jarFileInfo);
                    break;
                }
            }
        }

        jobClient.setSql(String.join(";", sqlList));
    }
}
