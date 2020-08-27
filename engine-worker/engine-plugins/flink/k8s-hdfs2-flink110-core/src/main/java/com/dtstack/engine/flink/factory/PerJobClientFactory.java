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

import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.enums.ComputeType;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.flink.FlinkClientBuilder;
import com.dtstack.engine.flink.FlinkConfig;
import com.dtstack.engine.flink.constrant.ConfigConstrant;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.client.deployment.ClusterDescriptor;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ResourceManagerOptions;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.kubernetes.KubernetesClusterDescriptor;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Date: 2020/6/1
 * Company: www.dtstack.com
 * @author maqi
 */
public class PerJobClientFactory extends AbstractClientFactory {
    private static final Logger LOG = LoggerFactory.getLogger(PerJobClientFactory.class);

    private FlinkConfig flinkConfig;
    private Configuration flinkConfiguration;

    private PerJobClientFactory(FlinkClientBuilder flinkClientBuilder) {
        this.flinkConfig = flinkClientBuilder.getFlinkConfig();
        this.flinkConfiguration = flinkClientBuilder.getFlinkConfiguration();
    }

    public ClusterDescriptor<String> createPerjobClusterDescriptor(JobClient jobClient, String projobClusterId) {
        Configuration newConf = new Configuration(flinkConfiguration);

        String taskIdMasterKey = ResourceManagerOptions.CONTAINERIZED_MASTER_ENV_PREFIX + ConfigConstrant.TASKID_KEY;
        newConf.setString(taskIdMasterKey, jobClient.getTaskId());
        String taskIdTaskMangerKey = ResourceManagerOptions.CONTAINERIZED_TASK_MANAGER_ENV_PREFIX + ConfigConstrant.TASKID_KEY;
        newConf.setString(taskIdTaskMangerKey, jobClient.getTaskId());

        String flinkxHostsMasterKey = ResourceManagerOptions.CONTAINERIZED_MASTER_ENV_PREFIX + ConfigConstrant.FLINKX_HOSTS_ENV;
        newConf.setString(flinkxHostsMasterKey, newConf.getString(ConfigConstrant.FLINKX_HOSTS_CONFIG_KEY, ""));
        String flinkxHostsTaskMangerKey = ResourceManagerOptions.CONTAINERIZED_TASK_MANAGER_ENV_PREFIX + ConfigConstrant.FLINKX_HOSTS_ENV;
        newConf.setString(flinkxHostsTaskMangerKey, newConf.getString(ConfigConstrant.FLINKX_HOSTS_CONFIG_KEY, ""));


        newConf = appendJobConfigAndInitFs(jobClient.getConfProperties(), newConf);

        newConf.setString(KubernetesConfigOptions.CLUSTER_ID, projobClusterId);

        if (!flinkConfig.getFlinkHighAvailability() && ComputeType.BATCH == jobClient.getComputeType()) {
            setNoneHaModeConfig(newConf);
        }

        newConf.setString(KubernetesConfigOptions.CLUSTER_ID, projobClusterId);

        KubernetesClusterDescriptor clusterDescriptor = getClusterDescriptor(newConf);

        // plugin dependent on shipfile
        if (StringUtils.isNotBlank(flinkConfig.getPluginLoadMode()) && ConfigConstrant.FLINK_PLUGIN_SHIPFILE_LOAD.equalsIgnoreCase(flinkConfig.getPluginLoadMode())) {
            newConf.setString(ConfigConstrant.FLINK_PLUGIN_LOAD_MODE, flinkConfig.getPluginLoadMode());
            newConf.setString("classloader.resolve-order", "parent-first");
        }

        return clusterDescriptor;
    }

    private Configuration appendJobConfigAndInitFs(Properties properties, Configuration configuration) {
        if (properties != null) {
            properties.forEach((key, value) -> {
                Boolean isLogLevel = key.toString().equalsIgnoreCase(KubernetesConfigOptions.FLINK_LOG_LEVEL.key());
                Boolean isLogFileName = key.toString().equalsIgnoreCase(KubernetesConfigOptions.FLINK_LOG_FILE_NAME.key());
                if (key.toString().contains(".") || isLogLevel || isLogFileName) {
                    configuration.setString(key.toString(), value.toString());
                }
            });
        }

        try {
            FileSystem.initialize(configuration);
        } catch (Exception e) {
            LOG.error("", e);
            throw new RdosDefineException(e.getMessage());
        }

        return configuration;
    }

    @Override
    public ClusterClient getClusterClient() {
        return null;
    }

    public static PerJobClientFactory createPerJobClientFactory(FlinkClientBuilder flinkClientBuilder) {
        PerJobClientFactory perJobClientFactory = new PerJobClientFactory(flinkClientBuilder);
        return perJobClientFactory;
    }

}
