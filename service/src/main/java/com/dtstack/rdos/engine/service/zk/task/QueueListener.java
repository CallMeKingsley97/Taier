package com.dtstack.rdos.engine.service.zk.task;

import com.dtstack.rdos.commom.exception.ExceptionUtil;
import com.dtstack.rdos.engine.execution.base.queue.ClusterQueueInfo;
import com.dtstack.rdos.engine.service.node.WorkNode;
import com.dtstack.rdos.engine.service.zk.ZkDistributed;
import com.dtstack.rdos.engine.service.zk.data.BrokerQueueNode;
import com.dtstack.rdos.engine.execution.base.queue.ExeQueueMgr;
import com.dtstack.rdos.engine.execution.base.queue.GroupInfo;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;


/**
 *
 * @author sishu.yss
 *
 */
public class QueueListener implements Runnable{

	private static final Logger logger = LoggerFactory.getLogger(QueueListener.class);

	private final static int listener = 5 * 1000;

    private ZkDistributed zkDistributed = ZkDistributed.getZkDistributed();

	public QueueListener(){
	}

	@Override
	public void run() {

        while(true){
            try{
                logger.warn("QueueListener start again....");
                //获取所有节点的queue
                Map<String, BrokerQueueNode> queueNodeMap = zkDistributed.getAllBrokerQueueNode();
                Map<String, Map<String, Map<String, GroupInfo>>> queueInfo = Maps.newHashMap();
                queueNodeMap.forEach( (address, queueNode) -> queueInfo.put(address, queueNode.getGroupQueueInfo()));

                ClusterQueueInfo.getInstance().updateClusterQueueInfo(queueInfo);

                //更新当前节点的queue 信息
                Map<String, Map<String, Integer>>  localQueuePriority = ExeQueueMgr.getInstance().getEngineTypePriorityInfo();
                Map<String, Map<String, Integer>>  localQueueSize = WorkNode.getInstance().getEngineTypeQueueSizeInfo();

                Map<String,Map<String,GroupInfo>> engineTypeGroup = Maps.newHashMap();
                combineQueuePriority(localQueuePriority,engineTypeGroup);
                combineQueueSize(localQueueSize,engineTypeGroup);

                BrokerQueueNode localQueueNode = new BrokerQueueNode();
                localQueueNode.setGroupQueueInfo(engineTypeGroup);

                zkDistributed.updateSynchronizedLocalQueueNode(zkDistributed.getLocalAddress(), localQueueNode);
            }catch(Throwable e){
                logger.error("QueueListener error:{}",ExceptionUtil.getErrorMessage(e));
            }finally {
                try {
                    Thread.sleep(listener);
                } catch (InterruptedException e1) {
                    logger.error("", e1);
                }
            }
        }

	}

    private void combineQueuePriority(Map<String, Map<String, Integer>> localQueue,
                                  Map<String,Map<String,GroupInfo>> engineTypeGroup) {
	    for (Map.Entry<String,Map<String, Integer>> engineTypeEntry:localQueue.entrySet()){
            Map<String,GroupInfo> group = engineTypeGroup.computeIfAbsent(engineTypeEntry.getKey(),k->Maps.newHashMap());
            for (Map.Entry<String,Integer> engineTypeGroupEntry: engineTypeEntry.getValue().entrySet()){
                GroupInfo groupInfo = group.computeIfAbsent(engineTypeGroupEntry.getKey(),k->new GroupInfo());
                groupInfo.setPriority(engineTypeGroupEntry.getValue());
            }
        }
    }

    private void combineQueueSize(Map<String, Map<String, Integer>> localQueue,
                                  Map<String,Map<String,GroupInfo>> engineTypeGroup) {
        for (Map.Entry<String,Map<String, Integer>> engineTypeEntry:localQueue.entrySet()){
            Map<String,GroupInfo> group = engineTypeGroup.computeIfAbsent(engineTypeEntry.getKey(),k->Maps.newHashMap());
            for (Map.Entry<String,Integer> engineTypeGroupEntry: engineTypeEntry.getValue().entrySet()){
                GroupInfo groupInfo = group.computeIfAbsent(engineTypeGroupEntry.getKey(),k->new GroupInfo());
                groupInfo.setSize(engineTypeGroupEntry.getValue());
            }
        }
    }

}
