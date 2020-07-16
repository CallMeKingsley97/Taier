package com.dtstack.engine.flink;

import com.dtstack.engine.common.exception.ExceptionUtil;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.common.http.PoolHttpClient;
import com.dtstack.engine.common.util.DtStringUtil;
import com.dtstack.engine.common.util.PublicUtil;
import com.dtstack.engine.common.JarFileInfo;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.JobParam;
import com.dtstack.engine.common.enums.ComputeType;
import com.dtstack.engine.common.enums.EJobType;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.pojo.JobResult;
import com.dtstack.engine.common.util.SFTPHandler;
import com.dtstack.engine.flink.constrant.ConfigConstrant;
import com.dtstack.engine.flink.constrant.ExceptionInfoConstrant;
import com.dtstack.engine.flink.enums.FlinkMode;
import com.dtstack.engine.flink.factory.PerJobClientFactory;
import com.dtstack.engine.flink.parser.AddJarOperator;
import com.dtstack.engine.flink.plugininfo.SqlPluginInfo;
import com.dtstack.engine.flink.plugininfo.SyncPluginInfo;
import com.dtstack.engine.flink.util.FlinkConfUtil;
import com.dtstack.engine.flink.resource.FlinkSeesionResourceInfo;
import com.dtstack.engine.flink.util.FlinkRestParseUtil;
import com.dtstack.engine.flink.util.FlinkUtil;
import com.dtstack.engine.flink.util.HadoopConf;
import com.dtstack.engine.common.client.AbstractClient;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.Charsets;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.cache.DistributedCache;
import org.apache.flink.client.ClientUtils;
import org.apache.flink.client.deployment.ClusterDeploymentException;
import org.apache.flink.client.deployment.ClusterDescriptor;
import org.apache.flink.client.deployment.ClusterSpecification;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.client.program.PackagedProgramUtils;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HistoryServerOptions;
import org.apache.flink.kubernetes.kubeclient.FlinkKubeClient;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.FunctionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.action.GetPropertyAction;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.security.AccessController.doPrivileged;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2020/04/03
 */
public class FlinkClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(FlinkClient.class);

    //FIXME key值需要根据客户端传输名称调整
    private static final String FLINK_JOB_ALLOWNONRESTOREDSTATE_KEY = "allowNonRestoredState";

    public final static String FLINK_CP_URL_FORMAT = "/jobs/%s/checkpoints";

    private String tmpFileDirPath = "./tmp";

    private static final Path tmpdir = Paths.get(doPrivileged(new GetPropertyAction("java.io.tmpdir")));

    private FlinkConfig flinkConfig;

    private org.apache.hadoop.conf.Configuration hadoopConf;

    private FlinkClientBuilder flinkClientBuilder;

    private SyncPluginInfo syncPluginInfo;

    private SqlPluginInfo sqlPluginInfo;

    private Map<String, List<String>> cacheFile = Maps.newConcurrentMap();

    private KubernetesClient kubernetesClient;

    private FlinkClusterClientManager flinkClusterClientManager;

    private String jobHistory;

    @Override
    public void init(Properties prop) throws Exception {

        String propStr = PublicUtil.objToString(prop);
        flinkConfig = PublicUtil.jsonStrToObject(propStr, FlinkConfig.class);

        tmpFileDirPath = flinkConfig.getJarTmpDir();
        Preconditions.checkNotNull(tmpFileDirPath, "you need to set tmp file path for jar download.");

        syncPluginInfo = SyncPluginInfo.create(flinkConfig);
        sqlPluginInfo = SqlPluginInfo.create(flinkConfig);

        initHadoopConf(flinkConfig);

        FlinkUtil.downloadK8sConfig(prop, flinkConfig);

        flinkClientBuilder = new FlinkClientBuilder(flinkConfig, hadoopConf, prop);
        kubernetesClient = flinkClientBuilder.getKubernetesClient();

        flinkClusterClientManager = FlinkClusterClientManager.createWithInit(flinkClientBuilder);
    }

    private void initHadoopConf(FlinkConfig flinkConfig) {
        HadoopConf customerConf = new HadoopConf();
        customerConf.initHadoopConf(flinkConfig.getHadoopConf());

        hadoopConf = customerConf.getConfiguration();
    }

    @Override
    protected JobResult processSubmitJobWithType(JobClient jobClient) {
        EJobType jobType = jobClient.getJobType();
        JobResult jobResult = null;
        if (EJobType.MR.equals(jobType)) {
            jobResult = submitJobWithJar(jobClient);
        } else if (EJobType.SQL.equals(jobType)) {
            jobResult = submitSqlJob(jobClient);
        } else if (EJobType.SYNC.equals(jobType)) {
            jobResult = submitSyncJob(jobClient);
        }
        return jobResult;
    }

    private JobResult submitJobWithJar(JobClient jobClient) {
        List<URL> classPaths = Lists.newArrayList();
        List<String> programArgList = Lists.newArrayList();
        return submitJobWithJar(jobClient, classPaths, programArgList);
    }

    private JobResult submitJobWithJar(JobClient jobClient, List<URL> classPaths, List<String> programArgList) {
        if (flinkConfig.isOpenKerberos()) {
            downloadKafkaKeyTab(jobClient.getTaskParams(), flinkConfig);
        }

        if (StringUtils.isNotBlank(jobClient.getEngineTaskId())) {
            if (existsJobOnFlink(jobClient.getEngineTaskId())) {
                return JobResult.createSuccessResult(jobClient.getEngineTaskId());
            }
        }

        JobParam jobParam = new JobParam(jobClient);
        String jarPath = jobParam.getJarPath();
        if (jarPath == null) {
            logger.error("can not submit a job without jar path, please check it");
            return JobResult.createErrorResult("can not submit a job without jar path, please check it");
        }

        FlinkMode taskRunMode = FlinkUtil.getTaskRunMode(jobClient.getConfProperties(), jobClient.getComputeType());
        Configuration tmpConfiguration = new Configuration(flinkClientBuilder.getFlinkConfiguration());
        ClusterClient clusterClient = null;
        String monitorUrl = "";
        logger.info("clusterClient monitorUrl is {},run mode is {}", monitorUrl, taskRunMode.name());
        try {
            if (FlinkMode.isPerJob(taskRunMode)) {
                ClusterSpecification clusterSpecification = FlinkConfUtil.createClusterSpecification(tmpConfiguration, jobClient.getJobPriority(), jobClient.getConfProperties());
                clusterClient = createClusterClientForPerJob(clusterSpecification, jobClient);
            } else {
                clusterClient = flinkClusterClientManager.getClusterClient(null);
            }

            Preconditions.checkNotNull(clusterClient, "clusterClient is null");
            monitorUrl = clusterClient.getWebInterfaceURL();
            logger.info("clusterClient monitorUrl is {},run mode is {}", monitorUrl, taskRunMode.name());
        } catch (Exception e) {
            logger.error("create clusterClient or getSession clusterClient error", e);
            throw new RdosDefineException(e);
        }

        PackagedProgram packagedProgram = null;
        JobGraph jobGraph = null;
        SavepointRestoreSettings spSettings = buildSavepointSetting(jobClient);
        String entryPointClass = jobParam.getMainClass();
        EJobType jobType = jobClient.getJobType();
        String[] programArgs = dealProgramArgs(programArgList, jobParam, monitorUrl, jobType);

        try {
            Integer runParallelism = FlinkUtil.getJobParallelism(jobClient.getConfProperties());
            packagedProgram = FlinkUtil.buildProgram(jarPath, tmpFileDirPath, classPaths, jobClient.getJobType(), entryPointClass, programArgs, spSettings, hadoopConf, tmpConfiguration);
            jobGraph = PackagedProgramUtils.createJobGraph(packagedProgram, tmpConfiguration, runParallelism, false);

            fillJobGraphClassPath(jobGraph);

            Pair<String, String> runResult = submitFlinkJob(clusterClient, jobGraph, taskRunMode);
            return JobResult.createSuccessResult(runResult.getFirst(), runResult.getSecond());
        } catch (Exception e) {
            if (FlinkMode.isPerJob(taskRunMode)) {
                clusterClient.shutDownCluster();
            }
            return JobResult.createErrorResult(e);
        } finally {
            if (packagedProgram != null) {
                packagedProgram.deleteExtractedLibraries();
            }
        }
    }

    /**
     *
     *  sync job rely on monitorUrl parameters to transmit metric information
     * @param programArgList
     * @param jobParam
     * @param monitorUrl
     * @return
     */
    private String[] dealProgramArgs(List<String> programArgList, JobParam jobParam, String monitorUrl, EJobType jobType) {
        String args = jobParam.getClassArgs();
        if (StringUtils.isNotBlank(args)) {
            programArgList.addAll(Arrays.asList(args.split("\\s+")));
        }
        String[] programArgs = programArgList.toArray(new String[programArgList.size()]);

        if (EJobType.SYNC.equals(jobType)) {
            for (int i = 0; i < args.length(); i++) {
                if ("-monitor".equals(programArgs[i])) {
                    programArgs[i + 1] = monitorUrl;
                    break;
                }
            }
        }
        return programArgs;
    }


    private ClusterClient createClusterClientForPerJob(ClusterSpecification clusterSpecification, JobClient jobClient) throws ClusterDeploymentException {
        ClusterDescriptor<String> clusterDescriptor = null;
        ClusterClient<String> clusterClient = null;
        try {
            clusterDescriptor = PerJobClientFactory.getPerJobClientFactory().createPerjobClusterDescriptor(jobClient);
            clusterClient = clusterDescriptor.deploySessionCluster(clusterSpecification).getClusterClient();

            flinkClusterClientManager.addClient(clusterClient.getClusterId(), clusterClient);
            return clusterClient;
        } catch (Exception e) {
            try {
                if (clusterClient != null) {
                    clusterClient.shutDownCluster();
                }
                if (clusterDescriptor != null) {
                    clusterDescriptor.close();
                }
            } catch (Exception e1) {
                logger.info("Could not properly close the kubernetes cluster descriptor.", e1);
            }
            throw new RdosDefineException(e);
        }
    }


    private Pair<String, String> submitFlinkJob(ClusterClient clusterClient, JobGraph jobGraph, FlinkMode taskRunMode) throws Exception {
        try {
            JobExecutionResult jobExecutionResult = ClientUtils.submitJob(clusterClient, jobGraph, flinkConfig.getSubmitTimeout(), TimeUnit.MINUTES);
            logger.info("Program execution finished");
            logger.info("Job with JobID " + jobExecutionResult.getJobID() + " has finished.");
            return Pair.create(jobExecutionResult.getJobID().toString(), clusterClient.getClusterId().toString());
        } catch (Exception e) {
            if (!FlinkMode.isPerJob(taskRunMode) && flinkClusterClientManager.getIsClientOn()) {
                logger.info("submit job error,flink session init ..");
                flinkClusterClientManager.setIsClientOn(false);
                flinkClusterClientManager.initClusterClient();
            }
            throw e;
        } finally {
            delFilesFromDir(tmpdir, "flink-jobgraph");
        }
    }

    private void delFilesFromDir(Path dir, String fileName) {
        File[] jobGraphFile = dir.toFile().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(fileName);
            }
        });

        if (jobGraphFile.length != 0) {
            for (int i = 0; i < jobGraphFile.length; i++) {
                jobGraphFile[i].delete();
            }
        }
    }

    private SavepointRestoreSettings buildSavepointSetting(JobClient jobClient) {

        if (jobClient.getExternalPath() == null) {
            return SavepointRestoreSettings.none();
        }

        String externalPath = jobClient.getExternalPath();
        boolean allowNonRestoredState = false;
        if (jobClient.getConfProperties().containsKey(FLINK_JOB_ALLOWNONRESTOREDSTATE_KEY)) {
            String allowNonRestored = (String) jobClient.getConfProperties().get(FLINK_JOB_ALLOWNONRESTOREDSTATE_KEY);
            allowNonRestoredState = BooleanUtils.toBoolean(allowNonRestored);
        }

        return SavepointRestoreSettings.forPath(externalPath, allowNonRestoredState);
    }

    private JobResult submitSqlJob(JobClient jobClient) {

        if (StringUtils.isNotBlank(jobClient.getEngineTaskId())) {
            if (existsJobOnFlink(jobClient.getEngineTaskId())) {
                return JobResult.createSuccessResult(jobClient.getEngineTaskId());
            }
        }

        ComputeType computeType = jobClient.getComputeType();
        if (computeType == null) {
            throw new RdosDefineException("need to set compute type.");
        }

        switch (computeType) {
            case BATCH:
                return submitSqlJobForBatch(jobClient);
            case STREAM:
                return submitSqlJobForStream(jobClient);

        }

        throw new RdosDefineException("not support for compute type :" + computeType);
    }

    /**
     * 1: 不再对操作顺序做限制
     * 2：不再限制输入源数量
     * 3：不再限制输出源数量
     *
     * @param jobClient
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     */
    private JobResult submitSqlJobForStream(JobClient jobClient) {

        try {
            //构建args
            List<String> args = sqlPluginInfo.buildExeArgs(jobClient);
            List<String> attachJarLists = cacheFile.get(jobClient.getTaskId());

            if (!CollectionUtils.isEmpty(attachJarLists)) {
                args.add("-addjar");
                String attachJarStr = PublicUtil.objToString(attachJarLists);
                args.add(URLEncoder.encode(attachJarStr, Charsets.UTF_8.name()));
            }

            JarFileInfo coreJarInfo = sqlPluginInfo.createCoreJarInfo();
            jobClient.setCoreJarInfo(coreJarInfo);

            return submitJobWithJar(jobClient, Lists.newArrayList(), args);
        } catch (Exception e) {
            return JobResult.createErrorResult(e);
        }
    }

    private JobResult submitSqlJobForBatch(JobClient jobClient) {
        throw new RdosDefineException("not support for flink batch sql now!!!");
    }

    @Override
    public JobResult cancelJob(JobIdentifier jobIdentifier) {
        String applicationId = jobIdentifier.getApplicationId();
        logger.info("cancel job applicationId is: {}", applicationId);

        ClusterClient targetClusterClient = flinkClusterClientManager.getClusterClient(jobIdentifier);
        try {
            RdosTaskStatus rdosTaskStatus = getJobStatus(jobIdentifier);
            if (!RdosTaskStatus.getStoppedStatus().contains(rdosTaskStatus.getStatus())) {
                JobID jobID = new JobID(org.apache.flink.util.StringUtils.hexStringToByte(jobIdentifier.getEngineJobId()));

                CompletableFuture cancel = targetClusterClient.cancel(jobID);;
                Object ack = cancel.get(2, TimeUnit.MINUTES);

                if (ack instanceof Acknowledge) {
                    logger.info("cancel job success, applicationId is {}", applicationId);
                }

            }
        } catch (Exception e) {
            // session mode
            if (targetClusterClient == flinkClusterClientManager.getClusterClient()) {
                logger.error("", e);
                return JobResult.createErrorResult(e);
            }

            try {
                targetClusterClient.shutDownCluster();
            } catch (Exception ec) {
                logger.error("shutDownCluster error", ec);
                return JobResult.createErrorResult(e);
            }
        }

        JobResult jobResult = JobResult.newInstance(false);
        jobResult.setData(JobResult.JOB_ID_KEY, jobIdentifier.getEngineJobId());
        return jobResult;
    }

    /**
     * 直接调用rest api直接返回
     *
     * @param jobIdentifier
     * @return
     */
    @Override
    public RdosTaskStatus getJobStatus(JobIdentifier jobIdentifier) {
        String jobId = jobIdentifier.getEngineJobId();
        String applicationId = jobIdentifier.getApplicationId();

        if (StringUtils.isBlank(jobId)) {
            logger.warn("jobIdentifier:{} is blank.", jobIdentifier);
            return RdosTaskStatus.NOTFOUND;
        }

        try {
            FlinkKubeClient flinkKubeClient = flinkClientBuilder.getFlinkKubeClient();
            if (flinkKubeClient.getInternalService(applicationId) == null) {
                return RdosTaskStatus.NOTFOUND;
            }

            ClusterClient clusterClient = flinkClusterClientManager.getClusterClient(jobIdentifier);
            String reqUrlPrefix = clusterClient.getWebInterfaceURL();
            String reqUrl = reqUrlPrefix + "/jobs/" + jobId;
            String response = PoolHttpClient.get(reqUrl);
            if (response != null) {
                Map<String, Object> statusMap = PublicUtil.jsonStrToObject(response, Map.class);
                Object stateObj = statusMap.get("state");
                if (stateObj != null) {
                    String state = (String) stateObj;
                    state = StringUtils.upperCase(state);
                    RdosTaskStatus rdosTaskStatus =  RdosTaskStatus.getTaskStatus(state);
                    Boolean isFlinkSessionTask = applicationId.startsWith(FlinkConfig.FLINK_SESSION_PREFIX);
                    if (RdosTaskStatus.isStopped(rdosTaskStatus.getStatus()) && !isFlinkSessionTask) {
                        clusterClient.shutDownCluster();
                    }
                    return rdosTaskStatus;

                }
            }
        } catch (Exception e) {
            logger.error("", e);
        }

        return RdosTaskStatus.NOTFOUND;
    }

    public String getReqUrl(FlinkMode flinkMode) {
        if (FlinkMode.PER_JOB == flinkMode) {
            return "${monitor}";
        } else {
            return flinkClusterClientManager.getClusterClient(null).getWebInterfaceURL();
        }
    }

    @Override
    public String getJobMaster(JobIdentifier jobIdentifier) {
        return flinkClusterClientManager.getClusterClient(jobIdentifier).getWebInterfaceURL();
    }

    private JobResult submitSyncJob(JobClient jobClient) {
        //使用flink作为数据同步调用的其实是提交mr--job
        JarFileInfo coreJar = syncPluginInfo.createAddJarInfo();
        jobClient.setCoreJarInfo(coreJar);

        List<String> programArgList = syncPluginInfo.createSyncPluginArgs(jobClient, this);
        List<URL> classPaths = syncPluginInfo.getClassPaths(programArgList);

        return submitJobWithJar(jobClient, classPaths, programArgList);
    }

    private String getMessageByHttp(String path, String reqURL) throws IOException {
        String reqUrl = String.format("%s%s", reqURL, path);
        return PoolHttpClient.get(reqUrl);
    }

    @Override
    public String getJobLog(JobIdentifier jobIdentifier) {

        String jobId = jobIdentifier.getEngineJobId();
        String applicationId = jobIdentifier.getApplicationId();

        RdosTaskStatus rdosTaskStatus = getJobStatus(jobIdentifier);
        String reqURL;

        //从jobhistory读取
        if (StringUtils.isNotBlank(applicationId) && (rdosTaskStatus.equals(RdosTaskStatus.FINISHED) || rdosTaskStatus.equals(RdosTaskStatus.CANCELED)
                || rdosTaskStatus.equals(RdosTaskStatus.FAILED) || rdosTaskStatus.equals(RdosTaskStatus.KILLED))) {
            reqURL = getJobHistoryURL();
        } else {
            ClusterClient currClient = flinkClusterClientManager.getClusterClient(jobIdentifier);
            reqURL = currClient.getWebInterfaceURL();
        }

        Map<String, String> retMap = Maps.newHashMap();

        try {
            String exceptPath = String.format(FlinkRestParseUtil.EXCEPTION_INFO, jobId);
            String except = getExceptionInfo(exceptPath, reqURL);
            String accuPath = String.format(FlinkRestParseUtil.JOB_ACCUMULATOR_INFO, jobId);
            String accuInfo = getMessageByHttp(accuPath, reqURL);
            retMap.put("exception", except);
            retMap.put("accuInfo", accuInfo);
            return FlinkRestParseUtil.parseEngineLog(retMap);
        } catch (RdosDefineException e) {
            //http 请求失败时返回空日志
            logger.error("", e);
            return null;
        } catch (Exception e) {
            logger.error("", e);
            Map<String, String> map = new LinkedHashMap<>(8);
            map.put("jobId", jobId);
            map.put("exception", ExceptionInfoConstrant.FLINK_GET_LOG_ERROR_UNDO_RESTART_EXCEPTION);
            map.put("reqURL", reqURL);
            map.put("engineLogErr", ExceptionUtil.getErrorMessage(e));
            return new Gson().toJson(map);
        }
    }

    /**
     * perjob模式下任务完成后进入jobHistory会有一定的时间
     */
    private String getExceptionInfo(String exceptPath, String reqURL) {
        String exceptionInfo = "";
        try {
            exceptionInfo = getMessageByHttp(exceptPath, reqURL);
            return exceptionInfo;
        } catch (Exception e) {
            logger.error("", e);
        }

        return exceptionInfo;
    }

    @Override
    public boolean judgeSlots(JobClient jobClient) {

        try {
            FlinkSeesionResourceInfo seesionResourceInfo = new FlinkSeesionResourceInfo();
            seesionResourceInfo.getResource(kubernetesClient, null, 0);
            return seesionResourceInfo.judgeSlots(jobClient);
        } catch (Exception e) {
            logger.error("judgeSlots error:{}", e);
            return false;
        }
    }

    @Override
    public void beforeSubmitFunc(JobClient jobClient) {
        String sql = jobClient.getSql();
        List<String> sqlArr = DtStringUtil.splitIgnoreQuota(sql, ';');
        if (sqlArr.size() == 0) {
            return;
        }

        List<String> sqlList = Lists.newArrayList(sqlArr);
        Iterator<String> sqlItera = sqlList.iterator();
        List<String> fileList = Lists.newArrayList();

        while (sqlItera.hasNext()) {
            String tmpSql = sqlItera.next();
            if (AddJarOperator.verific(tmpSql)) {
                sqlItera.remove();
                JarFileInfo jarFileInfo = AddJarOperator.parseSql(tmpSql);
                String addFilePath = jarFileInfo.getJarPath();
                File tmpFile = null;
                try {
                    tmpFile = FlinkUtil.downloadJar(addFilePath, tmpFileDirPath, hadoopConf, flinkConfig.getSftpConf());
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
                if (tmpFile == null) {
                    throw new RuntimeException("JAR file does not exist: " + addFilePath);
                }

                fileList.add(tmpFile.getAbsolutePath());

                //更改路径为本地路径
                jarFileInfo.setJarPath(tmpFile.getAbsolutePath());

                if (jobClient.getJobType() == EJobType.SQL) {
                    jobClient.addAttachJarInfo(jarFileInfo);
                } else {
                    //非sql任务只允许提交一个附件包
                    jobClient.setCoreJarInfo(jarFileInfo);
                    break;
                }
            }
        }

        cacheFile.put(jobClient.getTaskId(), fileList);
        jobClient.setSql(String.join(";", sqlList));
        try {
            FlinkConfig flinkConfig = PublicUtil.jsonStrToObject(jobClient.getPluginInfo(), FlinkConfig.class);
            Properties prop = PublicUtil.stringToProperties(jobClient.getPluginInfo());
            FlinkUtil.downloadK8sConfig(prop, flinkConfig);
        } catch (IOException e) {
            throw new RuntimeException("k8s config file download fail");
        }
    }

    @Override
    public void afterSubmitFunc(JobClient jobClient) {
        List<String> fileList = cacheFile.get(jobClient.getTaskId());
        if (CollectionUtils.isEmpty(fileList)) {
            return;
        }

        //清理包含下载下来的临时jar文件
        for (String path : fileList) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    file.delete();
                }

            } catch (Exception e1) {
                logger.error("", e1);
            }
        }

        cacheFile.remove(jobClient.getTaskId());

        FlinkUtil.deleteK8sConfig(jobClient);
    }

    @Override
    public String getCheckpoints(JobIdentifier jobIdentifier) {
        String appId = jobIdentifier.getApplicationId();
        String jobId = jobIdentifier.getEngineJobId();

        RdosTaskStatus rdosTaskStatus = getJobStatus(jobIdentifier);

        String reqURL;
        if (rdosTaskStatus.equals(RdosTaskStatus.FINISHED) || rdosTaskStatus.equals(RdosTaskStatus.CANCELED)
                || rdosTaskStatus.equals(RdosTaskStatus.FAILED) || rdosTaskStatus.equals(RdosTaskStatus.KILLED)) {
            reqURL = getJobHistoryURL();
        } else {
            ClusterClient currClient = flinkClusterClientManager.getClusterClient(jobIdentifier);
            reqURL = currClient.getWebInterfaceURL();
        }

        try {
            return getMessageByHttp(String.format(FLINK_CP_URL_FORMAT, jobId), reqURL);
        } catch (IOException e) {
            logger.error("", e);
            return null;
        }
    }

    private boolean existsJobOnFlink(String engineJobId) {
        RdosTaskStatus taskStatus = getJobStatus(JobIdentifier.createInstance(engineJobId, null, null));
        if (taskStatus == null) {
            return false;
        }

        if (taskStatus == RdosTaskStatus.RUNNING) {
            return true;
        }

        return false;
    }

    public void fillJobGraphClassPath(JobGraph jobGraph) {
        Map<String, DistributedCache.DistributedCacheEntry> jobCacheFileConfig = jobGraph.getUserArtifacts();
        List<URL> classPath = jobCacheFileConfig.entrySet().stream()
                .filter(tmp -> tmp.getKey().startsWith("class_path"))
                .map(FunctionUtils.uncheckedFunction(tmp -> new URL("file:" + tmp.getValue().filePath)))
                .collect(Collectors.toList());

        jobGraph.getUserArtifacts().clear();
        jobGraph.setClasspaths(classPath);
    }

    private String getJobHistoryURL() {
        if (StringUtils.isNotBlank(jobHistory)) {
            return jobHistory;
        }
        String webAddress = flinkClientBuilder.getFlinkConfiguration().getValue(HistoryServerOptions.HISTORY_SERVER_WEB_ADDRESS);
        String port = flinkClientBuilder.getFlinkConfiguration().getValue(HistoryServerOptions.HISTORY_SERVER_WEB_PORT);
        if (StringUtils.isBlank(webAddress) || StringUtils.isBlank(port)) {
            throw new RdosDefineException("History Server webAddress:" + webAddress + " port:" + port);
        }
        jobHistory = String.format("http://%s:%s", webAddress, port);
        return jobHistory;
    }

    private void downloadKafkaKeyTab(String taskParams, FlinkConfig flinkConfig) {
        try {
            Properties confProperties = new Properties();
            List<String> taskParam = DtStringUtil.splitIngoreBlank(taskParams.trim());
            for (int i = 0; i < taskParam.size(); ++i) {
                String[] pair = taskParam.get(i).split("=", 2);
                confProperties.setProperty(pair[0], pair[1]);
            }
            String sftpKeytab = confProperties.getProperty(ConfigConstrant.KAFKA_SFTP_KEYTAB);

            if (StringUtils.isBlank(sftpKeytab)) {
                logger.info("flink task submission has enabled keberos authentication, but kafka has not !!!");
                return;
            }

            String localKeytab = confProperties.getProperty(ConfigConstrant.SECURITY_KERBEROS_LOGIN_KEYTAB);
            if (StringUtils.isNotBlank(localKeytab) && !(new File(localKeytab).exists())) {
                SFTPHandler handler = SFTPHandler.getInstance(flinkConfig.getSftpConf());
                handler.downloadFile(sftpKeytab, localKeytab);
            }
        } catch (Exception e) {
            logger.error("Download keytab from sftp failed", e);
        }
    }


}
