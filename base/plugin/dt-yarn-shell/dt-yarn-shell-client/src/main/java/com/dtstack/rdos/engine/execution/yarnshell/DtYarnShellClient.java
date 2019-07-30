package com.dtstack.rdos.engine.execution.yarnshell;

import com.dtstack.rdos.commom.exception.ExceptionUtil;
import com.dtstack.rdos.commom.exception.RdosException;
import com.dtstack.rdos.common.util.MathUtil;
import com.dtstack.rdos.engine.execution.base.AbsClient;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.dtstack.rdos.engine.execution.base.JobIdentifier;
import com.dtstack.rdos.engine.execution.base.enums.EJobType;
import com.dtstack.rdos.engine.execution.base.enums.RdosTaskStatus;
import com.dtstack.rdos.engine.execution.base.pojo.EngineResourceInfo;
import com.dtstack.rdos.engine.execution.base.pojo.JobResult;
import com.dtstack.yarn.DtYarnConfiguration;
import com.dtstack.yarn.client.Client;
import com.google.gson.Gson;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * dt-yarn-shell客户端
 * Date: 2018/9/14
 * Company: www.dtstack.com
 *
 * @author jingzhen
 */
public class DtYarnShellClient extends AbsClient {

    private static final Logger LOG = LoggerFactory.getLogger(DtYarnShellClient.class);

    private static final String YARN_RM_WEB_KEY_PREFIX = "yarn.resourcemanager.webapp.address.";

    private static final String APP_URL_FORMAT = "http://%s";

    private static final Gson gson = new Gson();

    private Client client;

    private DtYarnConfiguration conf = new DtYarnConfiguration();

    @Override
    public void init(Properties prop) throws Exception {

        LOG.info("DtYarnShellClient init ...");

        conf.set("fs.hdfs.impl.disable.cache", "true");
        conf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
        Boolean useLocalEnv = MathUtil.getBoolean(prop.get("use.local.env"), false);

        if(useLocalEnv){
            //从本地环境变量读取
            String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");
            conf.addResource(new URL("file://" + hadoopConfDir + "/" + "core-site.xml"));
            conf.addResource(new URL("file://" + hadoopConfDir + "/" + "hdfs-site.xml"));
            conf.addResource(new URL("file://" + hadoopConfDir + "/" + "yarn-site.xml"));
        }

        Enumeration enumeration =  prop.propertyNames();
        while(enumeration.hasMoreElements()) {
            String key = (String) enumeration.nextElement();
            Object value = prop.get(key);
            if(value instanceof String) {
                conf.set(key, (String)value);
            } else if(value instanceof  Integer) {
                conf.setInt(key, (Integer)value);
            } else if(value instanceof  Float) {
                conf.setFloat(key, (Float)value);
            } else if(value instanceof Double) {
                conf.setDouble(key, (Double)value);
            } else if(value instanceof Map) {
                Map<String,Object> map = (Map<String, Object>) value;
                for(Map.Entry<String,Object> entry : map.entrySet()) {
                    conf.set(entry.getKey(), MapUtils.getString(map,entry.getKey()));
                }
            } else {
                conf.set(key, value.toString());
            }
        }
        String queue = prop.getProperty(DtYarnConfiguration.DT_APP_QUEUE);
        if (StringUtils.isNotBlank(queue)){
            LOG.warn("curr queue is {}", queue);
            conf.set(DtYarnConfiguration.DT_APP_QUEUE, queue);
        }

        client = new Client(conf);
    }

    @Override
    protected JobResult processSubmitJobWithType(JobClient jobClient) {
        EJobType jobType = jobClient.getJobType();
        JobResult jobResult = null;
        if(EJobType.PYTHON.equals(jobType)){
            jobResult = submitPythonJob(jobClient);
        }
        return jobResult;
    }

    @Override
    public JobResult cancelJob(JobIdentifier jobIdentifier) {
        String jobId = jobIdentifier.getEngineJobId();
        try {
            client.kill(jobId);
            return JobResult.createSuccessResult(jobId);
        } catch (Exception e) {
            LOG.error("", e);
            return JobResult.createErrorResult(e.getMessage());
        }
    }

    @Override
    public RdosTaskStatus getJobStatus(JobIdentifier jobIdentifier) throws IOException {
        String jobId = jobIdentifier.getEngineJobId();

        if(org.apache.commons.lang3.StringUtils.isEmpty(jobId)){
            return null;
        }

        try {
            ApplicationReport report = client.getApplicationReport(jobId);
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
            LOG.error("", e);
            return RdosTaskStatus.NOTFOUND;
        }
    }

    @Override
    public String getJobMaster() {
        YarnClient  yarnClient = client.getYarnClient();
        String url = "";
        try{
            //调用一次远程,防止rm切换本地没有及时切换
            yarnClient.getNodeReports();
            Field rmClientField = yarnClient.getClass().getDeclaredField("rmClient");
            rmClientField.setAccessible(true);
            Object rmClient = rmClientField.get(yarnClient);

            Field hField = rmClient.getClass().getSuperclass().getDeclaredField("h");
            hField.setAccessible(true);
            //获取指定对象中此字段的值
            Object h = hField.get(rmClient);

            Field currentProxyField = h.getClass().getDeclaredField("currentProxy");
            currentProxyField.setAccessible(true);
            Object currentProxy = currentProxyField.get(h);

            Field proxyInfoField = currentProxy.getClass().getDeclaredField("proxyInfo");
            proxyInfoField.setAccessible(true);
            String proxyInfoKey = (String) proxyInfoField.get(currentProxy);

            String key = YARN_RM_WEB_KEY_PREFIX + proxyInfoKey;
            String addr = conf.get(key);

            if(addr == null) {
                addr = conf.get("yarn.resourcemanager.webapp.address");
            }

            url = String.format(APP_URL_FORMAT, addr);
        }catch (Exception e){
            LOG.error("Getting URL failed" + e);
        }

        LOG.info("get req url=" + url);
        return url;
    }

    @Override
    public String getMessageByHttp(String path) {
        return null;
    }

    private JobResult submitPythonJob(JobClient jobClient){
        try {
            String[] args = DtYarnShellUtil.buildPythonArgs(jobClient);
            System.out.println(Arrays.asList(args));
            String jobId = client.submit(args);
            return JobResult.createSuccessResult(jobId);
        } catch(Exception ex) {
            LOG.info("", ex);
            return JobResult.createErrorResult("submit job get unknown error\n" + ExceptionUtil.getErrorMessage(ex));
        }
    }

    @Override
    public EngineResourceInfo getAvailSlots() {
        DtYarnShellResourceInfo resourceInfo = new DtYarnShellResourceInfo();
        try {
            EnumSet<YarnApplicationState> enumSet = EnumSet.noneOf(YarnApplicationState.class);
            enumSet.add(YarnApplicationState.ACCEPTED);
            List<ApplicationReport> acceptedApps = client.getYarnClient().getApplications(enumSet).stream().
                    filter(report->report.getQueue().endsWith(conf.get(DtYarnConfiguration.DT_APP_QUEUE))).collect(Collectors.toList());
            if (acceptedApps.size() > conf.getInt(DtYarnConfiguration.DT_APP_YARN_ACCEPTER_TASK_NUMBER,1)){
                LOG.warn("curr conf is :{}", conf);
                LOG.warn("yarn curr queue has accept app, num is {} max then {}, waiting to submit.", acceptedApps.size(), conf.getInt(DtYarnConfiguration.DT_APP_YARN_ACCEPTER_TASK_NUMBER,1));
                return resourceInfo;
            }
            List<NodeReport> nodeReports = client.getNodeReports();
            float capacity = 1;
            if (!conf.getBoolean(DtYarnConfiguration.DT_APP_ELASTIC_CAPACITY, true)){
                capacity = getQueueRemainCapacity(1,client.getYarnClient().getRootQueueInfos());
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
            LOG.error("", e);
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
            if (queueInfo.getQueueName().equals(conf.get(DtYarnConfiguration.DT_APP_QUEUE))){
                capacity = coefficient * queueInfo.getCapacity() * (1 - queueInfo.getCurrentCapacity());
            }
            if (capacity>0){
                return capacity;
            }
        }
        return capacity;
    }

    @Override
    public String getJobLog(JobIdentifier jobIdentifier) {
        String jobId = jobIdentifier.getEngineJobId();
        Map<String,Object> jobLog = new HashMap<>();
        try {
            ApplicationReport applicationReport = client.getApplicationReport(jobId);
            jobLog.put("msg_info", applicationReport.getDiagnostics());
        } catch (Exception e) {
            LOG.error("", e);
            jobLog.put("msg_info", e.getMessage());
        }
        return gson.toJson(jobLog, Map.class);
    }

    @Override
    public List<String> getContainerInfos(JobIdentifier jobIdentifier) {

        String jobId = jobIdentifier.getEngineJobId();
        try {
            return client.getContainerInfos(jobId);
        } catch (Exception e) {
            LOG.error("", e);
            return null;
        }
    }

}
