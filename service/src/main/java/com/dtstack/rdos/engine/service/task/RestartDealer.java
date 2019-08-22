package com.dtstack.rdos.engine.service.task;

import com.dtstack.rdos.common.util.PublicUtil;
import com.dtstack.rdos.engine.execution.base.ClientCache;
import com.dtstack.rdos.engine.execution.base.IClient;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.dtstack.rdos.engine.execution.base.enums.*;
import com.dtstack.rdos.engine.execution.base.pojo.ParamAction;
import com.dtstack.rdos.engine.execution.base.restart.IRestartStrategy;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineBatchJobDAO;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineBatchJobRetryDAO;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineJobCacheDAO;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineStreamJobDAO;
import com.dtstack.rdos.engine.service.db.dao.RdosEngineStreamJobRetryDAO;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineBatchJob;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineBatchJobRetry;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineJobCache;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineStreamJob;
import com.dtstack.rdos.engine.service.db.dataobject.RdosEngineStreamJobRetry;
import com.dtstack.rdos.engine.service.node.WorkNode;
import com.dtstack.rdos.engine.service.util.TaskIdUtil;
import com.dtstack.rdos.engine.service.zk.cache.ZkLocalCache;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;


/**
 * 注意如果是由于资源不足导致的任务失败应该减慢发送速度
 * Date: 2018/3/22
 * Company: www.dtstack.com
 * @author xuchao
 */

public class RestartDealer {

    private static final Logger LOG = LoggerFactory.getLogger(RestartDealer.class);

    private static final Integer SUBMIT_INTERVAL = 2 * 60 * 1000;

    private RdosEngineJobCacheDAO engineJobCacheDAO = new RdosEngineJobCacheDAO();

    private RdosEngineBatchJobDAO engineBatchJobDAO = new RdosEngineBatchJobDAO();

    private RdosEngineBatchJobRetryDAO engineBatchJobRetryDAO = new RdosEngineBatchJobRetryDAO();

    private RdosEngineStreamJobDAO engineStreamJobDAO = new RdosEngineStreamJobDAO();

    private RdosEngineStreamJobRetryDAO engineStreamJobRetryDAO = new RdosEngineStreamJobRetryDAO();

    private ClientCache clientCache = ClientCache.getInstance();

    private ZkLocalCache zkLocalCache = ZkLocalCache.getInstance();

    private static RestartDealer sigleton = new RestartDealer();

    private RestartDealer(){
    }

    public static RestartDealer getInstance(){
        return sigleton;
    }


    /**
     * 对提交结果判定是否重试
     * 不限制重试次数
     * @param jobClient
     * @return
     */
    public boolean checkAndRestartForSubmitResult(JobClient jobClient){
        if(!checkNeedReSubmitForSubmitResult(jobClient)){
            return false;
        }

        resetStatus(jobClient, true);
        addToRestart(jobClient);
        //update retry num
        increaseJobRetryNum(jobClient.getTaskId(), jobClient.getComputeType().getType());
        LOG.info("------ job: {} add into orderLinkedBlockingQueue again.", jobClient.getTaskId());
        return true;
    }

    private boolean checkNeedReSubmitForSubmitResult(JobClient jobClient){
        if(jobClient.getJobResult() == null){
            //未提交过
            return true;
        }

        if(!jobClient.getJobResult().getCheckRetry()){
            return false;
        }

        String engineType = jobClient.getEngineType();

        try{
            String pluginInfo = jobClient.getPluginInfo();
            String resultMsg = jobClient.getJobResult().getMsgInfo();

            IClient client = clientCache.getClient(engineType, pluginInfo);
            if(client == null){
                LOG.error("can't get client by engineType:{}", engineType);
                return false;
            }

            if(!jobClient.getIsFailRetry()){
                return false;
            }

            IRestartStrategy restartStrategy = client.getRestartStrategy();
            if(restartStrategy == null){
                LOG.warn("engineType " + engineType + " not support restart." );
                return false;
            }

            Integer alreadyRetryNum = getAlreadyRetryNum(jobClient.getTaskId(), jobClient.getComputeType().getType());
            return restartStrategy.retrySubmitFail(jobClient.getTaskId(), resultMsg, alreadyRetryNum, jobClient.getMaxRetryNum());
        }catch (Exception e){
            LOG.error("", e);
        }

        return false;
    }

    /***
     * 对任务状态判断是否需要重试
     * @param status
     * @param jobId
     * @param engineJobId
     * @param engineType
     * @param computeType
     * @param pluginInfo
     * @return
     */
    public boolean checkAndRestart(Integer status, String jobId, String engineJobId, String engineType,
                                          Integer computeType, String pluginInfo){
        if(!RdosTaskStatus.FAILED.getStatus().equals(status) && !RdosTaskStatus.SUBMITFAILD.getStatus().equals(status)){
            return false;
        }
        try {
            Integer alreadyRetryNum = getAlreadyRetryNum(jobId, computeType);
            boolean needResubmit = checkNeedResubmit(jobId, engineJobId, engineType, pluginInfo, computeType, alreadyRetryNum);
            LOG.info("[checkAndRestart] jobId:{} engineJobId:{} status:{} engineType:{} alreadyRetryNum:{} needResubmit:{}",
                                        jobId, engineJobId, status, engineType, alreadyRetryNum, needResubmit);

            if(!needResubmit){
                return false;
            }

            RdosEngineJobCache jobCache = engineJobCacheDAO.getJobById(jobId);
            if(jobCache == null){
                LOG.error("can't get record from rdos_engine_job_cache by jobId:{}", jobId);
                return false;
            }

            String jobInfo = jobCache.getJobInfo();
            ParamAction paramAction = PublicUtil.jsonStrToObject(jobInfo, ParamAction.class);
            JobClient jobClient = new JobClient(paramAction);
            String finalJobId = jobClient.getTaskId();
            Integer finalComputeType = jobClient.getComputeType().getType();
            jobClient.setCallBack((jobStatus)->{
                updateJobStatus(finalJobId, finalComputeType, jobStatus);
            });

            if(EngineType.Kylin.name().equalsIgnoreCase(jobClient.getEngineType())){
                setRetryTag(jobClient);
            }

            resetStatus(jobClient, false);
            addToRestart(jobClient);
            // update retryNum
            increaseJobRetryNum(jobId, computeType);
            LOG.warn("jobName:{}---jobId:{} resubmit again...",jobClient.getJobName(), jobClient.getTaskId());
            return true;
        } catch (Exception e) {
            LOG.error("", e);
            return false;
        }
    }

    private void setRetryTag(JobClient jobClient){
        try {
            Map<String, Object> pluginInfoMap = PublicUtil.jsonStrToObject(jobClient.getPluginInfo(), Map.class);
            pluginInfoMap.put("retry", true);
            jobClient.setPluginInfo(PublicUtil.objToString(pluginInfoMap));
        } catch (IOException e) {
            LOG.warn("Set retry tag error:", e);
        }
    }

    private boolean checkNeedResubmit(String jobId,
                                      String engineJobId,
                                      String engineType,
                                      String pluginInfo,
                                      Integer computeType,
                                      Integer alreadyRetryNum) throws Exception {
        if(Strings.isNullOrEmpty(engineJobId)){
            return false;
        }

        if(ComputeType.STREAM.getType().equals(computeType)){
            //do nothing
        }else{
            RdosEngineBatchJob engineBatchJob = engineBatchJobDAO.getRdosTaskByTaskId(jobId);
            if(engineBatchJob == null){
                LOG.error("batch job {} can't find.", jobId);
                return false;
            }
        }

        IClient client = clientCache.getClient(engineType, pluginInfo);
        if(client == null){
            LOG.error("can't get client by engineType:{}", engineJobId);
            return false;
        }

        IRestartStrategy restartStrategy = client.getRestartStrategy();
        if(restartStrategy == null){
            LOG.warn("engineType " + engineType + " not support restart." );
            return false;
        }

        RdosEngineJobCache jobCache = engineJobCacheDAO.getJobById(jobId);
        if(jobCache == null){
            LOG.error("can't get record from rdos_engine_job_cache by jobId:{}", jobId);
            return false;
        }

        String jobInfo = jobCache.getJobInfo();
        ParamAction paramAction = PublicUtil.jsonStrToObject(jobInfo, ParamAction.class);
        JobClient jobClient = new JobClient(paramAction);

        if(!jobClient.getIsFailRetry()){
            return false;
        }

        return restartStrategy.checkCanRestart(jobId, engineJobId, client, alreadyRetryNum, jobClient.getMaxRetryNum());
    }

    private void resetStatus(JobClient jobClient, boolean submitFailed){
        String jobId = jobClient.getTaskId();
        Integer computeType = jobClient.getComputeType().getType();
        String engineType = jobClient.getEngineType();
        //重试的时候，更改cache状态
        WorkNode.getInstance().updateCache(jobClient, EJobCacheStage.IN_PRIORITY_QUEUE.getStage());
        String zkTaskId = TaskIdUtil.getZkTaskId(computeType, engineType, jobId);
        //重试任务更改在zk的状态，统一做状态清理
        zkLocalCache.updateLocalMemTaskStatus(zkTaskId, RdosTaskStatus.RESTARTING.getStatus());

        //重试的任务不置为失败，waitengine
        if(ComputeType.STREAM.getType().equals(computeType)){
            if (submitFailed){
                engineStreamJobDAO.updateTaskSubmitFailed(jobId, null, null, RdosTaskStatus.RESTARTING.getStatus());
            } else {
                engineStreamJobDAO.updateTaskEngineIdAndStatus(jobId, null, null, RdosTaskStatus.RESTARTING.getStatus());
            }
            jobRetryRecord(jobClient);
            engineStreamJobDAO.updateSubmitLog(jobId, null);
            engineStreamJobDAO.updateEngineLog(jobId, null);
        }else if(ComputeType.BATCH.getType().equals(computeType)){
            if (submitFailed){
                engineBatchJobDAO.updateJobSubmitFailed(jobId, null, RdosTaskStatus.RESTARTING.getStatus(),null);
            } else {
                engineBatchJobDAO.updateJobEngineIdAndStatus(jobId, null, RdosTaskStatus.RESTARTING.getStatus(),null);
            }
            jobRetryRecord(jobClient);
            engineBatchJobDAO.updateSubmitLog(jobId, null);
            engineBatchJobDAO.updateEngineLog(jobId, null);
            engineBatchJobDAO.resetExecTime(jobId);
        }else{
            LOG.error("not support for computeType:{}", computeType);
        }
    }

    private void jobRetryRecord(JobClient jobClient) {
        try {
            Integer computeType = jobClient.getComputeType().getType();
            if(ComputeType.STREAM.getType().equals(computeType)){
                RdosEngineStreamJob streamJob = engineStreamJobDAO.getRdosTaskByTaskId(jobClient.getTaskId());
                RdosEngineStreamJobRetry streamJobRetry = RdosEngineStreamJobRetry.toEntity(streamJob, jobClient);
                streamJobRetry.setStatus(RdosTaskStatus.RESTARTING.getStatus().byteValue());
                engineStreamJobRetryDAO.insert(streamJobRetry);
            } else if(ComputeType.BATCH.getType().equals(computeType)){
                RdosEngineBatchJob batchJob = engineBatchJobDAO.getRdosTaskByTaskId(jobClient.getTaskId());
                RdosEngineBatchJobRetry batchJobRetry = RdosEngineBatchJobRetry.toEntity(batchJob, jobClient);
                batchJobRetry.setStatus(RdosTaskStatus.RESTARTING.getStatus().byteValue());
                engineBatchJobRetryDAO.insert(batchJobRetry);
            }
        } catch (Throwable e ){
            LOG.error("{}",e);
        }
    }

    private void updateJobStatus(String jobId, Integer computeType, Integer status) {
        if (ComputeType.STREAM.getType().equals(computeType)) {
            engineStreamJobDAO.updateTaskStatus(jobId, status);
        } else {
            engineBatchJobDAO.updateJobStatus(jobId, status);
        }
    }

    private void addToRestart(JobClient jobClient){
        jobClient.setRestartTime(System.currentTimeMillis() + SUBMIT_INTERVAL);
        WorkNode.getInstance().redirectSubmitJob(jobClient, false);
    }

    /**
     * 获取任务已经重试的次数
     * @param jobId
     * @param computeType
     * @return
     */
    private Integer getAlreadyRetryNum(String jobId, Integer computeType){
        if (ComputeType.STREAM.getType().equals(computeType)) {
            RdosEngineStreamJob rdosEngineStreamJob = engineStreamJobDAO.getRdosTaskByTaskId(jobId);
            return rdosEngineStreamJob.getRetryNum() == null ? 0 : rdosEngineStreamJob.getRetryNum();
        } else {
            RdosEngineBatchJob rdosEngineBatchJob = engineBatchJobDAO.getRdosTaskByTaskId(jobId);
            return rdosEngineBatchJob.getRetryNum() == null ? 0 : rdosEngineBatchJob.getRetryNum();
        }
    }

    private void increaseJobRetryNum(String jobId, Integer computeType){
        if (ComputeType.STREAM.getType().equals(computeType)) {
            RdosEngineStreamJob rdosEngineStreamJob = engineStreamJobDAO.getRdosTaskByTaskId(jobId);
            Integer retryNum = rdosEngineStreamJob.getRetryNum() == null ? 0 : rdosEngineStreamJob.getRetryNum();
            retryNum++;
            engineStreamJobDAO.updateRetryNum(jobId, retryNum);
        } else {
            RdosEngineBatchJob rdosEngineBatchJob = engineBatchJobDAO.getRdosTaskByTaskId(jobId);
            Integer retryNum = rdosEngineBatchJob.getRetryNum() == null ? 0 : rdosEngineBatchJob.getRetryNum();
            retryNum++;
            engineBatchJobDAO.updateRetryNum(jobId, retryNum);
        }
    }
}
