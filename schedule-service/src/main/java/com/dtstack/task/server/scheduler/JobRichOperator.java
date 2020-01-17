package com.dtstack.task.server.scheduler;

import com.dtstack.dtcenter.common.constant.TaskStatusConstrant;
import com.dtstack.dtcenter.common.enums.*;
import com.dtstack.dtcenter.common.util.MathUtil;
import com.dtstack.sql.Twins;
import com.dtstack.task.common.enums.DependencyType;
import com.dtstack.task.common.enums.EScheduleStatus;
import com.dtstack.task.common.enums.EScheduleType;
import com.dtstack.task.common.enums.JobCheckStatus;
import com.dtstack.task.common.env.EnvironmentContext;
import com.dtstack.task.dao.BatchJobDao;
import com.dtstack.task.dao.BatchJobJobDao;
import com.dtstack.task.domain.BatchJob;
import com.dtstack.task.domain.BatchJobJob;
import com.dtstack.task.domain.BatchTaskShade;
import com.dtstack.task.server.bo.ScheduleBatchJob;
import com.dtstack.task.server.impl.BatchJobService;
import com.dtstack.task.server.impl.BatchTaskShadeService;
import com.dtstack.task.server.parser.ESchedulePeriodType;
import com.dtstack.task.server.parser.ScheduleCron;
import com.dtstack.task.server.parser.ScheduleFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * company: www.dtstack.com
 *
 * @author: toutian
 * create: 2019/10/30
 */
@Component
public class JobRichOperator {

    private static final Logger logger = LoggerFactory.getLogger(JobRichOperator.class);

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");

    private static final long COUNT_BITS = Long.SIZE - 8;

    @Autowired
    private EnvironmentContext environmentContext;

    @Autowired
    private BatchJobDao batchJobDao;

    @Autowired
    private BatchJobJobDao batchJobJobDao;

    @Autowired
    private BatchJobService batchJobService;

    @Autowired
    private BatchTaskShadeService batchTaskShadeService;

    /**
     * 判断任务是否可以执行
     *
     * @param scheduleBatchJob
     * @param status           当前任务状态
     * @param scheduleType
     * @param notStartCache
     * @param errorJobCache
     * @param taskCache
     * @return
     * @throws ParseException
     */
    public JobCheckRunInfo checkJobCanRun(ScheduleBatchJob scheduleBatchJob, Integer status, Integer scheduleType,
                                          Set<String> notStartCache, Map<String, JobErrorInfo> errorJobCache,
                                          Map<Long, BatchTaskShade> taskCache) throws ParseException {

        BatchTaskShade batchTaskShade = getTaskShadeFromCache(taskCache, scheduleBatchJob.getAppType(), scheduleBatchJob.getTaskId());

        if (batchTaskShade == null || batchTaskShade.getIsDeleted() == Deleted.DELETED.getStatus()) {
            return JobCheckRunInfo.createCheckInfo(JobCheckStatus.TASK_DELETE);
        }

        if (!TaskStatus.UNSUBMIT.getStatus().equals(status)) {
            return JobCheckRunInfo.createCheckInfo(JobCheckStatus.NOT_UNSUBMIT);
        }

        //正常调度---判断当前任务是不是处于暂停状态--暂停状态直接返回冻结
        if (scheduleType == EScheduleType.NORMAL_SCHEDULE.getType()
                && batchTaskShade.getScheduleStatus().equals(EScheduleStatus.PAUSE.getVal())) {//查询缓存
            return JobCheckRunInfo.createCheckInfo(JobCheckStatus.TASK_PAUSE);
        }

        //判断执行时间是否到达
        String currStr = sdf.format(new Date());
        long currVal = Long.valueOf(currStr);
        long triggerVal = Long.valueOf(scheduleBatchJob.getCycTime());

        if (currVal < triggerVal) {
            return JobCheckRunInfo.createCheckInfo(JobCheckStatus.TIME_NOT_REACH);
        }

        //配置了允许过期才能
        if (Expired.EXPIRE.getVal() == batchTaskShade.getIsExpire() && this.checkExpire(scheduleBatchJob, scheduleType, batchTaskShade)) {
            return JobCheckRunInfo.createCheckInfo(JobCheckStatus.TIME_OVER_EXPIRE);
        }

        //判断依赖是否满足
        JobCheckStatus flag = JobCheckStatus.CAN_EXE;
        String extInfo = "";

        Integer dependencyType = scheduleBatchJob.getBatchJob().getDependencyType();

        for (BatchJobJob jobjob : scheduleBatchJob.getBatchJobJobList()) {

            if (notStartCache.contains(jobjob.getParentJobKey())) {
                notStartCache.add(jobjob.getJobKey());
                flag = JobCheckStatus.FATHER_JOB_NOT_FINISHED;
                break;
            }

            Long dependencyTaskId = getJobTaskIdFromJobKey(jobjob.getParentJobKey());
            Boolean isSelfDependency = scheduleBatchJob.getTaskId().equals(dependencyTaskId);

            if (errorJobCache.containsKey(jobjob.getParentJobKey())) {
                if (isSelfDependency && (DependencyType.SELF_DEPENDENCY_END.getType().equals(dependencyType))) {
                    continue;
                }
                if (DependencyType.PRE_PERIOD_CHILD_DEPENDENCY_END.getType().equals(dependencyType)){
                    continue;
                }

                errorJobCache.put(scheduleBatchJob.getJobKey(), createErrJobCacheInfo(scheduleBatchJob.getBatchJob(), taskCache));
                if (isSelfDependency) {
                    flag = JobCheckStatus.SELF_PRE_PERIOD_EXCEPTION;
                    logger.error("job:{} 自依赖异常 job:{} error cache self_pre_period_exception", jobjob.getJobKey(), jobjob.getParentJobKey());
                } else {
                    flag = JobCheckStatus.FATHER_JOB_EXCEPTION;
                    JobErrorInfo fatherJobErrIfo = errorJobCache.get(jobjob.getParentJobKey());
                    extInfo = "(父任务名称为:" + fatherJobErrIfo.getTaskName() + ")";
                    logger.error("job:{} 父任务异常 job:{} error cache father_job_exception", jobjob.getJobKey(), jobjob.getParentJobKey());
                }
                break;
            }

            BatchJob dependencyJob = batchJobService.getJobByJobKeyAndType(jobjob.getParentJobKey(), scheduleType);
            if (dependencyJob == null) {//有可能任务已经失效.或者配置错误-->只有正常调度才可能存在
                if (scheduleType == EScheduleType.FILL_DATA.getType()) {
                    continue;
                }

                logger.error("job:{} dependency job:{} not exists.", jobjob.getJobKey(), jobjob.getParentJobKey());
                flag = JobCheckStatus.FATHER_NO_CREATED;
                String parentJobKey = jobjob.getParentJobKey();
                String parentTaskName = batchTaskShadeService.getTaskNameByJobKey(parentJobKey, scheduleBatchJob.getBatchJob().getAppType());
                extInfo = "(父任务名称为:" + parentTaskName + ")";
                errorJobCache.put(scheduleBatchJob.getJobKey(), createErrJobCacheInfo(scheduleBatchJob.getBatchJob(), taskCache));
                break;
            }

            Integer dependencyJobStatus = batchJobService.getStatusById(dependencyJob.getId());

            //工作中的起始子节点
            if (!StringUtils.equals("0", scheduleBatchJob.getBatchJob().getFlowJobId())) {
                BatchTaskShade taskShade = getTaskShadeFromCache(taskCache, dependencyJob.getAppType(), dependencyJob.getTaskId());
                if (taskShade != null &&
                        (taskShade.getTaskType().intValue() == EJobType.WORK_FLOW.getVal() || taskShade.getTaskType().intValue() == EJobType.ALGORITHM_LAB.getVal())) {
                    if (TaskStatus.RUNNING.getStatus().equals(dependencyJobStatus)) {
                        continue;
                    }
                }
            }

            //自依赖还需要判断二种情况
            //如果是依赖父任务成功 要判断父任务状态 走自依赖上一个周期异常
            //如果是依赖父任务结束 只要是满足结束条件的 这一周期可以执行
            if ((isSelfDependency && (DependencyType.SELF_DEPENDENCY_END.getType().equals(dependencyType)))
                    || DependencyType.PRE_PERIOD_CHILD_DEPENDENCY_END.getType().equals(dependencyType)) {
                if (isEndStatus(dependencyJobStatus)) {
                    continue;
                } else {
                    flag = JobCheckStatus.FATHER_JOB_NOT_FINISHED;
                    break;
                }
            }

            if (TaskStatus.FAILED.getStatus().equals(dependencyJobStatus)
                    || TaskStatus.SUBMITFAILD.getStatus().equals(dependencyJobStatus)
                    || TaskStatus.PARENTFAILED.getStatus().equals(dependencyJobStatus)) {
                flag = JobCheckStatus.FATHER_JOB_EXCEPTION;
                if (isSelfDependency) {
                    flag = JobCheckStatus.SELF_PRE_PERIOD_EXCEPTION;
                    logger.error("job:{} 自依赖异常 job:{} self_pre_period_exception", jobjob.getJobKey(), jobjob.getParentJobKey());
                } else {//记录失败的父任务的名称
                    JobErrorInfo errorInfo = createErrJobCacheInfo(dependencyJob, taskCache);
                    extInfo = "(父任务名称为:" + errorInfo.getTaskName() + ")";
                    errorJobCache.put(dependencyJob.getJobKey(), errorInfo);
                    logger.error("job:{} 父任务异常 taskname:{} error cache father_job_exception", dependencyJob.getJobKey(), errorInfo.getTaskName());
                }

                errorJobCache.put(scheduleBatchJob.getJobKey(), createErrJobCacheInfo(scheduleBatchJob.getBatchJob(), taskCache));
                break;
            } else if (TaskStatus.FROZEN.getStatus().equals(dependencyJobStatus)) {
                if (!isSelfDependency) {
                    flag = JobCheckStatus.DEPENDENCY_JOB_FROZEN;
                    break;
                } else {//自依赖的上游任务冻结不会影响当前任务的执行
                    continue;
                }

            } else if (TaskStatus.CANCELED.getStatus().equals(dependencyJobStatus)
                    || TaskStatus.KILLED.getStatus().equals(dependencyJobStatus)) {
                flag = JobCheckStatus.DEPENDENCY_JOB_CANCELED;
                extInfo = "(父任务名称为:" + getTaskNameFromJobName(dependencyJob.getJobName(), dependencyJob.getType()) + ")";
                break;
            } else if (!TaskStatus.FINISHED.getStatus().equals(dependencyJobStatus) &&
                    !TaskStatus.MANUALSUCCESS.getStatus().equals(dependencyJobStatus)) {//系统设置完成或者手动设置为完成
                flag = JobCheckStatus.FATHER_JOB_NOT_FINISHED;
                notStartCache.add(jobjob.getJobKey());
                break;
            }
        }

        Boolean dependencyChildPrePeriod = DependencyType.PRE_PERIOD_CHILD_DEPENDENCY_SUCCESS.getType().equals(dependencyType)
                || DependencyType.PRE_PERIOD_CHILD_DEPENDENCY_END.getType().equals(dependencyType);

        if (JobCheckStatus.CAN_EXE.equals(flag) && dependencyChildPrePeriod) {//检测下游任务的上一个周期是否结束
            List<BatchJob> childPrePeriodList = scheduleBatchJob.getDependencyChildPrePeriodList();
            String jobKey = scheduleBatchJob.getBatchJob().getJobKey();
            if (childPrePeriodList == null) {//获取子任务的上一个周期
                List<BatchJobJob> childJobJobList = batchJobJobDao.listByParentJobKey(jobKey);
                childPrePeriodList = getFirstChildPrePeriodBatchJobJob(childJobJobList);
                scheduleBatchJob.setDependencyChildPrePeriodList(childPrePeriodList);
            }
            String cycTime = JobGraphBuilder.parseCycTimeFromJobKey(jobKey);
            //如果没有下游任务 需要往前找到有下游任务周期
            if (CollectionUtils.isEmpty(childPrePeriodList)) {
                ScheduleCron scheduleCron = null;
                try {
                    scheduleCron = ScheduleFactory.parseFromJson(batchTaskShade.getScheduleConf());
                } catch (IOException e) {
                    logger.error("get {} parent pre pre error", scheduleBatchJob.getTaskId(), e);
                }

                List<BatchJob> parentPrePreJob = this.getParentPrePreJob(jobKey, scheduleCron, cycTime);
                if (CollectionUtils.isNotEmpty(parentPrePreJob)) {
                    if (null == scheduleBatchJob.getDependencyChildPrePeriodList()) {
                        scheduleBatchJob.setDependencyChildPrePeriodList(new ArrayList<>());
                    }
                    scheduleBatchJob.getDependencyChildPrePeriodList().addAll(parentPrePreJob);
                }
            }

            for (BatchJob childJobPreJob : childPrePeriodList) {
                //子实例和 当前任务一致 直接运行
                if (jobKey.equals(childJobPreJob.getJobKey())) {
                    continue;
                }
                Integer childJobStatus = batchJobService.getStatusById(childJobPreJob.getId());
                boolean check;
                if (DependencyType.PRE_PERIOD_CHILD_DEPENDENCY_SUCCESS.getType().equals(dependencyType)) {
                    check = isSuccessStatus(childJobStatus);
                } else {
                    check = isEndStatus(childJobStatus);
                }

                if (!check) {
                    flag = JobCheckStatus.CHILD_PRE_NOT_FINISHED;
                    break;
                }
            }
        }

        return JobCheckRunInfo.createCheckInfo(flag, extInfo);
    }

    /**
     * 如果任务没有下游任务 需要找到往前 一直找到有下游任务的实例
     *
     * @param jobKey
     * @param scheduleCron
     * @param cycTime
     */
    private List<BatchJob> getParentPrePreJob(String jobKey, ScheduleCron scheduleCron, String cycTime) {
        if (StringUtils.isNotBlank(jobKey) && null != scheduleCron && StringUtils.isNotBlank(cycTime)) {
            String prePeriodJobTriggerDateStr = JobGraphBuilder.getPrePeriodJobTriggerDateStr(cycTime, scheduleCron);
            String prePeriodJobKey = jobKey.substring(0, jobKey.lastIndexOf("_") + 1) + prePeriodJobTriggerDateStr;
            EScheduleType scheduleType = JobGraphBuilder.parseScheduleTypeFromJobKey(jobKey);
            BatchJob dbBatchJob = batchJobService.getJobByJobKeyAndType(prePeriodJobKey, scheduleType.getType());
            //上一个周期任务为空 直接返回
            if (Objects.isNull(dbBatchJob)) {
                return null;
            }
            List<BatchJobJob> batchJobJobs = batchJobJobDao.listByParentJobKey(dbBatchJob.getJobKey());
            if (!CollectionUtils.isEmpty(batchJobJobs)) {
                //上一轮周期任务的下游任务不为空 判断下游任务的状态
                return batchJobDao.listJobByJobKeys(batchJobJobs.stream().map(BatchJobJob::getJobKey).collect(Collectors.toList()));
            }
            cycTime = JobGraphBuilder.parseCycTimeFromJobKey(prePeriodJobKey);
            //如果上一轮周期也没下游任务 继续找
            return this.getParentPrePreJob(prePeriodJobKey, scheduleCron, cycTime);
        }
        return null;

    }



    private boolean checkExpire(ScheduleBatchJob scheduleBatchJob, Integer scheduleType, BatchTaskShade batchTaskShade) {
        //---正常周期任务超过当前时间则标记为过期
        //http://redmine.prod.dtstack.cn/issues/19917
        if (EScheduleType.NORMAL_SCHEDULE.getType() != scheduleType) {
            return false;
        }
        //分钟 小时任务 才有过期
        if (!batchTaskShade.getPeriodType().equals(ESchedulePeriodType.MIN.getVal())
                && !batchTaskShade.getPeriodType().equals(ESchedulePeriodType.HOUR.getVal())) {
            return false;
        }
        //重跑不走
        if (Objects.nonNull(scheduleBatchJob.getBatchJob()) && Restarted.RESTARTED.getStatus() == scheduleBatchJob.getBatchJob().getIsRestart()) {
            return false;
        }
        //判断task任务是否配置了允许过期（暂时允许全部任务过期 不做判断）
        //超过时间限制
        if (StringUtils.isNotBlank(scheduleBatchJob.getBatchJob().getNextCycTime())) {
            LocalDateTime nextCycTime = LocalDateTime.parse(scheduleBatchJob.getBatchJob().getNextCycTime(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            if(nextCycTime.isBefore(LocalDateTime.now())){
                //如果超过过期时间限制 每天最后一次周期不允许过期
                return nextCycTime.getDayOfYear() == LocalDateTime.now().getDayOfYear();
            }
        }
        return false;
    }

    private boolean isEndStatus(Integer jobStatus) {
        for (Integer status : TaskStatusConstrant.endStatusList) {
            if (jobStatus.equals(status)) {
                return true;
            }
        }

        return false;
    }

    private boolean isSuccessStatus(Integer jobStatus) {
        for (Integer status : TaskStatusConstrant.FINISH_STATUS) {
            if (jobStatus.equals(status)) {
                return true;
            }
        }

        return false;
    }

    public BatchTaskShade getTaskShadeFromCache(Map<Long, BatchTaskShade> taskCache, Integer appType, Long taskId) {
        Long taskIdUnique = getTaskIdUnique(appType, taskId);
        return taskCache.computeIfAbsent(taskIdUnique,
                k -> {
                    BatchTaskShade taskShade = batchTaskShadeService.getBatchTaskById(taskId, appType);
                    if(Objects.nonNull(taskShade)){
                        //防止sqlText导致内存溢出
                        taskShade.setSqlText(null);
                    }
                    return taskShade;
                });
    }

    public JobErrorInfo createErrJobCacheInfo(BatchJob batchJob, Map<Long, BatchTaskShade> taskCache) {
        JobErrorInfo errorJobCacheInfo = new JobErrorInfo();
        errorJobCacheInfo.setJobKey(batchJob.getJobKey());

        BatchTaskShade batchTaskShade = getTaskShadeFromCache(taskCache, batchJob.getAppType(), batchJob.getTaskId());

        if (batchTaskShade == null) {
            errorJobCacheInfo.setTaskName("找不到对应的任务(id:" + batchJob.getTaskId() + ")");
        } else {
            errorJobCacheInfo.setTaskName(batchTaskShade.getName());
        }

        return errorJobCacheInfo;
    }



    public String getTaskNameFromJobName(String jobName, Integer scheduleType) {

        if (scheduleType == 1) {
            String[] arr = jobName.split("-");
            if (arr.length != 3) {
                return jobName;
            }

            return arr[1];
        } else {
            if (!jobName.contains("_")) {
                return jobName;
            }
            return jobName.substring(jobName.indexOf("_") + 1, jobName.lastIndexOf("_"));
        }

    }

    private List<BatchJob> getFirstChildPrePeriodBatchJobJob(List<BatchJobJob> jobJobList) {

        if (CollectionUtils.isEmpty(jobJobList)) {
            return Lists.newArrayList();
        }

        Map<Long, BatchJobJob> taskRefFirstJobMap = Maps.newHashMap();
        for (BatchJobJob batchJobJob : jobJobList) {
            String jobKey = batchJobJob.getJobKey();
            Long taskId = getJobTaskIdFromJobKey(jobKey);
            if (taskId == null) {
                continue;
            }

            BatchJobJob preJobJob = taskRefFirstJobMap.get(taskId);
            if (preJobJob == null) {
                taskRefFirstJobMap.put(taskId, batchJobJob);
            } else {
                String preJobTimeStr = getJobTriggerTimeFromJobKey(preJobJob.getJobKey());
                String currJobTimeStr = getJobTriggerTimeFromJobKey(batchJobJob.getJobKey());

                Long preJobTime = MathUtil.getLongVal(preJobTimeStr);
                Long currJobTime = MathUtil.getLongVal(currJobTimeStr);

                if (currJobTime < preJobTime) {
                    taskRefFirstJobMap.put(taskId, preJobJob);
                }
            }
        }

        List<BatchJob> resultList = Lists.newArrayList();
        //计算上一个周期的key,并判断是否存在-->存在则添加依赖关系
        for (Map.Entry<Long, BatchJobJob> entry : taskRefFirstJobMap.entrySet()) {
            BatchJobJob batchJobJob = entry.getValue();
            Long taskId = entry.getKey();
            BatchTaskShade batchTaskShade = batchTaskShadeService.getBatchTaskById(taskId, batchJobJob.getAppType());
            if (batchTaskShade == null) {
                logger.error("can't find task by id:{}.", taskId);
                continue;
            }

            String jobKey = batchJobJob.getJobKey();
            String cycTime = JobGraphBuilder.parseCycTimeFromJobKey(jobKey);
            String scheduleConf = batchTaskShade.getScheduleConf();
            try {
                ScheduleCron scheduleCron = ScheduleFactory.parseFromJson(scheduleConf);
                String prePeriodJobTriggerDateStr = JobGraphBuilder.getPrePeriodJobTriggerDateStr(cycTime, scheduleCron);
                String prePeriodJobKey = jobKey.substring(0, jobKey.lastIndexOf("_") + 1) + prePeriodJobTriggerDateStr;
                EScheduleType scheduleType = JobGraphBuilder.parseScheduleTypeFromJobKey(jobKey);
                BatchJob dbBatchJob = batchJobService.getJobByJobKeyAndType(prePeriodJobKey, scheduleType.getType());
                if (dbBatchJob != null) {
                    resultList.add(dbBatchJob);
                }

            } catch (Exception e) {
                logger.error("", e);
                continue;
            }
        }

        return resultList;

    }

    private Long getJobTaskIdFromJobKey(String jobKey) {
        String[] fields = jobKey.split("_");
        if (fields.length < 3) {
            return null;
        }
        Long taskShadeId = MathUtil.getLongVal(fields[fields.length - 2]);
        BatchTaskShade batchTaskShade = batchTaskShadeService.getById(taskShadeId);
        return Objects.isNull(batchTaskShade) ? null : batchTaskShade.getTaskId();
    }

    private String getJobTriggerTimeFromJobKey(String jobKey) {
        String[] fields = jobKey.split("_");
        if (fields.length < 3) {
            return null;
        }

        return fields[fields.length - 1];
    }

    public Long getTaskIdUnique(int appType, long taskId) {
        return ((long) appType << COUNT_BITS) + taskId;
    }


    public Twins<String, String> getCycTimeLimit() {
        Integer dayGap = environmentContext.getCycTimeDayGap();
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DATE, -dayGap);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        String startTime = sdf.format(calendar.getTime());
        calendar.add(Calendar.DATE, dayGap+1);
        String endTime = sdf.format(calendar.getTime());
        return new Twins<>(startTime, endTime);
    }




}
