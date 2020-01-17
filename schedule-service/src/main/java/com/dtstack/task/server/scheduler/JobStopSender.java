package com.dtstack.task.server.scheduler;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dtstack.dtcenter.common.engine.EngineSend;
import com.dtstack.dtcenter.common.enums.EJobType;
import com.dtstack.dtcenter.common.enums.EngineType;
import com.dtstack.task.common.TaskThreadFactory;
import com.dtstack.task.common.exception.ErrorCode;
import com.dtstack.task.common.exception.RdosDefineException;
import com.dtstack.task.domain.BatchJob;
import com.dtstack.task.domain.BatchTaskShade;
import com.dtstack.task.server.impl.BatchTaskShadeService;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author yuebai
 * @date 2019-07-09
 */
@Component
public class JobStopSender implements InitializingBean, DisposableBean, Runnable {

    private final Logger logger = LoggerFactory.getLogger(JobStopSender.class);

    private volatile boolean run = true;

    @Autowired
    private EngineSend engineSend;

    @Autowired
    private BatchTaskShadeService batchTaskShadeService;

    private ExecutorService jobStopSenderExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new TaskThreadFactory("JobStopSender"));

    private ArrayBlockingQueue<StoppedJob> stopJobQueue = new ArrayBlockingQueue<StoppedJob>(1000);

    @Override
    public void run() {
        logger.info("JobStopSender thread is start...");
        while (run) {
            StoppedJob stoppedJob = null;
            try {
                stoppedJob = stopJobQueue.take();
                this.processStoppedJob(stoppedJob);
            } catch (Exception e) {
                logger.error("stop job:{} error:{}", stoppedJob, e);
            }
        }
        logger.info("JobStopSender thread is shutdown...");
    }

    public boolean addStopJob(List<BatchJob> jobs, Long dtuicTenantId, Integer appType) {
        if (CollectionUtils.isEmpty(jobs)) {
            return true;
        }
        StoppedJob stoppedJob = new StoppedJob(jobs, dtuicTenantId, appType);
        try {
            return stopJobQueue.add(stoppedJob);
        } catch (IllegalStateException e) {
            throw new RdosDefineException(ErrorCode.UNSUPPORTED_OPERATION);
        }
    }

    private void stop() {
        this.run = false;
    }

    private void processStoppedJob(StoppedJob stoppedJob) throws Exception {
        logger.info("appType:{} dtuicTenantId:{} stop job data size:{}", stoppedJob.getAppType(), stoppedJob.getDtuicTenantId(), stoppedJob.getJobs().size());

        List<Long> taskIds = stoppedJob.getJobs()
                .parallelStream()
                .map(BatchJob::getTaskId)
                .collect(Collectors.toList());

        Map<Long, List<BatchTaskShade>> taskShades =
                batchTaskShadeService.getTaskByIds(taskIds, stoppedJob.getAppType())
                        .stream()
                        .collect(Collectors.groupingBy(BatchTaskShade::getTaskId));

        JSONArray jsonArray = new JSONArray();
        for (BatchJob job : stoppedJob.getJobs()) {
            List<BatchTaskShade> shades = taskShades.get(job.getTaskId());

            if (CollectionUtils.isNotEmpty(shades)) {
                BatchTaskShade batchTask = shades.get(0);
                JSONObject params = new JSONObject();
                params.put("engineType", EngineType.getEngineName(batchTask.getEngineType()));
                params.put("taskId", job.getJobId());
                params.put("computeType", batchTask.getComputeType());
                params.put("taskType", batchTask.getTaskType());
                params.put("tenantId", stoppedJob.getDtuicTenantId());
                if (batchTask.getTaskType().equals(EJobType.DEEP_LEARNING.getVal())) {
                    params.put("engineType", EngineType.Learning.getEngineName());
                    params.put("taskType", EJobType.SPARK_PYTHON.getVal());
                } else if (batchTask.getTaskType().equals(EJobType.PYTHON.getVal()) || batchTask.getTaskType().equals(EJobType.SHELL.getVal())) {
                    params.put("engineType", EngineType.DtScript.getEngineName());
                    params.put("taskType", EJobType.SPARK_PYTHON.getVal());
                }
                jsonArray.add(params);
            }
        }

        JSONObject sendData = new JSONObject();
        sendData.put("jobs", jsonArray);

        engineSend.stopTask(sendData.toJSONString(), null, 2);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        jobStopSenderExecutor.submit(this);
    }

    @Override
    public void destroy() throws Exception {
        stop();
    }

    class StoppedJob {
        List<BatchJob> jobs;
        Long dtuicTenantId;
        Integer appType;

        private StoppedJob(List<BatchJob> jobs, Long dtuicTenantId, Integer appType) {
            this.jobs = jobs;
            this.dtuicTenantId = dtuicTenantId;
            this.appType = appType;
        }

        public List<BatchJob> getJobs() {
            return jobs;
        }

        public Long getDtuicTenantId() {
            return dtuicTenantId;
        }

        public Integer getAppType() {
            return appType;
        }

        @Override
        public String toString() {
            return "StoppedJob{" +
                    "jobs=" + jobs +
                    ", dtuicTenantId=" + dtuicTenantId +
                    ", appType=" + appType +
                    '}';
        }
    }


}
