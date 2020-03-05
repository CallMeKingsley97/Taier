package com.dtstack.engine.service.task;

import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.exception.ExceptionUtil;
import com.dtstack.engine.common.queue.DelayBlockingQueue;
import com.dtstack.engine.service.node.WorkNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @Auther: jiangjunjie
 * @Date: 2020-03-05
 * @Description:
 */
public class JobCompletedLogDelayDealer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(JobCompletedLogDelayDealer.class);

    private DelayBlockingQueue<CompletedTaskInfo> delayBlockingQueue = new DelayBlockingQueue<CompletedTaskInfo>(1000);
    private ExecutorService taskStatusPool = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(true), new CustomThreadFactory(this.getClass().getSimpleName()));

    public  JobCompletedLogDelayDealer() {
        taskStatusPool.execute(this);
    }

    @Override
    public void run() {
        while (true) {
            try {
                CompletedTaskInfo taskInfo = delayBlockingQueue.take();
                updateJobEngineLog(taskInfo.getJobId(), taskInfo.getJobIdentifier(), taskInfo.getEngineType(), taskInfo.getPluginInfo());
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    public void addTaskToDelayQueue(CompletedTaskInfo taskInfo){
        try {
            delayBlockingQueue.put(taskInfo);
        } catch (InterruptedException e) {
            logger.error("", e);
        }
    }

    private void updateJobEngineLog(String jobId, JobIdentifier jobIdentifier, String engineType, String pluginInfo) {
        try {
            //从engine获取log
            String jobLog = JobClient.getEngineLog(engineType, pluginInfo, jobIdentifier);
            if (jobLog != null){
                WorkNode.getInstance().updateJobEngineLog(jobId, jobLog, engineType);
            }
        } catch (Throwable e){
            String errorLog = ExceptionUtil.getErrorMessage(e);
            logger.error("update JobEngine Log error jobid {} ,error info {}..", jobId, errorLog);
            WorkNode.getInstance().updateJobEngineLog(jobId, errorLog, engineType);
        }
    }
}
