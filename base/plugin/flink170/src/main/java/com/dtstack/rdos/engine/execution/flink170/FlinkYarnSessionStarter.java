package com.dtstack.rdos.engine.execution.flink170;

import com.dtstack.rdos.engine.execution.flink170.constrant.ConfigConstrant;
import com.dtstack.rdos.engine.execution.flink170.util.FLinkConfUtil;
import org.apache.flink.client.deployment.ClusterSpecification;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.shaded.curator.org.apache.curator.framework.CuratorFramework;
import org.apache.flink.shaded.curator.org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.flink.shaded.curator.org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.flink.shaded.curator.org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.flink.util.FlinkException;
import org.apache.flink.yarn.AbstractYarnClusterDescriptor;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2019/5/30
 */
public class FlinkYarnSessionStarter {

    private static final Logger logger = LoggerFactory.getLogger(FlinkYarnSessionStarter.class);

    private String lockPath = null;
    private CuratorFramework zkClient;

    private AbstractYarnClusterDescriptor yarnSessionDescriptor;
    private ClusterSpecification yarnSessionSpecification;
    private ClusterClient<ApplicationId> clusterClient;
    private FlinkClientBuilder flinkClientBuilder;
    private FlinkConfig flinkConfig;
    private InterProcessMutex clusterClientLock;


    public FlinkYarnSessionStarter(FlinkClientBuilder flinkClientBuilder, FlinkConfig flinkConfig, FlinkPrometheusGatewayConfig metricConfig) throws MalformedURLException {
        this.flinkClientBuilder = flinkClientBuilder;
        this.flinkConfig = flinkConfig;
        lockPath = String.format("%s/client/%s", flinkConfig.getFlinkZkNamespace(), flinkConfig.getCluster() + ConfigConstrant.SPLIT + flinkConfig.getQueue());

        String clusterId = flinkConfig.getCluster() + ConfigConstrant.SPLIT + flinkConfig.getQueue();

        this.yarnSessionDescriptor = flinkClientBuilder.createClusterDescriptorByMode(flinkConfig, metricConfig, null, false);
        this.yarnSessionDescriptor.setName(flinkConfig.getFlinkSessionName() + ConfigConstrant.SPLIT + clusterId);
        this.yarnSessionSpecification = FLinkConfUtil.createYarnSessionSpecification(flinkClientBuilder.getFlinkConfiguration());

        initZk();
        this.clusterClientLock = new InterProcessMutex(zkClient, lockPath);
    }

    public void startFlinkYarnSession() {
        try {
            this.clusterClientLock.acquire();

            ClusterClient<ApplicationId> retrieveClusterClient = null;
            try {
                retrieveClusterClient = flinkClientBuilder.initYarnClusterClient(flinkConfig);
            } catch (Exception e) {
                logger.error("{}", e);
                if (!e.getMessage().startsWith("No flink session")) {
                    System.exit(-1);
                }
            }

            if (retrieveClusterClient != null) {
                clusterClient = retrieveClusterClient;
                logger.info("retrieve flink client with yarn session success");
                return;
            }

            try {
                clusterClient = yarnSessionDescriptor.deploySessionCluster(yarnSessionSpecification);
                clusterClient.setDetached(true);
            } catch (FlinkException e) {
                logger.info("Couldn't deploy Yarn session cluster, {}", e);
                throw e;
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

}
