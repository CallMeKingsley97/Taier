package com.dtstack.engine.master.cache;

import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.dao.EngineJobCacheDao;
import com.dtstack.engine.dao.EngineJobDao;
import com.dtstack.engine.dao.PluginInfoDao;
import com.dtstack.engine.domain.EngineJobCache;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.dtstack.engine.master.resource.ComputeResourceType;
import com.dtstack.engine.master.taskdealer.TaskCheckpointDealer;
import com.dtstack.engine.master.taskdealer.TaskStatusDealer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2018/9/6
 */
@Component
public class ShardCache {

    private final ReentrantLock lock = new ReentrantLock();

    private static final ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(ComputeResourceType.values().length, new CustomThreadFactory("TaskStatusDealer"));

    @Autowired
    private EnvironmentContext environmentContext;

    @Autowired
    private EngineJobCacheDao engineJobCacheDao;

    @Autowired
    private EngineJobDao engineJobDao;

    @Autowired
    private PluginInfoDao pluginInfoDao;

    @Autowired
    private TaskCheckpointDealer taskCheckpointDealer;

    private Map<String, ShardManager> jobResourceShardManager = new ConcurrentHashMap<>();

    private ShardManager getShardManager(String jobId) {
        EngineJobCache engineJobCache = engineJobCacheDao.getOne(jobId);
        return jobResourceShardManager.computeIfAbsent(engineJobCache.getGroupName(), jr -> {
            ShardManager shardManager = new ShardManager();
            TaskStatusDealer taskStatusDealer = new TaskStatusDealer();
            taskStatusDealer.setEngineJobCacheDao(engineJobCacheDao);
            taskStatusDealer.setEngineJobDao(engineJobDao);
            taskStatusDealer.setPluginInfoDao(pluginInfoDao);
            taskStatusDealer.setTaskCheckpointDealer(taskCheckpointDealer);
            taskStatusDealer.setShardManager(shardManager);
            taskStatusDealer.setShardCache(this);
            taskStatusDealer.setJobResource(engineJobCache.getGroupName());
            scheduledService.scheduleWithFixedDelay(
                    taskStatusDealer,
                    0,
                    TaskStatusDealer.INTERVAL,
                    TimeUnit.MILLISECONDS);
            return shardManager;
        });
    }

    public void updateLocalMemTaskStatus(String jobId, Integer status) {
        if (jobId == null || status == null) {
            throw new UnsupportedOperationException();
        }
        //任务只有在提交成功后开始task status轮询并同时checkShard一次
        if (RdosTaskStatus.SUBMITTED.getStatus().equals(status)) {
            checkShard(jobId);
        }
        ShardManager shardManager = getShardManager(jobId);
        String shard = shardManager.getShardName(jobId);
        Lock lock = shardManager.tryLock(shard);
        if (lock != null) {
            lock.lock();
            try {
                shardManager.getShardData(jobId).put(jobId, status);
            } finally {
                lock.unlock();
            }
        }
    }

    public String getJobLocationAddr(String jobId) {
        String addr = null;
        //先查本地
        ShardManager shardManager = getShardManager(jobId);
        if (shardManager.getShardData(jobId).containsKey(jobId)) {
            addr = environmentContext.getLocalAddress();
        }
        //查数据库
        if (addr == null) {
            EngineJobCache jobCache = engineJobCacheDao.getOne(jobId);
            if (jobCache != null) {
                addr = jobCache.getNodeAddress();
            }
        }
        return addr;
    }

    public void checkShard(String jobId) {
        final ReentrantLock createShardLock = this.lock;
        createShardLock.lock();
        try {
            int shardSize = getShardManager(jobId).getShards().size();
            int avg = getShardManager(jobId).getShardDataSize() / shardSize;
            if (avg >= environmentContext.getShardSize()) {
                getShardManager(jobId).createShardNode(shardSize);
            }
        } finally {
            createShardLock.unlock();
        }
    }

}
