package com.dtstack.engine.service;

import com.dtstack.engine.common.annotation.Param;
import com.dtstack.engine.common.enums.EJobCacheStage;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.util.DateUtil;
import com.dtstack.engine.common.util.PublicUtil;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.pojo.ParamAction;
import com.dtstack.engine.service.db.dao.RdosEngineJobDAO;
import com.dtstack.engine.service.db.dao.RdosEngineJobCacheDAO;
import com.dtstack.engine.service.db.dao.RdosEngineJobStopRecordDAO;
import com.dtstack.engine.service.db.dataobject.RdosEngineJob;
import com.dtstack.engine.service.db.dataobject.RdosEngineJobCache;
import com.dtstack.engine.service.db.dataobject.RdosEngineJobStopRecord;
import com.dtstack.engine.service.node.WorkNode;
import com.dtstack.engine.service.zk.cache.ZkLocalCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对接数栈控制台
 * <p>
 * company: www.dtstack.com
 * author: toutian
 * create: 2018/9/18
 */
public class ConsoleServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleServiceImpl.class);

    private RdosEngineJobDAO engineJobDao = new RdosEngineJobDAO();

    private RdosEngineJobCacheDAO engineJobCacheDao = new RdosEngineJobCacheDAO();

    private RdosEngineJobStopRecordDAO jobStopRecordDAO = new RdosEngineJobStopRecordDAO();

    private WorkNode workNode = WorkNode.getInstance();

    public Boolean finishJob(String jobId, Integer status) {
        if (!RdosTaskStatus.isStopped(status.byteValue())) {
            logger.warn("Job status：" + status + " is not stopped status");
            return false;
        }
        ZkLocalCache.getInstance().updateLocalMemTaskStatus(jobId, status);
        engineJobCacheDao.deleteJob(jobId);
        engineJobDao.updateJobStatus(jobId, status);
        logger.info("jobId:{} update job status:{}, job is finished.", jobId, status);
        return true;
    }

    public List<String> nodes() {
        try {
            return engineJobCacheDao.getAllNodeAddress();
        } catch (Exception e) {
            return Collections.EMPTY_LIST;
        }
    }

    public Map<String, Object> searchJob(@Param("jobName") String jobName) {
        Preconditions.checkNotNull(jobName, "parameters of jobName not be null.");
        String jobId = null;
        RdosEngineJob engineJob = engineJobDao.getByName(jobName);
        if (engineJob != null) {
            jobId = engineJob.getJobId();
        }
        if (jobId == null) {
            return null;
        }
        RdosEngineJobCache engineJobCache = engineJobCacheDao.getJobById(jobId);
        if (engineJobCache == null) {
            return null;
        }
        try {
            Map<String, Object> theJobMap = PublicUtil.jsonStrToObject(engineJobCache.getJobInfo(), Map.class);
            this.fillJobInfo(theJobMap, engineJob, engineJobCache);

            Map<String, Object> result = new HashMap<>(3);
            result.put("theJob", Lists.newArrayList(theJobMap));
            result.put("theJobIdx", 1);
            result.put("node", engineJobCache.getNodeAddress());

            return result;
        } catch (Exception e) {
            logger.error("{}", e);
        }
        return null;
    }

    public List<String> listNames(@Param("jobName") String jobName) {
        try {
            Preconditions.checkNotNull(jobName, "parameters of jobName not be null.");
            return engineJobCacheDao.listNames(jobName);
        } catch (Exception e) {
            logger.error("{}", e);
        }
        return null;
    }

    public List<String> jobResources() {
        return engineJobCacheDao.getJobResources();
    }

    /**
     * 根据计算引擎类型显示任务
     */
    public Collection<Map<String, Object>> overview(@Param("node") String nodeAddress) {
        if (StringUtils.isBlank(nodeAddress)) {
            nodeAddress = null;
        }

        Map<String, Map<String, Object>> overview = new HashMap<>();
        List<Map<String, Object>> groupResult = engineJobCacheDao.groupByJobResource(nodeAddress);
        if (CollectionUtils.isNotEmpty(groupResult)) {
            for (Map<String, Object> record : groupResult) {
                long generateTime = MapUtils.getLong(record, "generateTime");
                long waitTime = System.currentTimeMillis() - generateTime;
                record.put("waitTime", waitTime);
            }

            for (Map<String, Object> record : groupResult) {
                String engineType = MapUtils.getString(record, "engineType");
                String groupName = MapUtils.getString(record, "groupName");
                int stage = MapUtils.getInteger(record, "stage");
                long waitTime = MapUtils.getLong(record, "waitTime");
                long jobSize = MapUtils.getLong(record, "jobSize");
                EJobCacheStage eJobCacheStage = EJobCacheStage.getStage(stage);
                String jobResource = WorkNode.getInstance().getJobResource(engineType, groupName);

                Map<String, Object> overviewRecord = overview.computeIfAbsent(jobResource, k-> {
                    Map<String, Object> overviewEle = new HashMap<>();
                    overviewEle.put("engineType", engineType);
                    overviewEle.put("groupName", groupName);
                    overviewEle.put("jobResource", jobResource);
                    return overviewEle;
                });
                overviewRecord.put(eJobCacheStage.name().toLowerCase(), stage);
                overviewRecord.put(eJobCacheStage.name().toLowerCase() + "JobSize", jobSize);
                overviewRecord.put(eJobCacheStage.name().toLowerCase() + "WaitTime", waitTime);
            }
        }
        return overview.values();
    }

    public Map<String, Object> groupDetail(@Param("jobResource") String jobResource,
                                           @Param("engineType") String engineType,
                                           @Param("groupName") String groupName,
                                           @Param("node") String nodeAddress,
                                           @Param("pageSize") int pageSize,
                                           @Param("currentPage") int currentPage) {
        Preconditions.checkNotNull(jobResource, "parameters of jobResource is required");
        if (StringUtils.isBlank(nodeAddress)) {
            nodeAddress = null;
        }
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> topN = new ArrayList<>();
        Long count = 0L;
        result.put("queueSize", count);
        result.put("topN", topN);

        try {
            count = engineJobCacheDao.countByJobResource(engineType, groupName, nodeAddress);
            if (count > 0) {
                List<RdosEngineJobCache> engineJobCaches = engineJobCacheDao.listByJobResource(engineType, groupName, nodeAddress);
                for (RdosEngineJobCache engineJobCache : engineJobCaches) {
                    Map<String, Object> theJobMap = PublicUtil.ObjectToMap(engineJobCache);
                    RdosEngineJob engineJob = engineJobDao.getRdosTaskByTaskId(engineJobCache.getJobId());
                    if (engineJob != null) {
                        this.fillJobInfo(theJobMap, engineJob, engineJobCache);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("{}", e);
        }
        return result;
    }

    private void fillJobInfo(Map<String, Object> theJobMap, RdosEngineJob engineJob, RdosEngineJobCache engineJobCache) {
        theJobMap.put("status", engineJob.getStatus());
        theJobMap.put("execStartTime", engineJob.getExecStartTime());
        theJobMap.put("generateTime", engineJobCache.getGmtCreate());
        long currentTime = System.currentTimeMillis();
        String waitTime = DateUtil.getTimeDifference(currentTime - engineJobCache.getGmtCreate().getTime());
        theJobMap.put("waitTime", waitTime);
    }

    public Boolean jobStick(@Param("jobId") String jobId,
                            @Param("jobResource") String jobResource) {
        Preconditions.checkNotNull(jobId, "parameters of jobId is required");
        Preconditions.checkNotNull(jobResource, "parameters of jobResource is required");

        try {
            RdosEngineJobCache engineJobCache = engineJobCacheDao.getJobById(jobId);
            ParamAction paramAction = PublicUtil.jsonStrToObject(engineJobCache.getJobInfo(), ParamAction.class);
            JobClient jobClient = new JobClient(paramAction);
            jobClient.setCallBack((jobStatus)-> {
                workNode.updateJobStatus(jobClient.getTaskId(), jobStatus);
            });
            workNode.addGroupPriorityQueue(jobClient, false);
            return true;
        } catch (Exception e) {
            logger.error("{}", e);
        }
        return false;
    }

    public void stopJob(@Param("jobId") String jobId) throws Exception {
        Preconditions.checkNotNull(jobId, "parameters of jobId is required");

        List<String> alreadyExistJobIds = jobStopRecordDAO.listByJobIds(Lists.newArrayList(jobId));
        if (alreadyExistJobIds.contains(jobId)) {
            logger.info("jobId:{} ignore insert stop record, because is already exist in table.", jobId);
            return;
        }

        RdosEngineJobStopRecord stopRecord = new RdosEngineJobStopRecord();
        stopRecord.setTaskId(jobId);

        jobStopRecordDAO.insert(stopRecord);
    }

    public void stopJobList(@Param("jobResource") String jobResource,
                            @Param("computeType") Integer computeType,
                            @Param("engineType") String engineType,
                            @Param("groupName") String groupName,
                            @Param("nodeAddress") String nodeAddress,
                            @Param("stage") Integer stage,
                            @Param("jobIdList") List<String> jobIdList) throws Exception {
        if (jobIdList != null && !jobIdList.isEmpty()) {
            //杀死指定jobIdList的任务

            List<String> alreadyExistJobIds = jobStopRecordDAO.listByJobIds(jobIdList);
            for (String jobId : jobIdList) {
                if (alreadyExistJobIds.contains(jobId)) {
                    logger.info("jobId:{} ignore insert stop record, because is already exist in table.", jobId);
                    continue;
                }

                RdosEngineJobStopRecord stopRecord = new RdosEngineJobStopRecord();
                stopRecord.setTaskId(jobId);
                jobStopRecordDAO.insert(stopRecord);
            }
        } else {
            //根据条件杀死所有任务

            Preconditions.checkNotNull(engineType, "parameters of engineType is required");
            Preconditions.checkNotNull(groupName, "parameters of groupName is required");
            Preconditions.checkNotNull(stage, "parameters of stage is required");

            if (StringUtils.isBlank(nodeAddress)) {
                nodeAddress = null;
            }

            long startId = 0L;
            while (true) {
                List<RdosEngineJobCache> jobCaches = engineJobCacheDao.listByNodeAddressStage(startId, nodeAddress, stage, engineType, groupName);
                if (CollectionUtils.isEmpty(jobCaches)) {
                    //两种情况：
                    //1. 可能本身没有jobcaches的数据
                    //2. master节点已经为此节点做了容灾
                    break;
                }
                List<String> jobIds = jobCaches.stream().map(RdosEngineJobCache::getJobId).collect(Collectors.toList());
                List<String> alreadyExistJobIds = jobStopRecordDAO.listByJobIds(jobIds);
                for(RdosEngineJobCache jobCache : jobCaches){
                    if (alreadyExistJobIds.contains(jobCache.getJobId())) {
                        logger.info("jobId:{} ignore insert stop record, because is already exist in table.", jobCache.getJobId());
                        continue;
                    }

                    RdosEngineJobStopRecord stopRecord = new RdosEngineJobStopRecord();
                    stopRecord.setTaskId(jobCache.getJobId());
                    jobStopRecordDAO.insert(stopRecord);
                }
            }
        }
    }
}
