package com.dtstack.rdos.engine.execution.flink170;

import com.dtstack.rdos.engine.execution.flink170.util.FLinkConfUtil;
import org.apache.flink.client.deployment.ClusterSpecification;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.shaded.curator.org.apache.curator.framework.CuratorFramework;
import org.apache.flink.shaded.curator.org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.flink.shaded.curator.org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.flink.shaded.curator.org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.StringUtils;
import org.apache.flink.yarn.AbstractYarnClusterDescriptor;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2019/5/30
 */
public class FlinkYarnSessionStarter {

    private static final Logger logger = LoggerFactory.getLogger(FlinkYarnSessionStarter.class);

    public static final String FLINK_CONF_FILENAME = "flink-conf.yaml";
    private static final String SPLIT = "_";

    private String lockPath = null;
    private CuratorFramework zkClient;

    private AbstractYarnClusterDescriptor yarnSessionDescriptor;
    private ClusterSpecification yarnSessionSpecification;
    private ClusterClient<ApplicationId> clusterClient;
    private FlinkClientBuilder flinkClientBuilder;
    private FlinkConfig flinkConfig;
    private InterProcessMutex clusterClientLock;
    private Configuration configuration;


    public FlinkYarnSessionStarter(FlinkClientBuilder flinkClientBuilder, FlinkConfig flinkConfig) throws MalformedURLException {
        this.flinkClientBuilder = flinkClientBuilder;
        this.flinkConfig = flinkConfig;
        lockPath = String.format("%s/client/%s", flinkConfig.getFlinkZkNamespace(), flinkConfig.getCluster() + SPLIT + flinkConfig.getQueue());


        Configuration configuration = loadConfiguration(flinkConfig.getFlinkJarPath());
        String clusterId = flinkConfig.getCluster() + SPLIT + flinkConfig.getQueue();
        configuration.setString(HighAvailabilityOptions.HA_CLUSTER_ID, clusterId);

        this.configuration = configuration;
        this.yarnSessionDescriptor = flinkClientBuilder.createClusterDescriptorByMode(configuration, null, false);
        this.yarnSessionDescriptor.setName(flinkConfig.getFlinkSessionName() + SPLIT + clusterId);
        this.yarnSessionSpecification = FLinkConfUtil.createYarnSessionSpecification(flinkClientBuilder.getFlinkConfiguration());

        initZk();
        this.clusterClientLock = new InterProcessMutex(zkClient, lockPath);
    }

    public boolean startFlinkYarnSession() {
        try {
            this.clusterClientLock.acquire();

            ClusterClient<ApplicationId> retrieveClusterClient = null;
            try {
                retrieveClusterClient = flinkClientBuilder.initYarnClusterClient(configuration);
            } catch (Exception e) {
                logger.error("{}", e);
            }

            if (retrieveClusterClient != null) {
                clusterClient = retrieveClusterClient;
                logger.info("retrieve flink client with yarn session success");
                return true;
            }

            if (flinkConfig.getYarnSessionStartAuto()) {
                try {
                    clusterClient = yarnSessionDescriptor.deploySessionCluster(yarnSessionSpecification);
                    clusterClient.setDetached(true);
                    return true;
                } catch (FlinkException e) {
                    logger.info("Couldn't deploy Yarn session cluster, {}", e);
                    throw e;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Couldn't deploy Yarn session cluster" + e.getMessage());
        } finally {
            if (this.clusterClientLock.isAcquiredInThisProcess()) {
                try {
                    this.clusterClientLock.release();
                } catch (Exception e) {
                    logger.error("lockPath:{} release clusterClientLock error:{}", lockPath, e);
                }
            }
        }

        return false;
    }

    public void stopFlinkYarnSession() {
        try {
            clusterClient.shutdown();
        } catch (Exception ex) {
            logger.info("Could not properly shutdown cluster client.", ex);
        }
    }

    public ClusterClient<ApplicationId> getClusterClient() {
        return clusterClient;
    }

    private void initZk() {
        this.zkClient = CuratorFrameworkFactory.builder()
                .connectString(flinkConfig.getFlinkZkAddress()).retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .connectionTimeoutMs(1000)
                .sessionTimeoutMs(1000).build();
        this.zkClient.start();
        logger.warn("connector zk success...");
    }


    private Configuration loadConfiguration(final String configDir) {
        File yamlConfigFile = null;
        try {
            if (StringUtils.isNullOrWhitespaceOnly(configDir)) {
                throw new IllegalArgumentException("Given configuration directory is null, cannot load configuration");
            }

            final File confDirFile = new File(configDir + "/../conf");
            if (!(confDirFile.exists())) {
                throw new IllegalConfigurationException(
                        "The given configuration directory name '" + configDir +
                                "' (" + confDirFile.getAbsolutePath() + ") does not describe an existing directory.");
            }

            // get Flink yaml configuration file
            yamlConfigFile = new File(confDirFile, FLINK_CONF_FILENAME);

            if (!yamlConfigFile.exists()) {
                throw new IllegalConfigurationException(
                        "The Flink config file '" + yamlConfigFile +
                                "' (" + confDirFile.getAbsolutePath() + ") does not exist.");
            }
        } catch (Exception e) {
            logger.error("{}", e);
        }
        return loadYAMLResource(yamlConfigFile);
    }

    private Configuration loadYAMLResource(File file) {
        final Configuration config = new Configuration();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {

            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                // 1. check for comments
                String[] comments = line.split("#", 2);
                String conf = comments[0].trim();

                // 2. get key and value
                if (conf.length() > 0) {
                    String[] kv = conf.split(": ", 2);

                    // skip line with no valid key-value pair
                    if (kv.length == 1) {
                        logger.warn("Error while trying to split key and value in configuration file " + file + ":" + lineNo + ": \"" + line + "\"");
                        continue;
                    }

                    String key = kv[0].trim();
                    String value = kv[1].trim();

                    // sanity check
                    if (key.length() == 0 || value.length() == 0) {
                        logger.warn("Error after splitting key and value in configuration file " + file + ":" + lineNo + ": \"" + line + "\"");
                        continue;
                    }

                    logger.info("Loading configuration property: {}, {}", key, value);
                    config.setString(key, value);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error parsing YAML configuration.", e);
        }

        return config;
    }

}
