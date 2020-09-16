/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.engine.flink.factory;

import com.dtstack.engine.base.util.KerberosUtils;
import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.flink.FlinkClientBuilder;
import com.dtstack.engine.flink.FlinkClusterClientManager;
import com.dtstack.engine.flink.FlinkConfig;
import com.dtstack.engine.flink.constrant.ConfigConstrant;
import com.dtstack.engine.flink.plugininfo.SyncPluginInfo;
import com.dtstack.engine.flink.util.FileUtil;
import com.dtstack.engine.flink.util.FlinkConfUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.client.deployment.ClusterSpecification;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.configuration.SecurityOptions;
import org.apache.flink.shaded.curator.org.apache.curator.framework.CuratorFramework;
import org.apache.flink.shaded.curator.org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.flink.shaded.curator.org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.flink.shaded.curator.org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.flink.util.FlinkException;
import org.apache.flink.yarn.AbstractYarnClusterDescriptor;
import org.apache.hadoop.service.Service;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Date: 2020/5/12
 * Company: www.dtstack.com
 * @author maqi
 */
public class SessionClientFactory extends AbstractClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(SessionClientFactory.class);

    private ClusterSpecification yarnSessionSpecification;
    private ClusterClient<ApplicationId> clusterClient;
    private FlinkConfig flinkConfig;
    private InterProcessMutex clusterClientLock;
    private String lockPath;
    private CuratorFramework zkClient;
    private Configuration flinkConfiguration;
    private String sessionAppNameSuffix;

    private boolean isDetached = true;
    private FlinkClusterClientManager flinkClusterClientManager;
    private ExecutorService yarnMonitorES;

    public SessionClientFactory(FlinkClusterClientManager flinkClusterClientManager, FlinkClientBuilder flinkClientBuilder) throws MalformedURLException {
        super(flinkClientBuilder);
        this.flinkConfig = flinkClientBuilder.getFlinkConfig();
        this.flinkClusterClientManager = flinkClusterClientManager;
        this.flinkConfiguration = flinkClientBuilder.getFlinkConfiguration();

        this.sessionAppNameSuffix = flinkConfig.getCluster() + ConfigConstrant.SPLIT + flinkConfig.getQueue();
        this.yarnSessionSpecification = FlinkConfUtil.createYarnSessionSpecification(flinkClientBuilder.getFlinkConfiguration());

        initZkClient();
        this.lockPath = String.format("/yarn_session/%s", flinkConfig.getCluster() + ConfigConstrant.SPLIT + flinkConfig.getQueue());
        this.clusterClientLock = new InterProcessMutex(zkClient, lockPath);

        startYarnSessionClientMonitor();
    }

    private void initZkClient() {
        String zkAddress = flinkConfiguration.getValue(HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM);
        if (StringUtils.isBlank(zkAddress)) {
            throw new RdosDefineException("zkAddress is error");
        }

        this.zkClient = CuratorFrameworkFactory.builder()
                .connectString(zkAddress)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .connectionTimeoutMs(1000)
                .sessionTimeoutMs(1000)
                .build();
        this.zkClient.start();
        LOG.warn("connector zk success...");
    }

    private void startYarnSessionClientMonitor() {
        yarnMonitorES = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new CustomThreadFactory("flink_yarn_monitor"));

        //启动守护线程---用于获取当前application状态和更新flink对应的application
        yarnMonitorES.submit(new AppStatusMonitor(flinkClusterClientManager, flinkClientBuilder, this));
    }


    public boolean startFlinkYarnSession() {
        try {
            if (this.clusterClientLock.acquire(5, TimeUnit.MINUTES)) {
                ClusterClient<ApplicationId> retrieveClusterClient = null;
                try {
                    retrieveClusterClient = initYarnClusterClient();
                } catch (Exception e) {
                    LOG.error("{}", e);
                }

                if (retrieveClusterClient != null) {
                    clusterClient = retrieveClusterClient;
                    LOG.info("retrieve flink client with yarn session success");
                    return true;
                }

                if (flinkConfig.getSessionStartAuto()) {
                    try {
                        AbstractYarnClusterDescriptor yarnSessionDescriptor = createYarnSessionClusterDescriptor();
                        yarnSessionDescriptor.setName(flinkConfig.getFlinkSessionName() + ConfigConstrant.SPLIT + sessionAppNameSuffix);
                        clusterClient = yarnSessionDescriptor.deploySessionCluster(yarnSessionSpecification);
                        clusterClient.setDetached(true);
                        return true;
                    } catch (FlinkException e) {
                        LOG.info("Couldn't deploy Yarn session cluster, {}", e);
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Couldn't deploy Yarn session cluster:{}", e);
        } finally {
            if (this.clusterClientLock.isAcquiredInThisProcess()) {
                try {
                    this.clusterClientLock.release();
                } catch (Exception e) {
                    LOG.error("lockPath:{} release clusterClientLock error:{}", lockPath, e);
                }
            }
        }
        return false;
    }


    public void stopFlinkYarnSession() {
        try {
            clusterClient.shutdown();
        } catch (Exception ex) {
            LOG.info("[YarnSessionClientFactory] Could not properly shutdown cluster client.", ex);
        }
    }

    @Override
    public ClusterClient<ApplicationId> getClusterClient() {
        return clusterClient;
    }

    /**
     * 根据yarn方式获取ClusterClient
     */
    public ClusterClient<ApplicationId> initYarnClusterClient() {
        Configuration newConf = new Configuration(flinkConfiguration);
        ApplicationId applicationId = acquireAppIdAndSetClusterId(newConf);

        if (!flinkConfig.getFlinkHighAvailability()) {
            setNoneHaModeConfig(newConf);
        }
        AbstractYarnClusterDescriptor clusterDescriptor = getClusterDescriptor(newConf, flinkClientBuilder.getYarnConf(), ".");

        ClusterClient<ApplicationId> clusterClient = null;
        try {
            clusterClient = clusterDescriptor.retrieve(applicationId);
        } catch (Exception e) {
            LOG.info("No flink session, Couldn't retrieve Yarn cluster.", e);
            throw new RdosDefineException("No flink session, Couldn't retrieve Yarn cluster.");
        }

        clusterClient.setDetached(isDetached);
        LOG.warn("---init flink client with yarn session success----");

        return clusterClient;
    }

    private ApplicationId acquireAppIdAndSetClusterId(Configuration configuration) {
        try {
            Set<String> set = new HashSet<>();
            set.add("Apache Flink");
            EnumSet<YarnApplicationState> enumSet = EnumSet.noneOf(YarnApplicationState.class);
            enumSet.add(YarnApplicationState.RUNNING);
            enumSet.add(YarnApplicationState.ACCEPTED);

            YarnClient yarnClient = flinkClientBuilder.getYarnClient();
            if (Objects.isNull(yarnClient)) {
                throw new RdosDefineException("getYarnClient error, Yarn Client is null!");
            }

            List<ApplicationReport> reportList = yarnClient.getApplications(set, enumSet);

            int maxMemory = -1;
            int maxCores = -1;
            ApplicationId applicationId = null;


            for (ApplicationReport report : reportList) {
                LOG.info("filter flink session application current reportName:{} queue:{} status:{}", report.getName(), report.getQueue(), report.getYarnApplicationState());
                if (!report.getYarnApplicationState().equals(YarnApplicationState.RUNNING)) {
                    continue;
                }
                if (!report.getName().startsWith(flinkConfig.getFlinkSessionName())) {
                    continue;
                }
                if (flinkConfig.getSessionStartAuto() && !report.getName().endsWith(sessionAppNameSuffix)) {
                    continue;
                }
                if (!report.getQueue().endsWith(flinkConfig.getQueue())) {
                    continue;
                }

                int thisMemory = report.getApplicationResourceUsageReport().getNeededResources().getMemory();
                int thisCores = report.getApplicationResourceUsageReport().getNeededResources().getVirtualCores();
                LOG.info("current flink session memory {},Cores{}", thisMemory, thisCores);
                if (thisMemory > maxMemory || thisMemory == maxMemory && thisCores > maxCores) {
                    maxMemory = thisMemory;
                    maxCores = thisCores;
                    applicationId = report.getApplicationId();
                    //flinkClusterId不为空 且 yarnsession不是由engine来管控时，需要设置clusterId（兼容手动启动yarnsession的情况）
                    if (StringUtils.isBlank(configuration.getValue(HighAvailabilityOptions.HA_CLUSTER_ID)) || report.getName().endsWith(sessionAppNameSuffix)) {
                        configuration.setString(HighAvailabilityOptions.HA_CLUSTER_ID, applicationId.toString());
                    }
                }

            }

            if (applicationId == null) {
                throw new RdosDefineException("No flink session found on yarn cluster.");
            }
            return applicationId;
        } catch (Exception e) {
            LOG.error("", e);
            throw new RdosDefineException(e.getMessage());
        }
    }

    public AbstractYarnClusterDescriptor createYarnSessionClusterDescriptor() throws MalformedURLException {
        Configuration newConf = new Configuration(flinkConfiguration);

        String flinkJarPath = flinkConfig.getFlinkJarPath();
        String pluginLoadMode = flinkConfig.getPluginLoadMode();
        YarnConfiguration yarnConf = flinkClientBuilder.getYarnConf();

        FileUtil.checkFileExist(flinkJarPath);

        if (!flinkConfig.getFlinkHighAvailability()) {
            setNoneHaModeConfig(newConf);
        } else {
            //由engine管控的yarnsession clusterId不进行设置，默认使用appId作为clusterId
            newConf.removeConfig(HighAvailabilityOptions.HA_CLUSTER_ID);
        }

        List<File> keytabFiles = null;
        if (flinkConfig.isOpenKerberos()) {
            keytabFiles = getKeytabFilesAndSetSecurityConfig(newConf);
        }

        AbstractYarnClusterDescriptor clusterDescriptor = getClusterDescriptor(newConf, yarnConf, ".");

        if (StringUtils.isNotBlank(pluginLoadMode) && ConfigConstrant.FLINK_PLUGIN_SHIPFILE_LOAD.equalsIgnoreCase(pluginLoadMode)) {
            newConf.setString(ConfigConstrant.FLINK_PLUGIN_LOAD_MODE, flinkConfig.getPluginLoadMode());
            newConf.setString("classloader.resolve-order", "parent-first");

            String flinkPluginRoot = flinkConfig.getFlinkPluginRoot();
            if (StringUtils.isNotBlank(flinkPluginRoot)) {
                String syncPluginDir = flinkPluginRoot + ConfigConstrant.SP + ConfigConstrant.SYNCPLUGIN_DIR;
                File syncFile = new File(syncPluginDir);
                if (!syncFile.exists()) {
                    throw new RdosDefineException("syncPlugin path is null");
                }
                List<File> pluginPaths = Arrays.stream(syncFile.listFiles())
                        .filter(file -> !file.getName().endsWith("zip"))
                        .collect(Collectors.toList());
                clusterDescriptor.addShipFiles(pluginPaths);
            }
        }
        if(CollectionUtils.isNotEmpty(keytabFiles)){
            clusterDescriptor.addShipFiles(keytabFiles);
        }
        List<URL> classpaths = getFlinkJarFile(flinkJarPath, clusterDescriptor);
        clusterDescriptor.setProvidedUserJarFiles(classpaths);
        clusterDescriptor.setQueue(flinkConfig.getQueue());
        return clusterDescriptor;
    }

    private List<File> getKeytabFilesAndSetSecurityConfig(Configuration config) {
        Map<String, File> keytabs = new HashMap<>();
        String remoteDir = flinkConfig.getRemoteDir();

        // 任务提交keytab
        String clusterKeytabDirPath = ConfigConstrant.LOCAL_KEYTAB_DIR_PARENT + remoteDir;
        File clusterKeytabDir = new File(clusterKeytabDirPath);
        File[] clusterKeytabFiles = clusterKeytabDir.listFiles();

        if (clusterKeytabFiles == null || clusterKeytabFiles.length == 0) {
            throw new RdosDefineException("not find keytab file from " + clusterKeytabDirPath);
        }
        for (File file : clusterKeytabFiles) {
            String fileName = file.getName();
            String keytabPath = file.getAbsolutePath();
            String keytabFileName = flinkConfig.getPrincipalFile();

            if (StringUtils.equals(fileName, keytabFileName)) {
                String principal = KerberosUtils.getPrincipal(keytabPath);
                config.setString(SecurityOptions.KERBEROS_LOGIN_KEYTAB, keytabPath);
                config.setString(SecurityOptions.KERBEROS_LOGIN_PRINCIPAL, principal);
            }
            keytabs.put(file.getName(), file);
        }

        return keytabs.entrySet().stream().map(entry -> entry.getValue()).collect(Collectors.toList());
    }

    static class AppStatusMonitor implements Runnable {

        /**
         * 检查时间不需要太频繁，默认10s/次
         */
        private static final Integer CHECK_INTERVAL = 10 * 1000;

        private static final Integer RETRY_WAIT = 10 * 1000;

        private AtomicBoolean run = new AtomicBoolean(true);

        private FlinkClusterClientManager clusterClientManager;

        private FlinkClientBuilder clientBuilder;

        private SessionClientFactory sessionClientFactory;

        private YarnApplicationState lastAppState;

        private String attemptId;

        private long startTime = System.currentTimeMillis();

        public AppStatusMonitor(FlinkClusterClientManager clusterClientManager, FlinkClientBuilder clientBuilder, SessionClientFactory yarnSessionClientFactory) {
            this.clusterClientManager = clusterClientManager;
            this.clientBuilder = clientBuilder;
            this.sessionClientFactory = yarnSessionClientFactory;
            this.lastAppState = YarnApplicationState.NEW;
        }

        @Override
        public void run() {
            while (run.get()) {
                try {
                    if (clusterClientManager.getIsClientOn()) {
                        if (clientBuilder.getYarnClient().isInState(Service.STATE.STARTED)) {
                            ApplicationId applicationId = (ApplicationId) clusterClientManager.getClusterClient().getClusterId();
                            ApplicationReport applicationReport = clientBuilder.getYarnClient().getApplicationReport(applicationId);
                            YarnApplicationState appState = applicationReport.getYarnApplicationState();
                            switch (appState) {
                                case FAILED:
                                case KILLED:
                                case FINISHED:
                                    LOG.error("-------Flink yarn-session appState:{}, prepare to stop Flink yarn-session client ----", appState.toString());
                                    clusterClientManager.setIsClientOn(false);
                                    break;
                                case RUNNING:
                                    if (lastAppState != appState) {
                                        LOG.info("YARN application has been deployed successfully.");
                                    }
                                    if (isDifferentAttemptId(applicationReport)) {
                                        LOG.error("AttemptId has changed, prepare to stop Flink yarn-session client.");
                                        clusterClientManager.setIsClientOn(false);
                                    }
                                    break;
                                default:
                                    if (appState != lastAppState) {
                                        LOG.info("Deploying cluster, current state " + appState);
                                    }
                                    if (System.currentTimeMillis() - startTime > 60000) {
                                        LOG.info("Deployment took more than 60 seconds. Please check if the requested resources are available in the YARN cluster");
                                    }
                            }
                            lastAppState = appState;
                        } else {
                            LOG.error("Yarn client is no longer in state STARTED, prepare to stop Flink yarn-session client.");
                            clusterClientManager.setIsClientOn(false);
                        }
                    } else {
                        //retry时有一段等待时间，确保session正常运行。
                        retry();
                    }
                } catch (Throwable t) {
                    LOG.error("YarnAppStatusMonitor check error:{}", t);
                    clusterClientManager.setIsClientOn(false);
                } finally {
                    try {
                        Thread.sleep(CHECK_INTERVAL);
                    } catch (Exception e) {
                        LOG.error("", e);
                    }
                }
            }
        }

        private void retry() {
            //重试
            try {
                if (sessionClientFactory.getClusterClient() != null) {
                    LOG.error("------- Flink yarn-session client shutdown ----");
                    sessionClientFactory.stopFlinkYarnSession();
                }
                LOG.warn("-- retry Flink yarn-session client ----");
                startTime = System.currentTimeMillis();
                this.lastAppState = YarnApplicationState.NEW;
                clusterClientManager.initClusterClient();
                attemptId = null;

                Thread.sleep(RETRY_WAIT);
            } catch (Exception e) {
                LOG.error("", e);
            }
        }

        public void setRun(boolean run) {
            this.run = new AtomicBoolean(run);
        }

        private boolean isDifferentAttemptId(ApplicationReport applicationReport) {
            String appId = applicationReport.getCurrentApplicationAttemptId().getApplicationId().toString();
            String attemptIdStr = String.valueOf(applicationReport.getCurrentApplicationAttemptId().getAttemptId());
            String currentAttemptId = appId + attemptIdStr;
            if (attemptId == null) {
                attemptId = currentAttemptId;
                return false;
            }
            if (!attemptId.equals(currentAttemptId)) {
                attemptId = currentAttemptId;
                return true;
            }
            return false;
        }


    }

}
