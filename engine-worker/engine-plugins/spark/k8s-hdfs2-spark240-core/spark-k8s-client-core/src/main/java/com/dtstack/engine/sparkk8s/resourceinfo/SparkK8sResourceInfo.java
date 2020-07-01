package com.dtstack.engine.sparkk8s.resourceinfo;

import com.dtstack.engine.base.resource.EngineResourceInfo;
import com.dtstack.engine.common.JobClient;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 *
 *   Spark依赖kuber-client版本为4.7以上会有兼容问题，导致driver pod无法运行。
 * Date: 2020/6/24
 * Company: www.dtstack.com
 * @author maqi
 */

public class SparkK8sResourceInfo implements EngineResourceInfo {

    private Logger LOG = LoggerFactory.getLogger(getClass());

    private final static String PENDING_PHASE = "Pending";

    @Override
    public boolean judgeSlots(JobClient jobClient) {
        return true;
    }

    //TODO 通过TOP接口进行k8s资源判断
    public boolean judgeSlots(KubernetesClient kubernetesClient, int allowPendingPodSize) {
        if (allowPendingPodSize > 0) {
            List<Pod> pods = kubernetesClient.pods().list().getItems();
            List<Pod> pendingPods = pods.stream().filter(p -> PENDING_PHASE.equals(p.getStatus().getPhase())).collect(Collectors.toList());
            if (pendingPods.size() > allowPendingPodSize) {
                LOG.info("pendingPods-size:{} allowPendingPodSize:{}", pendingPods.size(), allowPendingPodSize);
                return false;
            }
        }
        return true;
    }
}
