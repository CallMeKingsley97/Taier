package com.dtstack.engine.base.resource;

import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.exception.LimitResourceException;
import com.google.common.collect.Lists;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.internal.NodeMetricOperationsImpl;
import org.agrona.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2018/11/1
 */
public abstract class AbstractK8sResourceInfo implements EngineResourceInfo {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private final static String PENDING_PHASE = "Pending";
    private final static String CPU = "cpu";
    private final static String MEMORY = "memory";

    private final static double RETAIN_MEM = Quantity.getAmountInBytes(new Quantity("512M")).doubleValue();
    private final static double RETAIN_CPU = Quantity.getAmountInBytes(new Quantity("1")).doubleValue();

    protected List<NodeResourceDetail> nodeResources = Lists.newArrayList();

    public List<NodeResourceDetail> getNodeResources() {
        return nodeResources;
    }

    public void addNodeResource(NodeResourceDetail nodeResourceDetail) {
        nodeResources.add(nodeResourceDetail);
    }

    protected double totalFreeCore = 0;
    protected double totalFreeMem = 0;
    protected double totalCore = 0;
    protected double totalMem = 0;
    protected double[] nmFreeCore = null;
    protected double[] nmFreeMem = null;

    protected boolean judgeResource(List<InstanceInfo> instanceInfos) {
        if (totalFreeCore == 0 || totalFreeMem == 0) {
            logger.info("judgeResource, totalFreeCore={}, totalFreeMem={}", totalFreeCore, totalFreeMem);
            return false;
        }
        double needTotalCore = 0;
        double needTotalMem = 0;
        for (InstanceInfo instanceInfo : instanceInfos) {
            needTotalCore += instanceInfo.instances * instanceInfo.coresPerInstance;
            needTotalMem += instanceInfo.instances * instanceInfo.memPerInstance;
        }
        if (needTotalCore == 0 || needTotalMem == 0) {
            throw new LimitResourceException("task resource configuration error，needTotalCore：" + 0 + ", needTotalMem：" + needTotalMem);
        }
        if (needTotalCore > totalCore) {
            logger.info("judgeResource, needTotalCore={}, totalCore={}", needTotalCore, totalCore);
            return false;
        }
        if (needTotalMem > totalMem) {
            logger.info("judgeResource, needTotalMem={}, totalMem={}", needTotalMem, totalMem);
            return false;
        }
        for (InstanceInfo instanceInfo : instanceInfos) {
            if (!judgeInstanceResource(instanceInfo.instances, instanceInfo.coresPerInstance, instanceInfo.memPerInstance)) {
                logger.info("judgeResource, nmFreeCore={}, nmFreeMem={} instanceInfo={}", nmFreeCore, nmFreeMem, instanceInfo);
                return false;
            }
        }
        return true;
    }

    private boolean judgeInstanceResource(int instances, double coresPerInstance, double memPerInstance) {
        if (instances == 0 || coresPerInstance == 0 || memPerInstance == 0) {
            throw new LimitResourceException("task resource configuration error，instance：" + instances + ", coresPerInstance：" + coresPerInstance + ", memPerInstance：" + memPerInstance);
        }
        if (!judgeCores(instances, coresPerInstance)) {
            return false;
        }
        if (!judgeMem(instances, memPerInstance)) {
            return false;
        }
        return true;
    }

    private boolean judgeCores(int instances, double coresPerInstance) {
        for (int i = 1; i <= instances; i++) {
            if (!allocateResource(nmFreeCore, coresPerInstance)) {
                return false;
            }
        }
        return true;
    }

    private boolean judgeMem(int instances, double memPerInstance) {
        for (int i = 1; i <= instances; i++) {
            if (!allocateResource(nmFreeMem, memPerInstance)) {
                return false;
            }
        }
        return true;
    }

    private boolean allocateResource(double[] node, double toAllocate) {
        for (int i = 0; i < node.length; i++) {
            if (node[i] >= toAllocate) {
                node[i] -= toAllocate;
                return true;
            }
        }
        return false;
    }

    public void getResource(KubernetesClient kubernetesClient, List<String> labels, int allowPendingPodSize) {
        if (allowPendingPodSize > 0) {
            List<Pod> pods = kubernetesClient.pods().list().getItems();
            List<Pod> pendingPods = pods.stream().filter(p -> PENDING_PHASE.equals(p.getStatus().getPhase())).collect(Collectors.toList());
            if (pendingPods.size() > allowPendingPodSize) {
                logger.info("pendingPods-size:{} allowPendingPodSize:{}", pendingPods.size(), allowPendingPodSize);
                return;
            }
        }

        List<Node> nodes = kubernetesClient.nodes().list().getItems();
        Map<String, NodeStatus> nodeStatusMap = new HashMap<>(nodes.size());
        for (Node node : nodes) {
            nodeStatusMap.put(node.getMetadata().getName(), node.getStatus());
        }

        NodeMetricOperationsImpl nodeMetricOperations = kubernetesClient.top().nodes();
        List<NodeMetrics> nodeMetrics = nodeMetricOperations.metrics().getItems();
        for (NodeMetrics nodeMetric : nodeMetrics) {

            String nodeName = nodeMetric.getMetadata().getName();
            NodeStatus nodeStatus = nodeStatusMap.get(nodeName);

            Map<String, Quantity> allocatable = nodeStatus.getAllocatable();
            Map<String, Quantity> usage = nodeMetric.getUsage();

            BigDecimal cpuAllocatable = Quantity.getAmountInBytes(allocatable.get(CPU));
            BigDecimal cpuUsage = Quantity.getAmountInBytes(usage.get(CPU));

            BigDecimal menAllocatable = Quantity.getAmountInBytes(allocatable.get(MEMORY));
            BigDecimal menUsage = Quantity.getAmountInBytes(usage.get(MEMORY));

            double freeCores = cpuAllocatable.subtract(cpuUsage).doubleValue();
            double freeMem = menAllocatable.subtract(menUsage).doubleValue();

            this.addNodeResource(new AbstractK8sResourceInfo.NodeResourceDetail(nodeName, cpuAllocatable.doubleValue(), cpuUsage.doubleValue(), freeCores, menAllocatable.doubleValue(), menUsage.doubleValue(), freeMem));
        }


        calc();
    }

    protected void calc() {
        nmFreeCore = new double[nodeResources.size()];
        nmFreeMem = new double[nodeResources.size()];

        int index = 0;
        //执行时，统一对每个node保留512M和1core
        for (AbstractK8sResourceInfo.NodeResourceDetail resourceDetail : nodeResources) {
            double nodeFreeMem = Math.max(resourceDetail.memoryFree - RETAIN_MEM, 0);
            double nodeMem = resourceDetail.memoryTotal - RETAIN_MEM;

            double nodeFreeCores = Math.max(resourceDetail.coresFree - RETAIN_CPU, 0);
            double nodeCores = resourceDetail.coresTotal - RETAIN_CPU;

            totalFreeMem += nodeFreeMem;
            totalFreeCore += nodeFreeCores;
            totalCore += nodeCores;
            totalMem += nodeMem;

            nmFreeMem[index] = nodeFreeMem;
            nmFreeCore[index] = nodeFreeCores;
            index++;
        }
    }

    public boolean judgeResourceInNamespace(List<InstanceInfo> instanceInfos, ResourceQuota resourceQuota) {
        Double needTotalCore = 0d;
        Double needTotalMem = 0d;
        for (InstanceInfo instanceInfo : instanceInfos) {
            needTotalCore += instanceInfo.instances * instanceInfo.coresPerInstance;
            needTotalMem += instanceInfo.instances * instanceInfo.memPerInstance;
        }

        Quantity amountTotalCores = resourceQuota.getStatus().getHard().get("requests.cpu");
        Quantity amountTotalMem = resourceQuota.getStatus().getHard().get("requests.memory");
        Double totalCores = Objects.isNull(amountTotalCores) ? 0d : Quantity.getAmountInBytes(amountTotalCores).doubleValue();
        Double totalMem = Objects.isNull(amountTotalMem) ? 0d : Quantity.getAmountInBytes(amountTotalMem).doubleValue();

        Quantity amountUsedCores = resourceQuota.getStatus().getUsed().get("requests.cpu");
        Quantity amountUsedMem = resourceQuota.getStatus().getUsed().get("requests.memory");
        Double usedCores = Objects.isNull(amountUsedCores) ? 0d : Quantity.getAmountInBytes(amountUsedCores).doubleValue();
        Double usedMem = Objects.isNull(amountUsedMem) ? 0d : Quantity.getAmountInBytes(amountUsedMem).doubleValue();

        Double freeCores = totalCores - usedCores;
        Double freeMem = totalMem - usedMem;

        if (freeCores <= needTotalCore) {
            logger.warn("Insufficient cpu resources。 needTotalCore: {}, freeCores: {}", needTotalCore, freeCores);
            return false;
        }

        if (freeMem <= needTotalMem) {
            logger.warn("Insufficient memory resources。 needTotalMem: {}, freeMem: {}", needTotalMem, freeMem);
            return false;
        }
        return true;
    }

    public static class InstanceInfo {
        int instances;
        double coresPerInstance;
        double memPerInstance;

        public InstanceInfo(int instances, double coresPerInstance, double memPerInstance) {
            this.instances = instances;
            this.coresPerInstance = coresPerInstance;
            this.memPerInstance = memPerInstance;
        }

        @Override
        public String toString() {
            return String.format("InstanceInfo[instances=%s, coresPerInstance=%s, memPerInstance=%s]", instances, coresPerInstance, memPerInstance);
        }

        public static InstanceInfo newRecord(int instances, double coresPerInstance, double memPerInstance) {
            return new InstanceInfo(instances, coresPerInstance, memPerInstance);
        }
    }

    public static class NodeResourceDetail {
        private String nodeId;
        public double coresTotal;
        public double coresUsed;
        public double coresFree;
        public double memoryTotal;
        public double memoryUsed;
        public double memoryFree;

        public NodeResourceDetail(String nodeId, double coresTotal, double coresUsed, double coresFree, double memoryTotal, double memoryUsed, double memoryFree) {
            this.nodeId = nodeId;
            this.coresTotal = coresTotal;
            this.coresUsed = coresUsed;
            this.coresFree = coresFree;
            this.memoryTotal = memoryTotal;
            this.memoryUsed = memoryUsed;
            this.memoryFree = memoryFree;
        }
    }
}
