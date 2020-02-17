package com.dtstack.engine.master.impl;

import com.dtstack.engine.common.pojo.StoppedJob;
import com.dtstack.engine.common.util.PublicUtil;
import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.pojo.ParamAction;
import com.dtstack.engine.common.queue.DelayBlockingQueue;
import com.dtstack.engine.dao.EngineJobCacheDao;
import com.dtstack.engine.dao.EngineJobDao;
import com.dtstack.engine.dao.EngineJobStopRecordDao;
import com.dtstack.engine.domain.EngineJobCache;
import com.dtstack.engine.domain.EngineJob;
import com.dtstack.engine.domain.EngineJobStopRecord;
import com.dtstack.engine.common.enums.RequestStart;
import com.dtstack.engine.common.enums.StoppedStatus;
import com.dtstack.engine.master.WorkNode;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.dtstack.engine.master.send.HttpSendClient;
import com.dtstack.engine.master.cache.ShardCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 任务停止消息
 * 不需要区分是不是主节点才启动处理线程
 * Date: 2018/1/22
 * Company: www.dtstack.com
 *
 * @author xuchao
 */
@Component
public class JobStopQueue {

    private static final Logger logger = LoggerFactory.getLogger(JobStopQueue.class);

    @Autowired
    private ShardCache shardCache;

    private DelayBlockingQueue<StoppedJob<ParamAction>> stopJobQueue = new DelayBlockingQueue<StoppedJob<ParamAction>>(1000);

    @Autowired
    private EngineJobCacheDao engineJobCacheDao;

    @Autowired
    private EngineJobStopRecordDao engineJobStopRecordDao;

    @Autowired
    private EngineJobDao engineJobDao;

    @Autowired
    private EnvironmentContext environmentContext;

    @Autowired
    private WorkNode workNode;

    @Autowired
    private JobStopAction jobStopAction;

    private static final int WAIT_INTERVAL = 1000;

    private AtomicLong startId = new AtomicLong(0);

    private ExecutorService simpleES = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new CustomThreadFactory("stopProcessor"));

    private ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(1, new CustomThreadFactory("acquire-stopJob"));

    private StopProcessor stopProcessor = new StopProcessor();

    public void start() {
        if (simpleES.isShutdown()) {
            simpleES = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new CustomThreadFactory("stopProcessor"));
            stopProcessor.reStart();
        }

        simpleES.submit(stopProcessor);

        scheduledService.scheduleAtFixedRate(
                new AcquireStopJob(),
                WAIT_INTERVAL,
                WAIT_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        stopProcessor.stop();
        simpleES.shutdownNow();
    }

    public boolean tryPutStopJobQueue(ParamAction paramAction) {
        return stopJobQueue.tryPut(new StoppedJob<ParamAction>(paramAction, environmentContext.getJobStoppedRetry(), environmentContext.getJobRestartDelay()));
    }

    private class AcquireStopJob implements Runnable {
        @Override
        public void run() {
            long tmpStartId = 0;
            while (true) {
                try {
                    tmpStartId = startId.get();
                    //根据条件判断是否有数据存在
                    List<EngineJobStopRecord> jobStopRecords = engineJobStopRecordDao.listStopJob(startId.get());
                    if (jobStopRecords.isEmpty()) {
                        break;
                    }
                    //使用乐观锁防止多节点重复停止任务
                    Iterator<EngineJobStopRecord> it = jobStopRecords.iterator();
                    while (it.hasNext()) {
                        EngineJobStopRecord jobStopRecord = it.next();
                        startId.set(jobStopRecord.getId());
                        //已经被修改过version的任务代表其他节点正在处理，可以忽略
                        Integer update = engineJobStopRecordDao.updateVersion(jobStopRecord.getId(), jobStopRecord.getVersion());
                        if (update != 1) {
                            it.remove();
                        }
                    }
                    //经乐观锁判断，经过remove后所剩下的数据
                    if (jobStopRecords.isEmpty()) {
                        break;
                    }
                    List<String> jobIds = jobStopRecords.stream().map(job -> job.getTaskId()).collect(Collectors.toList());
                    List<EngineJobCache> jobCaches = engineJobCacheDao.getByJobIds(jobIds);

                    //为了下面兼容异常状态的任务停止
                    Map<String, EngineJobCache> jobCacheMap = new HashMap<>(jobCaches.size());
                    for (EngineJobCache jobCache : jobCaches) {
                        jobCacheMap.put(jobCache.getJobId(), jobCache);
                    }

                    for (EngineJobStopRecord jobStopRecord : jobStopRecords) {
                        EngineJobCache jobCache = jobCacheMap.get(jobStopRecord.getTaskId());
                        if (jobCache != null) {
                            //停止任务的时效性，发起停止操作要比任务存入jobCache表的时间要迟
                            if (jobCache.getGmtCreate().after(jobStopRecord.getGmtCreate())) {
                                engineJobStopRecordDao.delete(jobStopRecord.getId());
                                continue;
                            }

                            ParamAction paramAction = PublicUtil.jsonStrToObject(jobCache.getJobInfo(), ParamAction.class);
                            paramAction.setStopJobId(jobStopRecord.getId());
                            workNode.fillJobClientEngineId(paramAction);
                            boolean res = JobStopQueue.this.processStopJob(paramAction);
                            if (!res) {
                                //重置version等待下一次轮询stop
                                engineJobStopRecordDao.resetRecord(jobStopRecord.getId());
                                startId.set(tmpStartId);
                            }

                        } else {
                            logger.warn("[Unnormal Job] jobId:{}", jobStopRecord.getTaskId());
                            //jobcache表没有记录，可能任务已经停止。在update表时增加where条件不等于stopped
                            engineJobDao.updateTaskStatusNotStopped(jobStopRecord.getTaskId(), RdosTaskStatus.CANCELED.getStatus(), RdosTaskStatus.getStoppedStatus());
                            shardCache.updateLocalMemTaskStatus(jobStopRecord.getTaskId(), RdosTaskStatus.CANCELED.getStatus());
                            engineJobStopRecordDao.delete(jobStopRecord.getId());
                        }
                    }

                    Thread.sleep(500);
                } catch (Throwable e) {
                    logger.error("when acquire stop jobs happens error:{}", e);
                }
            }
        }
    }

    private boolean processStopJob(ParamAction paramAction) {
        try {
            String jobId = paramAction.getTaskId();
            if (!checkCanStop(jobId, paramAction.getComputeType())) {
                return true;
            }

            String address = shardCache.getJobLocationAddr(jobId);
            if (address == null) {
                logger.info("can't get info from engine zk for jobId" + jobId);
                return true;
            }

            if (!address.equals(environmentContext.getLocalAddress())) {
                paramAction.setRequestStart(RequestStart.NODE.getStart());
                logger.info("action stop jobId:{} to worker node addr:{}." + paramAction.getTaskId(), address);
                Boolean res = HttpSendClient.actionStopJobToWorker(address, paramAction);
                if (res != null) {
                    return res;
                }
            }
            stopJobQueue.put(new StoppedJob<ParamAction>(paramAction, environmentContext.getJobStoppedRetry(), environmentContext.getJobRestartDelay()));
            return true;
        } catch (Throwable e) {
            logger.error("processStopJob happens error, element:{}", paramAction, e);
            //停止发生错误时，需要避免死循环进行停止
            return true;
        }
    }

    /**
     * 判断任务是否可停止
     *
     * @param taskId
     * @param computeType
     * @return
     */
    private boolean checkCanStop(String taskId, Integer computeType) {
    	EngineJob rdosEngineBatchJob = engineJobDao.getRdosJobByJobId(taskId);
        Integer sta = rdosEngineBatchJob.getStatus().intValue();
        return RdosTaskStatus.getCanStopStatus().contains(sta);
    }

    private class StopProcessor implements Runnable {

        private boolean run = true;

        @Override
        public void run() {

            logger.info("job stop process thread is start...");

            while (run) {
                try {
                    StoppedJob<ParamAction> stoppedJob = stopJobQueue.take();
                    StoppedStatus stoppedStatus = jobStopAction.stopJob(stoppedJob.getJob());
                    switch (stoppedStatus) {
                        case STOPPED:
                        case MISSED:
                            break;
                        case STOPPING:
                        case RETRY:
                            if (!stoppedJob.isRetry()) {
                                logger.warn("jobId:{} retry limited!", stoppedJob.getJob().getTaskId());
                                break;
                            }
                            stoppedJob.incrCount();
                            if (StoppedStatus.STOPPING == stoppedStatus) {
                                stoppedJob.reset(environmentContext.getJobRestartDelay() * 20);
                            } else if (StoppedStatus.RETRY == stoppedStatus) {
                                stoppedJob.reset(environmentContext.getJobRestartDelay());
                            }
                            stopJobQueue.put(stoppedJob);
                            continue;
                        default:
                    }
                    engineJobStopRecordDao.delete(stoppedJob.getJob().getStopJobId());
                } catch (Exception e) {
                    logger.error("", e);
                }
            }

            logger.info("job stop process thread is shutdown...");

        }

        public void stop() {
            this.run = false;
        }

        public void reStart() {
            this.run = true;
        }
    }
}
