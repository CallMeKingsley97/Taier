package com.dtstack.engine.master.taskdealer;

import com.dtstack.engine.api.domain.EngineJobCache;
import com.dtstack.engine.api.domain.ScheduleJob;
import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.hash.ShardData;
import com.dtstack.engine.common.util.LogCountUtil;
import com.dtstack.engine.dao.EngineJobCacheDao;
import com.dtstack.engine.dao.ScheduleJobDao;
import com.dtstack.engine.dao.PluginInfoDao;
import com.dtstack.engine.master.akka.WorkerOperator;
import com.dtstack.engine.master.bo.CompletedTaskInfo;
import com.dtstack.engine.master.bo.FailedTaskInfo;
import com.dtstack.engine.master.cache.ShardCache;
import com.dtstack.engine.master.cache.ShardManager;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * company: www.dtstack.com
 *
 * @author toutian
 *         create: 2020/01/17
 */
public class TaskStatusDealer implements Runnable {

    private static Logger logger = LoggerFactory.getLogger(TaskStatusDealer.class);

    /**
     * 最大允许查询不到任务信息的次数--超过这个次数任务会被设置为CANCELED
     */
    private final static int NOT_FOUND_LIMIT_TIMES = 300;

    /**
     * 最大允许查询不到的任务信息最久时间
     */
    private final static int NOT_FOUND_LIMIT_INTERVAL = 3 * 60 * 1000;

    public static final long INTERVAL = 2000;
    private final static int MULTIPLES = 5;
    private int logOutput = 0;

    private ApplicationContext applicationContext;
    private ShardManager shardManager;
    private ShardCache shardCache;
    private String jobResource;
    private ScheduleJobDao scheduleJobDao;
    private EngineJobCacheDao engineJobCacheDao;
    private PluginInfoDao pluginInfoDao;
    private TaskCheckpointDealer taskCheckpointDealer;
    private TaskRestartDealer taskRestartDealer;
    private WorkerOperator workerOperator;
    private EnvironmentContext environmentContext;
    private long jobLogDelay;
    private JobCompletedLogDelayDealer jobCompletedLogDelayDealer;

    /**
     * 失败任务的额外处理：当前只是对(失败任务 or 取消任务)继续更新日志或者更新checkpoint
     */
    private Map<String, FailedTaskInfo> failedJobCache = Maps.newConcurrentMap();

    /**
     * 记录job 连续某个状态的频次
     */
    private Map<String, TaskStatusFrequencyDealer> jobStatusFrequency = Maps.newConcurrentMap();

    private ExecutorService taskStatusPool = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(true), new CustomThreadFactory(this.getClass().getSimpleName()));

    @Override
    public void run() {
        try {
            if (logger.isInfoEnabled() && LogCountUtil.count(logOutput++, MULTIPLES)) {
                logger.info("jobResource:{} start again gap:[{} ms]...", jobResource, INTERVAL * MULTIPLES);
            }

            Map<String, ShardData> shards = shardManager.getShards();
            CountDownLatch ctl = new CountDownLatch(shards.size());
            for (Map.Entry<String, ShardData> shardEntry : shards.entrySet()) {
                taskStatusPool.submit(() -> {
                    try {
                        for (Map.Entry<String, Integer> entry : shardEntry.getValue().getView().entrySet()) {
                            try {
                                if (!RdosTaskStatus.needClean(entry.getValue())) {
                                    logger.info("jobId:{} status:{}", entry.getKey(), entry.getValue());
                                    dealJob(entry.getKey());
                                }
                            } catch (Throwable e) {
                                logger.error("", e);
                            }
                        }
                    } catch (Throwable e) {
                        logger.error("{}", e);
                    } finally {
                        ctl.countDown();
                    }
                });
            }
            ctl.await();

            // deal fail task
            for (Map.Entry<String, FailedTaskInfo> failedTaskEntry : failedJobCache.entrySet()) {
                FailedTaskInfo failedTaskInfo = failedTaskEntry.getValue();
                String key = failedTaskEntry.getKey();

                failedTaskInfo.waitClean();
                if (failedTaskInfo.allowClean()) {
                    failedJobCache.remove(key);
                }
            }

        } catch (Throwable e) {
            logger.error("jobResource:{} run error:{}", jobResource, e);
        }
    }


    private void dealJob(String jobId) throws Exception {
        ScheduleJob scheduleJob = scheduleJobDao.getRdosJobByJobId(jobId);
        EngineJobCache engineJobCache = engineJobCacheDao.getOne(jobId);
        if (scheduleJob != null && engineJobCache != null) {
            String engineTaskId = scheduleJob.getEngineJobId();
            String appId = scheduleJob.getApplicationId();
            JobIdentifier jobIdentifier = JobIdentifier.createInstance(engineTaskId, appId, jobId);

            if (StringUtils.isNotBlank(engineTaskId)) {
                String pluginInfoStr = scheduleJob.getPluginInfoId() > 0 ? pluginInfoDao.getPluginInfo(scheduleJob.getPluginInfoId()) : "";
                RdosTaskStatus rdosTaskStatus = workerOperator.getJobStatus(engineJobCache.getEngineType(), pluginInfoStr, jobIdentifier);
                if (rdosTaskStatus != null) {

                    rdosTaskStatus = checkNotFoundStatus(rdosTaskStatus, jobId);
                    Integer status = rdosTaskStatus.getStatus();
                    // 重试状态 先不更新状态
                    boolean isRestart = taskRestartDealer.checkAndRestart(status, jobId, engineTaskId, appId, engineJobCache.getEngineType(), pluginInfoStr);
                    if (isRestart) {
                        return;
                    }

                    shardCache.updateLocalMemTaskStatus(jobId, status);
                    //数据的更新顺序，先更新job_cache，再更新engine_batch_job
                    if (RdosTaskStatus.getStoppedStatus().contains(rdosTaskStatus.getStatus())) {
                        jobLogDelayDealer(jobId, jobIdentifier, engineJobCache.getEngineType(), engineJobCache.getComputeType(), pluginInfoStr);
                    }

                    if (RdosTaskStatus.getStoppedStatus().contains(status)) {
                        jobStatusFrequency.remove(jobId);
                        engineJobCacheDao.delete(jobId);
                    }

                    if (RdosTaskStatus.RUNNING.getStatus().equals(status)) {
                        // deal open checkpoint job
                        long checkpointInterval = taskCheckpointDealer.getCheckpointInterval(jobId);
                        if (checkpointInterval > 0) {
                            taskCheckpointDealer.addCheckpointTaskForQueue(scheduleJob.getComputeType(), jobId, jobIdentifier,
                                    engineJobCache.getEngineType(), pluginInfoStr);
                        }
                    }

                    if (RdosTaskStatus.FAILED.equals(rdosTaskStatus)) {
                        FailedTaskInfo failedTaskInfo = new FailedTaskInfo(scheduleJob.getJobId(), jobIdentifier,
                                engineJobCache.getEngineType(), engineJobCache.getComputeType(), pluginInfoStr);

                        if (!failedJobCache.containsKey(failedTaskInfo.getJobId())) {
                            failedJobCache.put(failedTaskInfo.getJobId(), failedTaskInfo);
                        }
                    }

                    scheduleJobDao.updateJobStatusAndExecTime(jobId, status);
                    logger.info("jobId:{} update job status:{}.", jobId, status);
                }
            }
        } else {
            shardCache.updateLocalMemTaskStatus(jobId, RdosTaskStatus.CANCELED.getStatus());
            scheduleJobDao.updateJobStatusAndExecTime(jobId, RdosTaskStatus.CANCELED.getStatus());
            engineJobCacheDao.delete(jobId);
        }
    }

    private void updateJobEngineLog(String jobId, String jobLog) {
        //写入db
        scheduleJobDao.updateEngineLog(jobId, jobLog);
    }

    private RdosTaskStatus checkNotFoundStatus(RdosTaskStatus taskStatus, String jobId) {
        TaskStatusFrequencyDealer statusPair = updateJobStatusFrequency(jobId, taskStatus.getStatus());
        if (statusPair.getStatus() == RdosTaskStatus.NOTFOUND.getStatus().intValue()) {
            if (statusPair.getNum() >= NOT_FOUND_LIMIT_TIMES ||
                    System.currentTimeMillis() - statusPair.getCreateTime() >= NOT_FOUND_LIMIT_INTERVAL) {
                return RdosTaskStatus.FAILED;
            }
        }
        return taskStatus;
    }


    private void jobLogDelayDealer(String jobId, JobIdentifier jobIdentifier, String engineType, int computeType, String pluginInfo) {
        jobCompletedLogDelayDealer.addCompletedTaskInfo(new CompletedTaskInfo(jobId, jobIdentifier, engineType, computeType, pluginInfo, jobLogDelay));
    }


    /**
     * 更新任务状态频次
     *
     * @param jobId
     * @param status
     * @return
     */
    private TaskStatusFrequencyDealer updateJobStatusFrequency(String jobId, Integer status) {

        TaskStatusFrequencyDealer statusFrequency = jobStatusFrequency.get(jobId);
        statusFrequency = statusFrequency == null ? new TaskStatusFrequencyDealer(status) : statusFrequency;
        if (statusFrequency.getStatus() == status.intValue()) {
            statusFrequency.setNum(statusFrequency.getNum() + 1);
        } else {
            statusFrequency = new TaskStatusFrequencyDealer(status);
        }

        jobStatusFrequency.put(jobId, statusFrequency);
        return statusFrequency;
    }

    public void setShardManager(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    public void setShardCache(ShardCache shardCache) {
        this.shardCache = shardCache;
    }

    public void setJobResource(String jobResource) {
        this.jobResource = jobResource;
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        setBean();
        createLogDelayDealer();
    }

    private void setBean() {
        this.environmentContext = applicationContext.getBean(EnvironmentContext.class);
        this.scheduleJobDao = applicationContext.getBean(ScheduleJobDao.class);
        this.engineJobCacheDao = applicationContext.getBean(EngineJobCacheDao.class);
        this.pluginInfoDao = applicationContext.getBean(PluginInfoDao.class);
        this.taskCheckpointDealer = applicationContext.getBean(TaskCheckpointDealer.class);
        this.taskRestartDealer = applicationContext.getBean(TaskRestartDealer.class);
        this.workerOperator = applicationContext.getBean(WorkerOperator.class);
        this.scheduleJobDao = applicationContext.getBean(ScheduleJobDao.class);
    }

    private void createLogDelayDealer() {
        this.jobCompletedLogDelayDealer = new JobCompletedLogDelayDealer(applicationContext);
        this.jobLogDelay = environmentContext.getJobLogDelay();
    }
}