package com.dtstack.engine.dummy;

import com.alibaba.fastjson.JSONObject;
import com.dtstack.engine.base.config.YamlConfigParser;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.client.AbstractClient;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.exception.ExceptionUtil;
import com.dtstack.engine.common.pojo.ClientTemplate;
import com.dtstack.engine.common.pojo.ComponentTestResult;
import com.dtstack.engine.common.pojo.JobResult;
import com.dtstack.engine.common.sftp.SftpFactory;
import com.dtstack.engine.common.util.PublicUtil;
import com.jcraft.jsch.ChannelSftp;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 用于流程上压测的dummy插件
 * <p>
 * company: www.dtstack.com
 * author: toutian
 * create: 2020/4/13
 */
public class DummyClient extends AbstractClient {

    private Map<String, List<ClientTemplate>> defaultConfigs = new HashMap();

    private static final Logger logger = LoggerFactory.getLogger(DummyClient.class);

    private static Map<String,String> commonConfigFiles = new HashMap<>();

    static {
        commonConfigFiles.put("sftp","sftp-config.yml");
    }
    @Override
    public void init(Properties prop) throws Exception {
    }

    @Override
    public List<ClientTemplate> getDefaultPluginConfig(String componentType) {
        return defaultConfigs.get(componentType);
    }

    public DummyClient() {
        for (String componentType : commonConfigFiles.keySet()) {
            try {
                InputStream resourceAsStream = this.getClass().getClassLoader().getResourceAsStream(commonConfigFiles.get(componentType));
                Map<String, Object> config = YamlConfigParser.INSTANCE.parse(resourceAsStream);
                defaultPlugins = super.convertMapTemplateToConfig(config);
                logger.info("=======DummyClient============{}", defaultPlugins);
                defaultConfigs.put(componentType,defaultPlugins);
            } catch (Exception e) {
                logger.error("dummy client init default config error ", e);
            }
        }

    }

    @Override
    public String getJobLog(JobIdentifier jobId) {
        Map<String, Object> jobLog = new HashMap<>(2);
        jobLog.put("jobId", jobId.getTaskId());
        jobLog.put("msg_info", System.currentTimeMillis());
        return JSONObject.toJSONString(jobLog);
    }

    @Override
    public boolean judgeSlots(JobClient jobClient) {
        return true;
    }

    @Override
    public JobResult cancelJob(JobIdentifier jobIdentifier) {
        return JobResult.createSuccessResult(jobIdentifier.getTaskId(), jobIdentifier.getEngineJobId());
    }

    @Override
    public RdosTaskStatus getJobStatus(JobIdentifier jobIdentifier) throws IOException {
        return RdosTaskStatus.FINISHED;
    }

    @Override
    public String getJobMaster(JobIdentifier jobIdentifier) {
        return StringUtils.EMPTY;
    }

    @Override
    public String getMessageByHttp(String path) {
        return StringUtils.EMPTY;
    }

    @Override
    protected JobResult processSubmitJobWithType(JobClient jobClient) {
        return JobResult.createSuccessResult(jobClient.getTaskId(), jobClient.getTaskId());
    }

    @Override
    public ComponentTestResult testConnect(String pluginInfo) {
        ComponentTestResult componentTestResult = new ComponentTestResult();
        try {
            Map map = PublicUtil.jsonStrToObject(pluginInfo, Map.class);
            if ("sftp".equalsIgnoreCase(String.valueOf(map.get(COMPONENT_TYPE)))) {
                SftpFactory sftpFactory = new SftpFactory(map);
                ChannelSftp channelSftp = sftpFactory.create();
                channelSftp.disconnect();
            }
            componentTestResult.setResult(true);
        } catch (Exception e) {
            componentTestResult.setErrorMsg(ExceptionUtil.getErrorMessage(e));
            componentTestResult.setResult(false);
        }
        return componentTestResult;
    }
}
