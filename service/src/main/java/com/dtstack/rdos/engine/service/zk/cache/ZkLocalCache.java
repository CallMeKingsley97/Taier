package com.dtstack.rdos.engine.service.zk.cache;

import com.dtstack.rdos.commom.exception.RdosException;
import com.dtstack.rdos.common.config.ConfigParse;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.dtstack.rdos.engine.execution.base.enums.RdosTaskStatus;
import com.dtstack.rdos.engine.execution.base.queue.ClusterQueueInfo;
import com.dtstack.rdos.engine.execution.base.queue.GroupInfo;
import com.dtstack.rdos.engine.execution.base.queue.OrderLinkedBlockingQueue;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineJobCacheDAO;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineJobCache;
import com.dtstack.rdos.engine.service.node.GroupPriorityQueue;
import com.dtstack.rdos.engine.service.node.WorkNode;
import com.dtstack.rdos.engine.service.util.TaskIdUtil;
import com.dtstack.rdos.engine.service.zk.ZkDistributed;
import com.dtstack.rdos.engine.service.zk.ZkShardManager;
import com.dtstack.rdos.engine.service.zk.data.BrokerDataNode;
import com.dtstack.rdos.engine.service.zk.data.BrokerDataShard;
import com.google.common.collect.Maps;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2018/9/6
 */
public class ZkLocalCache implements Closeable {

    private volatile BrokerDataNode localDataCache;

    private String localAddress;
    private int distributeZkWeight;
    private int distributeQueueWeight;
    private int distributeDeviation;
    private int perShardSize;
    private volatile Map<String, Integer> zkDataSizeCache;
    private static ZkLocalCache zkLocalCache = new ZkLocalCache();

    public static ZkLocalCache getInstance() {
        return zkLocalCache;
    }

    private RdosEngineJobCacheDAO engineJobCacheDao = new RdosEngineJobCacheDAO();

    private final ReentrantLock lock = new ReentrantLock();

    private WorkNode workNode;
    private ClusterQueueInfo clusterQueueInfo = ClusterQueueInfo.getInstance();
    private ZkShardManager zkShardManager = ZkShardManager.getInstance();
    private LocalCacheSyncZkListener localCacheSyncZkListener;

    private ZkLocalCache() {
    }

    public void init(ZkDistributed zkDistributed) {
        localAddress = zkDistributed.getLocalAddress();
        localDataCache = zkDistributed.initMemTaskStatus();
        distributeQueueWeight = ConfigParse.getTaskDistributeQueueWeight();
        distributeZkWeight = ConfigParse.getTaskDistributeZkWeight();
        distributeDeviation = ConfigParse.getTaskDistributeDeviation();
        perShardSize = ConfigParse.getShardSize();
        zkShardManager.init(zkDistributed);
        if (distributeZkWeight > 0) {
            ZkSyncLocalCacheListener zkSyncLocalCacheListener = new ZkSyncLocalCacheListener();
        }
    }

    public void updateLocalMemTaskStatus(String zkTaskId, Integer status) {
        if (zkTaskId == null || status == null) {
            throw new UnsupportedOperationException();
        }
        if (RdosTaskStatus.WAITCOMPUTE.getStatus().equals(status)){
            checkShard();
        }
        String shard = localDataCache.getShard(zkTaskId);
        Lock lock = zkShardManager.tryLock(shard);
        lock.lock();
        try {
            localDataCache.getShards().get(shard).put(zkTaskId, status.byteValue());
        } finally {
            lock.unlock();
        }
    }

    public BrokerDataNode getBrokerData() {
        return localDataCache;
    }


    public String getJobLocationAddr(String zkTaskId) {
        String addr = null;
        //先查本地
        String shard = localDataCache.getShard(zkTaskId);
        if (localDataCache.getShards().get(shard).containsKey(zkTaskId)) {
            addr = localAddress;
        }
        //查数据库
        if (addr==null){
            String jobId = TaskIdUtil.getTaskId(zkTaskId);
            RdosEngineJobCache jobCache = engineJobCacheDao.getJobById(jobId);
            addr = jobCache.getNodeAddress();
        }
        return addr;
    }

    /**
     * 选择节点间（队列负载+已提交任务 加权值）+ 误差 符合要求的node，做任务分发
     */
    public String getDistributeNode(String engineType, String groupName, List<String> excludeNodes) {
        if (clusterQueueInfo.isEmpty()) {
            return localAddress;
        }

        ClusterQueueInfo.EngineTypeQueueInfo engineTypeQueueInfo = clusterQueueInfo.getEngineTypeQueueInfo(engineType);
        if (engineTypeQueueInfo == null) {
            return localAddress;
        }

        GroupPriorityQueue groupPriorityQueue = workNode.getEngineTypeQueue(engineType);
        if (groupPriorityQueue == null) {
            throw new RdosException("not support engineType:" + engineType);
        }
        Map<String, OrderLinkedBlockingQueue<JobClient>> groupQueues = groupPriorityQueue.getGroupPriorityQueueMap();
        OrderLinkedBlockingQueue queue = groupQueues.get(groupName);
        int localQueueSize = queue == null ? 0 : queue.size();
        Map<String, Integer> otherQueueInfoMap = Maps.newHashMap();
        for (Map.Entry<String, ClusterQueueInfo.GroupQueueInfo> zkInfoEntry : engineTypeQueueInfo.getGroupQueueInfoMap().entrySet()) {
            ClusterQueueInfo.GroupQueueInfo groupQueueZkInfo = zkInfoEntry.getValue();
            Map<String, GroupInfo> remoteQueueInfo = groupQueueZkInfo.getGroupInfo();
            GroupInfo groupInfo = remoteQueueInfo.getOrDefault(groupName, new GroupInfo());
            otherQueueInfoMap.put(zkInfoEntry.getKey(), groupInfo.getSize());
        }

        String node = null;
        int minWeight = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> queueEntry : otherQueueInfoMap.entrySet()) {
            if (excludeNodes.contains(queueEntry.getKey())) {
                continue;
            }
            int queueSize = queueEntry.getValue();
            int weight = queueSize * distributeQueueWeight + getZkDataSize(queueEntry.getKey()) * distributeZkWeight;
            if (minWeight > weight) {
                minWeight = weight;
                node = queueEntry.getKey();
            }
        }
        int localWeight = localQueueSize * distributeQueueWeight + getZkDataSize(localAddress) * distributeZkWeight;
        if (localWeight - minWeight <= distributeDeviation) {
            return localAddress;
        }
        return node;
    }

    public Map<String, Integer> getZkDataSizeCache() {
        return zkDataSizeCache;
    }

    public void setZkDataSizeCache(Map<String, Integer> zkDataSizeCache) {
        this.zkDataSizeCache = zkDataSizeCache;
    }

    public int getZkDataSize(String node){
        if (zkDataSizeCache!=null){
            return zkDataSizeCache.getOrDefault(node, 0);
        }
        return 0;
    }

    /**
     * 任务状态轮询的时候注意并发删除操作，CopyOnWrite
     */
    public Map<String, BrokerDataShard> cloneShardData() {
        return new HashMap<>(localDataCache.getShards());
    }

    public void cover(Map<String, Integer> zkSize) {
        zkDataSizeCache = zkSize;
    }

    public void checkShard() {
        final ReentrantLock createShardLock = this.lock;
        createShardLock.lock();
        try {
            int shardSize = localDataCache.getShards().size();
            int avg = localDataCache.getDataSize() / localDataCache.getShards().size();
            if (avg >= perShardSize) {
                zkShardManager.createShardNode(shardSize);
            }
        } finally {
            createShardLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        if (localCacheSyncZkListener != null) {
            localCacheSyncZkListener.run();
        }
    }

    public void setWorkNode(WorkNode workNode) {
        this.workNode = workNode;
    }

    public void setLocalCacheSyncZkListener(LocalCacheSyncZkListener localCacheSyncZkListener) {
        this.localCacheSyncZkListener = localCacheSyncZkListener;
    }

}
