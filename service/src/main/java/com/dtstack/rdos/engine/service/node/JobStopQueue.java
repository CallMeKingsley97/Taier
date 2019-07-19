package com.dtstack.rdos.engine.service.node;

import com.dtstack.rdos.common.config.ConfigParse;
import com.dtstack.rdos.common.util.PublicUtil;
import com.dtstack.rdos.engine.execution.base.CustomThreadFactory;
import com.dtstack.rdos.engine.execution.base.enums.ComputeType;
import com.dtstack.rdos.engine.execution.base.enums.RdosTaskStatus;
import com.dtstack.rdos.engine.execution.base.pojo.ParamAction;
import com.dtstack.rdos.engine.execution.base.queue.DelayBlockingQueue;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineBatchJobDAO;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineJobCacheDAO;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineJobStopRecordDAO;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineStreamJobDAO;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineBatchJob;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineJobCache;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineJobStopRecord;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineStreamJob;
import com.dtstack.rdos.engine.service.enums.RequestStart;
import com.dtstack.rdos.engine.service.enums.StoppedStatus;
import com.dtstack.rdos.engine.service.send.HttpSendClient;
import com.dtstack.rdos.engine.service.util.TaskIdUtil;
import com.dtstack.rdos.engine.service.zk.ZkDistributed;
import com.dtstack.rdos.engine.service.zk.cache.ZkLocalCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
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

public class JobStopQueue {

    private static final Logger LOG = LoggerFactory.getLogger(JobStopQueue.class);

    private ZkDistributed zkDistributed = ZkDistributed.getZkDistributed();

    private ZkLocalCache zkLocalCache = ZkLocalCache.getInstance();

    private DelayBlockingQueue<StoppedJob<ParamAction>> stopJobQueue = new DelayBlockingQueue<StoppedJob<ParamAction>>(1000);

    private RdosEngineJobCacheDAO jobCacheDAO = new RdosEngineJobCacheDAO();

    private RdosEngineJobStopRecordDAO jobStopRecordDAO = new RdosEngineJobStopRecordDAO();

    private RdosEngineBatchJobDAO batchJobDAO = new RdosEngineBatchJobDAO();

    private RdosEngineStreamJobDAO streamJobDAO = new RdosEngineStreamJobDAO();

    private WorkNode workNode;

    private JobStopAction jobStopAction;

    private final int jobStoppedRetry;
    /**
     * delay 3 second
     */
    private final long jobStoppedDelay;

    private static final int WAIT_INTERVAL = 1000;

    private AtomicLong startId = new AtomicLong(0);

    private ExecutorService simpleES = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new CustomThreadFactory("stopProcessor"));

    private ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(1, new CustomThreadFactory("acquire-stopJob"));

    private StopProcessor stopProcessor = new StopProcessor();

    public JobStopQueue(WorkNode workNode) {
        this.workNode = workNode;
        this.jobStopAction = new JobStopAction(workNode);
        this.jobStoppedRetry = ConfigParse.getJobStoppedRetry();
        this.jobStoppedDelay = ConfigParse.getJobStoppedDelay();
    }

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
        return stopJobQueue.tryPut(new StoppedJob<ParamAction>(paramAction));
    }

    private class AcquireStopJob implements Runnable {
        @Override
        public void run() {
            long tmpStartId = 0;
            while (true) {
                try {
                    tmpStartId = startId.get();
                    //根据条件判断是否有数据存在
                    List<RdosEngineJobStopRecord> jobStopRecords = jobStopRecordDAO.listStopJob(startId.get());
                    if (jobStopRecords.isEmpty()) {
                        break;
                    }
                    //使用乐观锁防止多节点重复停止任务
                    Iterator<RdosEngineJobStopRecord> it = jobStopRecords.iterator();
                    while (it.hasNext()) {
                        RdosEngineJobStopRecord jobStopRecord = it.next();
                        startId.set(jobStopRecord.getId());
                        //已经被修改过version的任务代表其他节点正在处理，可以忽略
                        Integer update = jobStopRecordDAO.updateVersion(jobStopRecord.getId(), jobStopRecord.getVersion());
                        if (update != 1) {
                            it.remove();
                        }
                    }
                    //经乐观锁判断，经过remove后所剩下的数据
                    if (jobStopRecords.isEmpty()) {
                        break;
                    }
                    List<String> jobIds = jobStopRecords.stream().map(job -> job.getTaskId()).collect(Collectors.toList());
                    List<RdosEngineJobCache> jobCaches = jobCacheDAO.getJobByIds(jobIds);

                    //为了下面兼容异常状态的任务停止
                    Map<String, RdosEngineJobCache> jobCacheMap = new HashMap<>(jobCaches.size());
                    for (RdosEngineJobCache jobCache : jobCaches) {
                        jobCacheMap.put(jobCache.getJobId(), jobCache);
                    }

                    for (RdosEngineJobStopRecord jobStopRecord : jobStopRecords) {
                        RdosEngineJobCache jobCache = jobCacheMap.get(jobStopRecord.getTaskId());
                        if (jobCache != null) {
                            //停止任务的时效性，发起停止操作要比任务存入jobCache表的时间要迟
                            if (jobCache.getGmtCreate().after(jobStopRecord.getGmtCreate())) {
                                jobStopRecordDAO.delete(jobStopRecord.getId());
                                continue;
                            }

                            ParamAction paramAction = PublicUtil.jsonStrToObject(jobCache.getJobInfo(), ParamAction.class);
                            paramAction.setStopJobId(jobStopRecord.getId());
                            workNode.fillJobClientEngineId(paramAction);
                            boolean res = JobStopQueue.this.processStopJob(paramAction);
                            if (!res) {
                                //重置version等待下一次轮询stop
                                jobStopRecordDAO.resetRecord(jobStopRecord.getId());
                                startId.set(tmpStartId);
                            }

                        } else {
                            LOG.warn("[Unnormal Job] jobId:{}", jobStopRecord.getTaskId());
                            //jobcache表没有记录，可能任务已经停止。在update表时增加where条件不等于stopped
                            if (ComputeType.STREAM.getType().equals(jobStopRecord.getComputeType())) {
                                streamJobDAO.updateTaskStatusNotStopped(jobStopRecord.getTaskId(), RdosTaskStatus.CANCELED.getStatus(), RdosTaskStatus.getStoppedStatus());
                            } else {
                                batchJobDAO.updateTaskStatusNotStopped(jobStopRecord.getTaskId(), RdosTaskStatus.CANCELED.getStatus(), RdosTaskStatus.getStoppedStatus());
                            }
                            jobStopRecordDAO.delete(jobStopRecord.getId());
                        }
                    }

                    Thread.sleep(500);
                } catch (Throwable e) {
                    LOG.error("when acquire stop jobs happens error:{}", e);
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

            //在zk上查找任务所在的worker-address
            Integer computeType = paramAction.getComputeType();
            String zkTaskId = TaskIdUtil.getZkTaskId(computeType, paramAction.getEngineType(), jobId);
            String address = zkLocalCache.getJobLocationAddr(zkTaskId);
            if (address == null) {
                LOG.info("can't get info from engine zk for jobId" + jobId);
                return true;
            }

            if (!address.equals(zkDistributed.getLocalAddress())) {
                paramAction.setRequestStart(RequestStart.NODE.getStart());
                LOG.info("action stop job:{} to worker node addr:{}." + paramAction.getTaskId(), address);
                Boolean res = HttpSendClient.actionStopJobToWorker(address, paramAction);
                if (res != null) {
                    return res;
                }
            }
            stopJobQueue.put(new StoppedJob<ParamAction>(paramAction));
            return true;
        } catch (Throwable e) {
            LOG.error("processStopJob happens error, element:{}", e);
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

        Integer sta;
        if (ComputeType.BATCH.getType().equals(computeType)) {
            RdosEngineBatchJob rdosEngineBatchJob = batchJobDAO.getRdosTaskByTaskId(taskId);
            sta = rdosEngineBatchJob.getStatus().intValue();
        } else if (ComputeType.STREAM.getType().equals(computeType)) {
            RdosEngineStreamJob rdosEngineStreamJob = streamJobDAO.getRdosTaskByTaskId(taskId);
            sta = rdosEngineStreamJob.getStatus().intValue();
        } else {
            LOG.error("invalid compute type:{}", computeType);
            return false;
        }

        return RdosTaskStatus.getCanStopStatus().contains(sta);
    }

    private class StopProcessor implements Runnable {

        private boolean run = true;

        @Override
        public void run() {

            LOG.info("job stop process thread is start...");

            while (run) {
                try {
                    StoppedJob<ParamAction> stoppedJob = stopJobQueue.take();
                    StoppedStatus stoppedStatus = jobStopAction.stopJob(stoppedJob.job);
                    switch (stoppedStatus) {
                        case STOPPED:
                        case MISSED:
                            break;
                        case STOPPING:
                            if (!stoppedJob.isRetry()) {
                                LOG.warn("job:{} retry limited!", stoppedJob.job.getTaskId());
                                break;
                            }
                            stoppedJob.incrCount();
                            stoppedJob.reset();
                            stopJobQueue.put(stoppedJob);
                        default:
                    }
                    jobStopRecordDAO.delete(stoppedJob.job.getStopJobId());
                } catch (Exception e) {
                    LOG.error("", e);
                }
            }

            LOG.info("job stop process thread is shutdown...");

        }

        public void stop() {
            this.run = false;
        }

        public void reStart() {
            this.run = true;
        }
    }

    private class StoppedJob<T> implements Delayed {
        private int count;
        private T job;
        private int retry;
        private long now;
        private long expired;

        private StoppedJob(T job) {
            this.job = job;
            this.retry = jobStoppedRetry;
            this.now = System.currentTimeMillis();
            this.expired = now + jobStoppedDelay;
        }

        private void incrCount() {
            count += 1;
        }

        private boolean isRetry() {
            return retry == 0 || count <= retry;
        }

        private void reset() {
            this.now = System.currentTimeMillis();
            this.expired = now + jobStoppedDelay;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(this.expired - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return (int) (this.getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
