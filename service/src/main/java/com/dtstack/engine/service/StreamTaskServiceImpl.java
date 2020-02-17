package com.dtstack.engine.service;

import com.dtstack.engine.common.exception.ErrorCode;
import com.dtstack.engine.common.exception.RdosException;
import com.dtstack.engine.common.annotation.Param;
import com.dtstack.engine.common.http.PoolHttpClient;
import com.dtstack.engine.common.util.ApplicationWSParser;
import com.dtstack.engine.common.util.PublicUtil;
import com.dtstack.engine.common.util.UrlUtil;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.enums.ComputeType;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.pojo.ParamAction;
import com.dtstack.engine.service.db.dao.RdosEngineJobDAO;
import com.dtstack.engine.service.db.dao.RdosEngineJobCacheDAO;
import com.dtstack.engine.service.db.dao.RdosStreamTaskCheckpointDAO;
import com.dtstack.engine.service.db.dataobject.RdosEngineJob;
import com.dtstack.engine.service.db.dataobject.RdosEngineJobCache;
import com.dtstack.engine.service.db.dataobject.RdosStreamTaskCheckpoint;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Reason: 查询实时任务数据
 * Date: 2018/10/11
 * Company: www.dtstack.com
 * @author jiangbo
 */
public class StreamTaskServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(StreamTaskServiceImpl.class);

    private RdosStreamTaskCheckpointDAO rdosStreamTaskCheckpointDAO = new RdosStreamTaskCheckpointDAO();

    private RdosEngineJobDAO rdosEngineJobDAO = new RdosEngineJobDAO();

    private RdosEngineJobCacheDAO rdosEngineJobCacheDAO = new RdosEngineJobCacheDAO();

    private static final String APPLICATION_REST_API_TMP = "%s/ws/v1/cluster/apps/%s";


    /**
     * 查询checkPoint
     */
    public List<RdosStreamTaskCheckpoint> getCheckPoint(@Param("taskId") String taskId, @Param("triggerStart") Long triggerStart, @Param("triggerEnd") Long triggerEnd){
        return rdosStreamTaskCheckpointDAO.listByTaskIdAndRangeTime(taskId,triggerStart,triggerEnd);
    }

    public RdosStreamTaskCheckpoint getByTaskIdAndEngineTaskId(@Param("taskId") String taskId, @Param("engineTaskId") String engineTaskId){
        return rdosStreamTaskCheckpointDAO.getByTaskIdAndEngineTaskId(taskId, engineTaskId);
    }

    /**
     * 查询stream job
     */
    public List<RdosEngineJob> getEngineStreamJob(@Param("taskIds") List<String> taskIds){
        return rdosEngineJobDAO.getRdosTaskByTaskIds(taskIds);
    }

    /**
     * 获取某个状态的任务task_id
     */
    public List<String> getTaskIdsByStatus(@Param("status") Integer status){
        return rdosEngineJobDAO.getTaskIdsByStatus(status, ComputeType.STREAM.getType());
    }

    /**
     * 获取任务的状态
     */
    public Byte getTaskStatus(@Param("taskId") String taskId){
        Byte status = null;
        if (StringUtils.isNotEmpty(taskId)){
        	RdosEngineJob engineJob = rdosEngineJobDAO.getRdosTaskByTaskId(taskId);
            if (engineJob != null){
                status = engineJob.getStatus();
            }
        }

        return status;
    }

    /**
     * 获取实时计算运行中任务的日志URL
     * @param taskId
     * @return
     */
    public Pair<String, String> getRunningTaskLogUrl(@Param("taskId") String taskId) {

        Preconditions.checkState(StringUtils.isNotEmpty(taskId), "taskId can't be empty");

        RdosEngineJob engineJob = rdosEngineJobDAO.getRdosTaskByTaskId(taskId);
        Preconditions.checkNotNull(engineJob, "can't find record by taskId" + taskId);

        //只获取运行中的任务的log—url
        Byte status = engineJob.getStatus();
        if (!RdosTaskStatus.RUNNING.getStatus().equals(status.intValue())) {
            throw new RdosException(String.format("job:%s not running status ", taskId), ErrorCode.INVALID_TASK_STATUS);
        }

        String applicationId = engineJob.getApplicationId();

        if (StringUtils.isEmpty(applicationId)) {
            throw new RdosException(String.format("job %s not running in perjob", taskId), ErrorCode.INVALID_TASK_RUN_MODE);
        }

        Preconditions.checkState(applicationId.contains("application"), String.format("current task %s don't have application id.", taskId));

        JobClient jobClient = null;
        JobIdentifier jobIdentifier = null;

        //如何获取url前缀
        try{
            RdosEngineJobCache rdosEngineJobCache = rdosEngineJobCacheDAO.getJobById(taskId);
            if (rdosEngineJobCache == null) {
                throw new RdosException(String.format("job:%s not exist in job cache table ", taskId),ErrorCode.JOB_CACHE_NOT_EXIST);
            }
            String jobInfo = rdosEngineJobCache.getJobInfo();
            ParamAction paramAction = PublicUtil.jsonStrToObject(jobInfo, ParamAction.class);

            jobIdentifier = JobIdentifier.createInstance(engineJob.getEngineJobId(), applicationId, taskId);
            jobClient = new JobClient(paramAction);
            String jobMaster = JobClient.getJobMaster(jobClient.getEngineType(), jobClient.getPluginInfo(), jobIdentifier);
            String rootUrl = UrlUtil.getHttpRootUrl(jobMaster);
            String requestUrl = String.format(APPLICATION_REST_API_TMP, rootUrl, applicationId);

            String response = PoolHttpClient.get(requestUrl);
            String amContainerLogsUrl = ApplicationWSParser.getAmContainerLogsUrl(response);

            String logPreUrl = UrlUtil.getHttpRootUrl(amContainerLogsUrl);
            String amContainerPreViewHttp = PoolHttpClient.get(amContainerLogsUrl);
            return ApplicationWSParser.parserAmContainerPreViewHttp(amContainerPreViewHttp, logPreUrl);

        }catch (Exception e){
            if (jobClient != null && jobIdentifier != null) {
                RdosTaskStatus jobStatus = JobClient.getStatus(jobClient.getEngineType(), jobClient.getPluginInfo(), jobIdentifier);
                Integer statusCode = jobStatus.getStatus();
                if (RdosTaskStatus.getStoppedStatus().contains(statusCode)) {
                    throw new RdosException(String.format("job:%s had stop ", taskId), ErrorCode.INVALID_TASK_STATUS, e);
                }
            }
            throw new RdosException(String.format("get job:%s ref application url error..", taskId), ErrorCode.UNKNOWN_ERROR, e);
        }

    }
}
