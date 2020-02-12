package com.dtstack.engine.master.task;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.master.MasterNode;
import com.dtstack.engine.master.data.BrokerHeartNode;
import com.dtstack.engine.dao.NodeMachineDao;
import com.dtstack.engine.common.enums.RdosNodeMachineType;
import com.dtstack.engine.master.zookeeper.ZkDistributed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dtstack.engine.common.exception.ExceptionUtil;
import com.dtstack.engine.common.util.PublicUtil;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @company: www.dtstack.com
 * @author: toutian
 * @create: 2018/9/8
 */
@Component
public class HeartBeatCheckListener implements Runnable{

	private static final Logger logger = LoggerFactory.getLogger(HeartBeatCheckListener.class);

	private final static int CHECK_INTERVAL = 2000;

	//正常停止
	private final static int RESTART_TIMEOUT_COUNT = 30;
	//宕机
	private final static int OUTAGE_TIMEOUT_COUNT = 10;
	private MasterListener masterListener;

	private ZkDistributed zkDistributed = ZkDistributed.getZkDistributed();
	private MasterNode masterNode = MasterNode.getInstance();

	@Autowired
	private NodeMachineDao nodeMachineDao;

	private int logOutput = 0;

	private static HeartBeatCheckListener listener;

	public static void init(MasterListener masterListener) {
		listener = new HeartBeatCheckListener(masterListener);
	}

	private HeartBeatCheckListener(MasterListener masterListener){
		this.masterListener = masterListener;
        ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(1, new CustomThreadFactory("HeartBeatCheckListener"));
        scheduledService.scheduleWithFixedDelay(
                this,
				CHECK_INTERVAL ,
                CHECK_INTERVAL,
                TimeUnit.MILLISECONDS);
	}

	private Map<String, BrokerNodeCount> brokerNodeCounts =  Maps.newHashMap();

	@Override
	public void run() {
        try {
            if(this.masterListener.isMaster()){
                logOutput++;
                healthCheck();
                if(PublicUtil.count(logOutput, 5)){logger.warn("HeartBeatCheckListener start check again...");}
            }
        } catch (Throwable e) {
            logger.error(ExceptionUtil.getErrorMessage(e));
        }
	}

    /**
     * 节点未正常重启、宕机都由 master 的健康检查机制来做任务恢复
	 * healthCheck 后的 broker-heart-seq = -1
     */
	private void healthCheck(){
		List<String> childrens = this.zkDistributed.getBrokersChildren();
		if(childrens!=null){
			for(String node:childrens){
				BrokerHeartNode brokerNode = this.zkDistributed.getBrokerHeartNode(node);
				boolean ignore = brokerNode == null || ZkDistributed.STOP_HEALTH_CHECK_SEQ == brokerNode.getSeq();
				if(ignore){
					continue;
				}
				BrokerNodeCount brokerNodeCount = brokerNodeCounts.computeIfAbsent(node, k->{
					//TODO
//					this.nodeMachineDao.ableMachineNode(node, RdosNodeMachineType.SLAVE.getType());
					return new BrokerNodeCount(brokerNode);
				});
				//是否假死
				if (brokerNode.getAlive()){
					//1. 异常宕机的节点，alive=true，seq不会再进行更新
					//2. 需要加入条件 seq !=0，因为当节点重启时 seq 可能都为0，可能会满足条件 brokerNode.getAlive() && brokerNodeCount.getCount() > OUTAGE_TIMEOUT_COUNT
					//导致多执行一次 dataMigration
					if(brokerNodeCount.getHeartSeq() != 0 && brokerNodeCount.getHeartSeq() == brokerNode.getSeq().longValue()){
						brokerNodeCount.increment();
					}else{
						brokerNodeCount.reset();
					}
				}else{
					//对失去心跳的节点，可能在重启，进行计数
					brokerNodeCount.increment();
					//TODO
//					this.nodeMachineDao.disableMachineNode(node, RdosNodeMachineType.SLAVE.getType());
				}
				//做宕机快速恢复的策略，异常宕机时alive=true
				boolean dataMigration = brokerNode.getAlive() && brokerNodeCount.getCount() > OUTAGE_TIMEOUT_COUNT ||
						brokerNodeCount.getCount() > RESTART_TIMEOUT_COUNT;
				if(dataMigration){
					//先置为 false
					this.zkDistributed.disableBrokerHeartNode(node,true);
					//再进行容灾，容灾时还需要再判断一下是否alive，node可能已经恢复
					this.masterNode.dataMigration(node);
					this.zkDistributed.removeBrokerQueueNode(node);
					this.brokerNodeCounts.remove(node);
				}else{
					brokerNodeCount.setBrokerHeartNode(brokerNode);
				}
			}
		}
	}

	private static class BrokerNodeCount{
		private AtomicLong count;
		private BrokerHeartNode brokerHeartNode;
		public BrokerNodeCount(BrokerHeartNode brokerHeartNode){
			this.count = new AtomicLong(0L);
			this.brokerHeartNode  = brokerHeartNode;
		}
		public long getCount() {
			return count.get();
		}
		public void increment() {
			count.incrementAndGet();
		}
		public void reset() {
			count.set(0L);
		}
		public long getHeartSeq() {
			return brokerHeartNode.getSeq().longValue();
		}
		public void setBrokerHeartNode(BrokerHeartNode brokerHeartNode) {
			this.brokerHeartNode = brokerHeartNode;
		}
	}
}
