package com.dtstack.engine.flink;

import com.dtstack.engine.common.exception.ExceptionUtil;
import com.dtstack.engine.common.exception.RdosException;
import com.dtstack.engine.common.JarFileInfo;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.flink.constrant.ConfigConstrant;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.client.deployment.ClusterRetrieveException;
import org.apache.flink.client.deployment.StandaloneClusterDescriptor;
import org.apache.flink.client.deployment.StandaloneClusterId;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.MiniClusterClient;
import org.apache.flink.client.program.rest.RestClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.runtime.akka.AkkaUtils;
import org.apache.flink.runtime.jobmanager.HighAvailabilityMode;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalException;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.runtime.util.HadoopUtils;
import org.apache.flink.runtime.util.LeaderConnectionInfo;
import org.apache.flink.yarn.AbstractYarnClusterDescriptor;
import org.apache.flink.yarn.YarnClusterDescriptor;
import org.apache.flink.yarn.configuration.YarnConfigOptions;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * 根据不同的配置创建对应的client
 * Date: 2018/5/3
 * Company: www.dtstack.com
 *
 * @author xuchao
 */

public class FlinkClientBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(FlinkClientBuilder.class);

    private final static String AKKA_ASK_TIMEOUT = "50 s";

    private final static String AKKA_CLIENT_TIMEOUT = "300 s";

    private final static String AKKA_TCP_TIMEOUT = "60 s";

    private static String jvm_options = "-XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:+CMSIncrementalMode -XX:+CMSIncrementalPacing";

    //默认使用异步提交
    private boolean isDetached = true;

    private FlinkConfig flinkConfig;

    private org.apache.hadoop.conf.Configuration hadoopConf;

    private YarnConfiguration yarnConf;

    private YarnClient yarnClient;

    private Configuration flinkConfiguration;

    public static FlinkClientBuilder create(FlinkConfig flinkConfig, org.apache.hadoop.conf.Configuration hadoopConf, YarnConfiguration yarnConf, YarnClient yarnClient) {
        FlinkClientBuilder builder = new FlinkClientBuilder();
        builder.flinkConfig = flinkConfig;
        builder.hadoopConf = hadoopConf;
        builder.yarnConf = yarnConf;
        builder.yarnClient = yarnClient;
        return builder;
    }

    public void initFlinkConfiguration(Properties extProp) {
        Configuration config = new Configuration();
        config.setString("akka.client.timeout", AKKA_CLIENT_TIMEOUT);
        config.setString("akka.ask.timeout", AKKA_ASK_TIMEOUT);
        config.setString("akka.tcp.timeout", AKKA_TCP_TIMEOUT);
        // JVM Param
        config.setString(CoreOptions.FLINK_JVM_OPTIONS, jvm_options);
        config.setBytes(HadoopUtils.HADOOP_CONF_BYTES, HadoopUtils.serializeHadoopConf(hadoopConf));

        if (extProp != null) {
            extProp.forEach((key, value) -> {
                if (!FlinkConfig.getEngineFlinkConfigs().contains(key.toString())) {
                    config.setString(key.toString(), value.toString());
                }
            });
        }

        try {
            FileSystem.initialize(config);
        } catch (Exception e) {
            LOG.error("", e);
            throw new RdosException(e.getMessage());
        }

        flinkConfiguration = config;
    }

    public ClusterClient createStandalone() {
        if (HighAvailabilityMode.ZOOKEEPER == HighAvailabilityMode.valueOf(flinkConfiguration.getValue(HighAvailabilityOptions.HA_MODE))) {
            return initClusterClientByZk();
        } else {
            return initClusterClientByUrl();
        }
    }

    /**
     * 根据zk获取clusterclient
     */
    private ClusterClient initClusterClientByZk() {

        Configuration config = new Configuration(flinkConfiguration);
        MiniClusterConfiguration.Builder configBuilder = new MiniClusterConfiguration.Builder();
        configBuilder.setConfiguration(config);
        //初始化的时候需要设置,否则提交job会出错,update config of jobMgrhost, jobMgrprt
        MiniCluster cluster = null;
        MiniClusterClient clusterClient = null;
        try {
            cluster = new MiniCluster(configBuilder.build());
            clusterClient = new MiniClusterClient(config, cluster);
            LeaderConnectionInfo connectionInfo = clusterClient.getClusterConnectionInfo();
            InetSocketAddress address = AkkaUtils.getInetSocketAddressFromAkkaURL(connectionInfo.getAddress());
            config.setString(JobManagerOptions.ADDRESS, address.getAddress().getHostName());
            config.setInteger(JobManagerOptions.PORT, address.getPort());
        } catch (LeaderRetrievalException e) {
            throw new RdosException("Could not retrieve the leader address and leader session ID.");
        } catch (Exception e1) {
            throw new RdosException("Failed to retrieve JobManager address");
        }
        return clusterClient;
    }

    /**
     * 直接指定jobmanager host:port方式
     */
    private ClusterClient initClusterClientByUrl() {

        String[] splitInfo = flinkConfig.getFlinkJobMgrUrl().split(":");
        if (splitInfo.length < 2) {
            throw new RdosException("the config of engineUrl is wrong. " +
                    "setting value is :" + flinkConfig.getFlinkJobMgrUrl() + ", please check it!");
        }

        String jobMgrHost = splitInfo[0].trim();
        Integer jobMgrPort = Integer.parseInt(splitInfo[1].trim());

        Configuration config = new Configuration();
        config.setString(JobManagerOptions.ADDRESS, jobMgrHost);
        config.setInteger(JobManagerOptions.PORT, jobMgrPort);

        StandaloneClusterDescriptor descriptor = new StandaloneClusterDescriptor(config);
        RestClusterClient<StandaloneClusterId> clusterClient = null;
        try {
            clusterClient = descriptor.retrieve(null);
        } catch (ClusterRetrieveException e) {
            throw new RdosException("Couldn't retrieve standalone cluster");
        }
        clusterClient.setDetached(isDetached);
        return clusterClient;
    }

    /**
     * 根据yarn方式获取ClusterClient
     */
    public ClusterClient<ApplicationId> initYarnClusterClient() {

        Configuration newConf = new Configuration(flinkConfiguration);

        ApplicationId applicationId = acquireAppIdAndSetClusterId(newConf);

        ClusterClient<ApplicationId> clusterClient = null;

        AbstractYarnClusterDescriptor clusterDescriptor = getClusterDescriptor(newConf, yarnConf, ".");

        try {
            clusterClient = clusterDescriptor.retrieve(applicationId);
        } catch (Exception e) {
            LOG.info("No flink session, Couldn't retrieve Yarn cluster.", e);
            throw new RdosException("No flink session, Couldn't retrieve Yarn cluster.");
        }

        clusterClient.setDetached(isDetached);
        LOG.warn("---init flink client with yarn session success----");

        return clusterClient;
    }

    public AbstractYarnClusterDescriptor createClusterDescriptorByMode(JobClient jobClient, boolean isPerjob) throws MalformedURLException {
        Configuration newConf = new Configuration(flinkConfiguration);
        if (isPerjob && jobClient != null) {
            newConf.setString(HighAvailabilityOptions.HA_CLUSTER_ID, jobClient.getTaskId());
            newConf.setInteger(YarnConfigOptions.APPLICATION_ATTEMPTS.key(), 0);
            newConf = addConfiguration(jobClient.getConfProperties(), newConf);
        } else {
            String clusterId = flinkConfig.getCluster() + ConfigConstrant.SPLIT + flinkConfig.getQueue();
            newConf.setString(HighAvailabilityOptions.HA_CLUSTER_ID, clusterId);
        }

        AbstractYarnClusterDescriptor clusterDescriptor = getClusterDescriptor(newConf, yarnConf, ".");
        String flinkJarPath = null;

        if (StringUtils.isNotBlank(flinkConfig.getFlinkJarPath())) {

            if (!new File(flinkConfig.getFlinkJarPath()).exists()) {
                throw new RdosException("The Flink jar path is not exist");
            }

            flinkJarPath = flinkConfig.getFlinkJarPath();
        }

        if (StringUtils.isNotBlank(flinkConfig.getPluginLoadMode()) && ConfigConstrant.FLINK_PLUGIN_SHIPFILE_LOAD.equalsIgnoreCase(flinkConfig.getPluginLoadMode())) {
            newConf.setString(ConfigConstrant.FLINK_PLUGIN_LOAD_MODE, flinkConfig.getPluginLoadMode());
            newConf.setString("classloader.resolve-order", "parent-first");

            String flinkPluginRoot = flinkConfig.getFlinkPluginRoot();
            List<File> pluginPaths = fillAllPluginPathForYarnSession(isPerjob, flinkPluginRoot);
            if (!pluginPaths.isEmpty()) {
                clusterDescriptor.addShipFiles(pluginPaths);
            }
        }

        List<URL> classpaths = new ArrayList<>();
        if (flinkJarPath != null) {
            File[] jars = new File(flinkJarPath).listFiles();

            for (File file : jars) {
                if (file.toURI().toURL().toString().contains("flink-dist")) {
                    clusterDescriptor.setLocalJarPath(new Path(file.toURI().toURL().toString()));
                } else {
                    classpaths.add(file.toURI().toURL());
                }
            }

        } else {
            throw new RdosException("The Flink jar path is null");
        }

        if (isPerjob && jobClient != null && CollectionUtils.isNotEmpty(jobClient.getAttachJarInfos())) {
            for (JarFileInfo jarFileInfo : jobClient.getAttachJarInfos()) {
                classpaths.add(new File(jarFileInfo.getJarPath()).toURI().toURL());
            }
        }

        clusterDescriptor.setProvidedUserJarFiles(classpaths);
        clusterDescriptor.setQueue(flinkConfig.getQueue());
        return clusterDescriptor;
    }

    private AbstractYarnClusterDescriptor getClusterDescriptor(
            Configuration configuration,
            YarnConfiguration yarnConfiguration,
            String configurationDirectory) {
        return new YarnClusterDescriptor(
                configuration,
                yarnConfiguration,
                configurationDirectory,
                yarnClient,
                true);
    }

    private ApplicationId acquireAppIdAndSetClusterId(Configuration configuration) {
        try {
            Set<String> set = new HashSet<>();
            set.add("Apache Flink");
            EnumSet<YarnApplicationState> enumSet = EnumSet.noneOf(YarnApplicationState.class);
            enumSet.add(YarnApplicationState.RUNNING);
            enumSet.add(YarnApplicationState.ACCEPTED);
            List<ApplicationReport> reportList = yarnClient.getApplications(set, enumSet);

            int maxMemory = -1;
            int maxCores = -1;
            ApplicationId applicationId = null;


            for (ApplicationReport report : reportList) {
                if (!report.getName().startsWith(flinkConfig.getFlinkSessionName())) {
                    continue;
                }

                if (!report.getYarnApplicationState().equals(YarnApplicationState.RUNNING)) {
                    continue;
                }

                if (!report.getQueue().endsWith(flinkConfig.getQueue())) {
                    continue;
                }

                int thisMemory = report.getApplicationResourceUsageReport().getNeededResources().getMemory();
                int thisCores = report.getApplicationResourceUsageReport().getNeededResources().getVirtualCores();
                if (thisMemory > maxMemory || thisMemory == maxMemory && thisCores > maxCores) {
                    maxMemory = thisMemory;
                    maxCores = thisCores;
                    applicationId = report.getApplicationId();
                    String clusterId = flinkConfig.getCluster() + ConfigConstrant.SPLIT + flinkConfig.getQueue();
                    //flinkClusterId不为空 且 yarnsession不是由engine来管控时，需要设置clusterId（兼容手动启动yarnsession的情况）
                    if (StringUtils.isBlank(configuration.getValue(HighAvailabilityOptions.HA_CLUSTER_ID)) || report.getName().endsWith(clusterId)) {
                        configuration.setString(HighAvailabilityOptions.HA_CLUSTER_ID, applicationId.toString());
                    }
                }

            }

            if (applicationId == null) {
                throw new RdosException("No flink session found on yarn cluster.");
            }
            return applicationId;
        } catch (Exception e) {
            LOG.error("", e);
            throw new RdosException(e.getMessage());
        }
    }

    public FlinkConfig getFlinkConfig() {
        return flinkConfig;
    }

    public org.apache.hadoop.conf.Configuration getHadoopConf() {
        return hadoopConf;
    }

    public YarnConfiguration getYarnConf() {
        return yarnConf;
    }

    public YarnClient getYarnClient() {
        return this.yarnClient;
    }

    public Configuration getFlinkConfiguration() {
        if (flinkConfiguration == null) {
            throw new RdosException("Configuration directory not set");
        }
        return flinkConfiguration;
    }

    private Configuration consoleConfiguration(Configuration flinkConfiguration) {
        Configuration configuration = flinkConfiguration;
        for (String key : flinkConfiguration.keySet()) {
            if (key.startsWith("flink.")) {
                String[] configs = key.split("flink.");
                if (configs.length == 2) {
                    configuration.setString(configs[1], flinkConfiguration.getString(key, ""));
                }
            }
        }
        return configuration;
    }

    private Configuration addConfiguration(Properties properties, Configuration configuration) {
        if (properties != null) {
            properties.forEach((key, value) -> {
                if (key.toString().contains(".")) {
                    configuration.setString(key.toString(), value.toString());
                }
            });
        }
        try {
            FileSystem.initialize(configuration);
        } catch (Exception e) {
            LOG.error("", e);
            throw new RdosException(e.getMessage());
        }

        return configuration;
    }

    private List<File> fillAllPluginPathForYarnSession(boolean isPerjob, String flinkPluginRoot) {
        List<File> pluginPaths = Lists.newArrayList();
        if (!isPerjob) {
            //预加载同步插件jar包
            if(StringUtils.isNotBlank(flinkPluginRoot)){
                String syncPluginDir = buildSyncPluginDir(flinkPluginRoot);
                try {
                    File[] jars = new File(syncPluginDir).listFiles();
                    if(jars != null){
                        pluginPaths.addAll(Arrays.asList(jars));
                    }else {
                        LOG.warn("jars in flinkPluginRoot is null, flinkPluginRoot = {}", flinkPluginRoot);
                    }
                }catch (Exception e){
                    LOG.error("error to load jars in flinkPluginRoot, flinkPluginRoot = {}, e = {}", flinkPluginRoot, ExceptionUtil.getErrorMessage(e));
                }
            }
        }
        return pluginPaths;
    }

    public String buildSyncPluginDir(String pluginRoot){
        return pluginRoot +  SyncPluginInfo.FILE_SP + SyncPluginInfo.SYNC_PLUGIN_DIR_NAME;
    }
}
