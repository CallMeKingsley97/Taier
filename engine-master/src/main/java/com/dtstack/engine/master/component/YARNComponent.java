package com.dtstack.engine.master.component;

import com.dtstack.engine.api.domain.ClusterResourceDescription;
import com.dtstack.engine.master.enums.KerberosKey;
import com.dtstack.engine.master.utils.HadoopConf;
import com.dtstack.engine.master.utils.HadoopConfTool;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class YARNComponent extends BaseComponent {

    private List<NodeDescription> clusterNodes = new ArrayList<>();

    private ClusterResourceDescription resourceDescription;

    private YarnClient yarnClient;

    public YARNComponent(Map<String, Object> allConfig) {
        super(allConfig);
    }

    @Override
    public void testConnection() throws Exception {

    }

    public void initClusterResource(boolean closeYarnClient) throws Exception{
        try {
            initYarnClient();

            List<NodeReport> nodes = yarnClient.getNodeReports(NodeState.RUNNING);
            for (NodeReport rep : nodes) {
                NodeDescription node = new NodeDescription();
                node.setMemory(rep.getCapability().getMemory());
                node.setUsedMemory(rep.getUsed().getMemory());
                node.setUsedVirtualCores(rep.getUsed().getVirtualCores());
                node.setVirtualCores(rep.getCapability().getVirtualCores());

                clusterNodes.add(node);
            }

            initResourceDescription(yarnClient);
        } finally {
            if(closeYarnClient){
                closeYarnClient();
            }
        }
    }

    private void initResourceDescription(YarnClient yarnClient) throws Exception{
        int totalMemory = 0;
        int totalCores = 0;
        for (NodeDescription clusterNode : clusterNodes) {
            totalMemory += clusterNode.getMemory();
            totalCores += clusterNode.getVirtualCores();
        }

        List<ClusterResourceDescription.QueueDescription> descriptions = getQueueDescription(null, yarnClient.getRootQueueInfos());
        resourceDescription = new ClusterResourceDescription(clusterNodes.size(), totalMemory,totalCores,descriptions);
    }

    private List<ClusterResourceDescription.QueueDescription> getQueueDescription(String parentPath, List<QueueInfo> queueInfos) {
        List<ClusterResourceDescription.QueueDescription> descriptions = new ArrayList<>(queueInfos.size());
        parentPath = StringUtils.isBlank(parentPath) ? "" : parentPath + ".";
        for (QueueInfo queueInfo : queueInfos) {
            String queuePath = parentPath + queueInfo.getQueueName();
            ClusterResourceDescription.QueueDescription queueDescription = new ClusterResourceDescription.QueueDescription();
            queueDescription.setQueueName(queueInfo.getQueueName());
            queueDescription.setCapacity(String.valueOf(queueInfo.getCapacity()));
            queueDescription.setMaximumCapacity(String.valueOf(queueInfo.getMaximumCapacity()));
            queueDescription.setQueueState(queueInfo.getQueueState().name());
            queueDescription.setQueuePath(queuePath);
            if (CollectionUtils.isNotEmpty(queueInfo.getChildQueues())) {
                List<ClusterResourceDescription.QueueDescription> childQueues = getQueueDescription(queueInfo.getQueueName(), queueInfo.getChildQueues());
                queueDescription.setChildQueues(childQueues);
            }
            descriptions.add(queueDescription);
        }
        return descriptions;
    }

    public YarnClient getYarnClient() {
        if(yarnClient == null){
            initYarnClient();
        }

        return yarnClient;
    }

    public void initYarnClient(){
        HadoopConf hadoopConf = new HadoopConf();
        YarnConfiguration yarnConfiguration = hadoopConf.getYarnConf(allConfig);

        String principal = MapUtils.getString(allConfig, KerberosKey.PRINCIPAL.getKey());
        String keytab = MapUtils.getString(allConfig, KerberosKey.KEYTAB.getKey());
        String krb5Conf = MapUtils.getString(allConfig, HadoopConfTool.KEY_JAVA_SECURITY_KRB5_CONF);

        loginKerberos(yarnConfiguration, principal, keytab, krb5Conf);
        yarnClient = YarnClient.createYarnClient();
        yarnClient.init(yarnConfiguration);
        yarnClient.start();
    }

    public void closeYarnClient(){
        if (yarnClient != null){
            try {
                yarnClient.close();
                yarnClient = null;
            } catch (Exception e) {
                LOG.warn("An exception occurred while closing the yarnClient: ", e);
            }
        }
    }

    public void setYarnClient(YarnClient yarnClient) {
        this.yarnClient = yarnClient;
    }

    public List<NodeDescription> getClusterNodes() {
        return clusterNodes;
    }

    public void setClusterNodes(List<NodeDescription> clusterNodes) {
        this.clusterNodes = clusterNodes;
    }

    public ClusterResourceDescription getResourceDescription() {
        return resourceDescription;
    }

    public void setResourceDescription(ClusterResourceDescription resourceDescription) {
        this.resourceDescription = resourceDescription;
    }

    public class NodeDescription {
        private int memory;
        private int virtualCores;
        private int usedMemory;
        private int usedVirtualCores;

        public int getMemory() {
            return memory;
        }

        public void setMemory(int memory) {
            this.memory = memory;
        }

        public int getVirtualCores() {
            return virtualCores;
        }

        public void setVirtualCores(int virtualCores) {
            this.virtualCores = virtualCores;
        }

        public int getUsedMemory() {
            return usedMemory;
        }

        public void setUsedMemory(int usedMemory) {
            this.usedMemory = usedMemory;
        }

        public int getUsedVirtualCores() {
            return usedVirtualCores;
        }

        public void setUsedVirtualCores(int usedVirtualCores) {
            this.usedVirtualCores = usedVirtualCores;
        }
    }

}
