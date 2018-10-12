package com.dtstack.rdos.engine.execution.base;

import com.dtstack.rdos.engine.execution.base.enums.RdosTaskStatus;
import com.dtstack.rdos.engine.execution.base.pojo.EngineResourceInfo;
import com.dtstack.rdos.engine.execution.base.pojo.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 发送具体任务线程
 * Date: 2017/11/27
 * Company: www.dtstack.com
 *
 * @author xuchao
 */

public class JobSubmitProcessor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(JobSubmitProcessor.class);

    private JobClient jobClient;
    private Handler handler;

    public JobSubmitProcessor(JobClient jobClient, Handler handler) {
        this.jobClient = jobClient;
        this.handler = handler;
    }

    @Override
    public void run() {

        JobResult jobResult = null;
        try {
            jobClient.doStatusCallBack(RdosTaskStatus.WAITCOMPUTE.getStatus());
            IClient clusterClient = ClientCache.getInstance().getClient(jobClient.getEngineType(), jobClient.getPluginInfo());

            if (clusterClient == null) {
                jobResult = JobResult.createErrorResult("client type (" + jobClient.getEngineType() + ") don't found.");
                addToTaskListener(jobClient, jobResult);
                return;
            }

            EngineResourceInfo resourceInfo = clusterClient.getAvailSlots();

            if (resourceInfo != null && resourceInfo.judgeSlots(jobClient)) {
                if (logger.isInfoEnabled()) {
                    logger.info("--------submit job:{} to engine start----.", jobClient.toString());
                }

                jobClient.doStatusCallBack(RdosTaskStatus.SUBMITTED.getStatus());

                jobResult = clusterClient.submitJob(jobClient);

                if (logger.isInfoEnabled()) {
                    logger.info("submit job result is:{}.", jobResult);
                }

                String jobId = jobResult.getData(JobResult.JOB_ID_KEY);
                jobClient.setEngineTaskId(jobId);
                addToTaskListener(jobClient, jobResult);
                if (logger.isInfoEnabled()) {
                    logger.info("--------submit job:{} to engine end----", jobClient.getTaskId());
                }
            } else {
                logger.info(" jobId:{} engineType:{} judgeSlots result is false", jobClient.getTaskId(), jobClient.getEngineType());
                jobClient.doStatusCallBack(RdosTaskStatus.WAITENGINE.getStatus());
                handler.handle();
            }
        } catch (Throwable e) {
            //捕获未处理异常,防止跳出执行线程
            jobClient.setEngineTaskId(null);
            jobResult = JobResult.createErrorResult(e);
            addToTaskListener(jobClient, jobResult);
            logger.error("get unexpected exception", e);
        }
    }

    private void addToTaskListener(JobClient jobClient, JobResult jobResult) {
        jobClient.setJobResult(jobResult);
        JobSubmitExecutor.getInstance().addJobIntoTaskListenerQueue(jobClient);//添加触发读取任务状态消息
    }

    public interface Handler {

        /**
         * Something has happened, so handle it.
         */
        void handle();
    }
}
