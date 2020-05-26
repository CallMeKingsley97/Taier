package com.dtstack.engine.master.job.impl;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import com.dtstack.engine.api.domain.ScheduleJob;
import com.dtstack.engine.api.domain.ScheduleTaskShade;
import com.dtstack.engine.api.dto.ScheduleTaskParamShade;
import com.dtstack.engine.common.constrant.TaskConstant;
import com.dtstack.engine.common.enums.EScheduleType;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.common.util.RetryUtil;
import com.dtstack.engine.dao.ScheduleJobDao;
import com.dtstack.engine.master.akka.WorkerOperator;
import com.dtstack.engine.master.enums.EComponentType;
import com.dtstack.engine.master.enums.MultiEngineType;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.dtstack.engine.master.impl.ClusterService;
import com.dtstack.engine.master.impl.ComponentService;
import com.dtstack.engine.master.job.IJobStartTrigger;
import com.dtstack.engine.master.scheduler.JobParamReplace;
import com.dtstack.schedule.common.enums.*;
import com.dtstack.schedule.common.metric.batch.IMetric;
import com.dtstack.schedule.common.metric.batch.MetricBuilder;
import com.dtstack.schedule.common.metric.prometheus.PrometheusMetricQuery;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author yuebai
 * @date 2019-11-05
 */
@Service
public class BatchHadoopJobStartTrigger implements IJobStartTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(BatchHadoopJobStartTrigger.class);

    @Autowired
    private JobParamReplace jobParamReplace;

    @Autowired
    private ScheduleJobDao scheduleJobDao;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private EnvironmentContext environmentContext;

    @Autowired
    private ComponentService componentService;

    @Autowired
    private WorkerOperator workerOperator;

    private DateTimeFormatter dayFormatterAll = DateTimeFormat.forPattern("yyyyMMddHHmmss");

    private static final String KEY_OPEN_CHECKPOINT = "openCheckpoint";

    private static final String KEY_CHECKPOINT_STATE_BACKEND = "flink.checkpoint.stateBackend";

    private static final String KEY_CHECKPOINT_INTERVAL = "flink.checkpoint.interval";

    private static final String DEFAULT_VAL_CHECKPOINT_INTERVAL = "300000";

    private static final String JOB_SAVEPOINT_ARGS_TEMPLATE = "-confProp %s";

    private static final String JOB_ARGS_TEMPLATE = "-jobid %s -job %s";

    private static final String KEY_SAVEPOINT = "state.checkpoints.dir";

    private static final String ADD_PART_TEMP = "alter table %s add partition(task_name='%s',time='%s')";

    @Override
    public void readyForTaskStartTrigger(Map<String, Object> actionParam, ScheduleTaskShade taskShade, ScheduleJob scheduleJob) throws Exception {

        //info信息中数据
        String sql = (String) actionParam.get("sqlText");
        sql = sql == null ? "" : sql;
        String taskParams = taskShade.getTaskParams();

        List<ScheduleTaskParamShade> taskParamsToReplace = JSONObject.parseArray((String) actionParam.get("taskParamsToReplace"), ScheduleTaskParamShade.class);

        String taskExeArgs = null;

        if (EScheduleJobType.SPARK_SQL.getVal().equals(taskShade.getTaskType()) || EScheduleJobType.HIVE_SQL.getVal().equals(taskShade.getTaskType())
                || EScheduleJobType.CARBON_SQL.getVal().equals(taskShade.getTaskType())) {
            sql = jobParamReplace.paramReplace(sql, taskParamsToReplace, scheduleJob.getCycTime());
        } else if (EScheduleJobType.SYNC.getVal().equals(taskShade.getTaskType())) {
            String job = (String) actionParam.get("job");
            if (StringUtils.isBlank(job)) {
                throw new RdosDefineException("数据同步信息不能为空");
            }

            //替换系统参数
            job = jobParamReplace.paramReplace(job, taskParamsToReplace, scheduleJob.getCycTime());

            Integer sourceType = (Integer) actionParam.getOrDefault("dataSourceType", DataSourceType.HIVE.getVal());
            String engineIdentity = (String) actionParam.get("engineIdentity");
            // 获取脏数据存储路径
            try {
                job = this.replaceTablePath(true, job, taskShade.getName(), sourceType, engineIdentity,taskShade.getDtuicTenantId());
            } catch (Exception e) {
                LOG.error("create dirty table  partition error {}", scheduleJob.getJobId(), e);
            }

            try {
                // 创建数据同步目标表分区
                job = this.createPartition(taskShade.getDtuicTenantId(), job, sourceType, actionParam);
            } catch (Exception e) {
                LOG.error("create partition error {}", scheduleJob.getJobId(), e);
                throw e;
            }


            // 查找上一次同步位置
            if (scheduleJob.getType() == EScheduleType.NORMAL_SCHEDULE.getType()) {
                job = getLastSyncLocation(taskShade.getTaskId(), job, scheduleJob.getCycTime(),taskShade.getDtuicTenantId());
            } else {
                job = removeIncreConf(job);
            }

            // 构造savepoint参数
            String savepointArgs = null;
            if (isRestore(job)) {
                String savepointPath = this.getSavepointPath(taskShade.getTenantId());
                savepointArgs = this.buildSyncTaskExecArgs(savepointPath, taskParams);

                taskParams += String.format(" \n %s=%s", KEY_OPEN_CHECKPOINT, Boolean.TRUE.toString());
            }

            job = URLEncoder.encode(job.replace(TaskConstant.JOB_ID, scheduleJob.getJobId()), Charsets.UTF_8.name());
            taskExeArgs = String.format(JOB_ARGS_TEMPLATE, scheduleJob.getJobName(), job);
            if (savepointArgs != null) {
                taskExeArgs += " " + savepointArgs;
            }
        } else if (taskShade.getEngineType().equals(ScheduleEngineType.Learning.getVal())
                || taskShade.getEngineType().equals(ScheduleEngineType.Shell.getVal())
                || taskShade.getEngineType().equals(ScheduleEngineType.DtScript.getVal())
                || taskShade.getEngineType().equals(ScheduleEngineType.Spark.getVal())
                || taskShade.getEngineType().equals(ScheduleEngineType.Python2.getVal())
                || taskShade.getEngineType().equals(ScheduleEngineType.Python3.getVal())) {
            //提交
            String exeArgs = (String) actionParam.get("exeArgs");
            //替换系统参数
            String content = jobParamReplace.paramReplace(exeArgs, taskParamsToReplace, scheduleJob.getCycTime());
            //替换jobId
            taskExeArgs = content.replace(TaskConstant.JOB_ID, scheduleJob.getJobId());
            //提交上传路径
            if (StringUtils.isNotBlank(taskExeArgs) && taskExeArgs.contains(TaskConstant.UPLOADPATH)) {
                taskExeArgs = taskExeArgs.replace(TaskConstant.UPLOADPATH, this.uploadSqlTextToHdfs(scheduleJob.getDtuicTenantId(), taskShade.getSqlText(), taskShade.getTaskType(),
                        taskShade.getName(), taskShade.getTenantId(), taskShade.getProjectId(), taskParamsToReplace, scheduleJob.getCycTime()));
            } else if (StringUtils.isNotBlank(sql) && sql.contains(TaskConstant.UPLOADPATH)) {
                //上传代码到hdfs
                String uploadPath = this.uploadSqlTextToHdfs(scheduleJob.getDtuicTenantId(), taskShade.getSqlText(), taskShade.getTaskType(),
                        taskShade.getName(), taskShade.getTenantId(), taskShade.getProjectId(), taskParamsToReplace, scheduleJob.getCycTime());
                sql = sql.replace(TaskConstant.UPLOADPATH, uploadPath);
                taskExeArgs = taskExeArgs.replace(TaskConstant.UPLOADPATH, uploadPath);
            }

        }

        if (taskExeArgs != null) {
            //替换jobId
            taskExeArgs = taskExeArgs.replace(TaskConstant.JOB_ID, scheduleJob.getJobId());
            actionParam.put("exeArgs", taskExeArgs);
        }
        //统一替换下sql
        sql = jobParamReplace.paramReplace(sql, taskParamsToReplace, scheduleJob.getCycTime());

        actionParam.put("sqlText", sql);
        actionParam.put("taskParams", taskParams);
        //engine 不需要用到的参数 去除
        actionParam.remove("taskParamsToReplace");
    }


    /**
     * 创建脏数据表的分区数据
     *
     * @param saveDirty
     * @param sqlText
     * @param taskName
     * @param tableType
     * @return
     * @throws Exception
     */
    public String replaceTablePath(boolean saveDirty, String sqlText, String taskName, Integer tableType, String db, Long dtuicTenantId) throws Exception {
        if (StringUtils.isBlank(db)) {
            return sqlText;
        }
        JSONObject sqlObject = JSONObject.parseObject(sqlText);
        JSONObject job = sqlObject.getJSONObject("job");
        JSONObject setting = job.getJSONObject("setting");

        if (setting.containsKey("dirty")) {

            if (!saveDirty) {
                setting.remove("dirty");
                return sqlObject.toJSONString();
            }

            JSONObject dirty = setting.getJSONObject("dirty");
            String tableName = dirty.getString("tableName");
            String path = null;

            if (StringUtils.isNotEmpty(tableName)) {
                //任务提交到task 之前 脏数据表 必须要在 ide 创建
                if (!tableName.contains(".")) {
                    tableName = String.format("%s.%s", db, tableName);
                }
                Long time = Timestamp.valueOf(LocalDateTime.now()).getTime();
                String alterSql = String.format(ADD_PART_TEMP, tableName, taskName, time);
                String location = "";
                if (ETableType.IMPALA.getType() == tableType) {
                    String jdbcInfo = clusterService.impalaInfo(dtuicTenantId, true);
                    JSONObject jdbcInfoObject = JSONObject.parseObject(jdbcInfo);
                    JSONObject pluginInfo = new JSONObject();
                    pluginInfo.put("dbUrl", jdbcInfoObject.getString("jdbcUrl"));
                    pluginInfo.put("userName", jdbcInfoObject.getString("username"));
                    pluginInfo.put("pwd", jdbcInfoObject.getString("password"));
                    pluginInfo.put("driverClassName", DataBaseType.Impala.getDriverClassName());
                    workerOperator.executeQuery(DataBaseType.Impala.getTypeName(), pluginInfo.toJSONString(), alterSql, db);
                    location = this.getTableLocation(pluginInfo, db, tableName, String.format("DESCRIBE formatted %s", tableName));
                } else if (ETableType.HIVE.getType() == tableType) {
                    String jdbcInfo = clusterService.hiveInfo(dtuicTenantId, true);
                    JSONObject jdbcInfoObject = JSONObject.parseObject(jdbcInfo);
                    JSONObject pluginInfo = new JSONObject();
                    pluginInfo.put("dbUrl", jdbcInfoObject.getString("jdbcUrl"));
                    pluginInfo.put("userName", jdbcInfoObject.getString("username"));
                    pluginInfo.put("pwd", jdbcInfoObject.getString("password"));
                    pluginInfo.put("driverClassName", DataBaseType.HIVE.getDriverClassName());
                    workerOperator.executeQuery(DataBaseType.HIVE.getTypeName(), pluginInfo.toJSONString(), alterSql, db);
                    location = this.getTableLocation(pluginInfo, db, tableName, String.format("desc formatted %s", tableName));
                }
                String partName = String.format("task_name=%s/time=%s", taskName, time);
                path = location + "/" + partName;

                dirty.put("path", path);
                setting.put("dirty", dirty);
                job.put("setting", setting);
                sqlObject.put("job", job);
            }
        }
        return sqlObject.toJSONString();
    }

    public String getTableLocation(JSONObject pluginInfo, String dbName, String tableName,String sql) throws Exception {
        String location = null;
        List<List<Object>> result = workerOperator.executeQuery(DataBaseType.Impala.getTypeName(),pluginInfo.toJSONString(), sql,dbName);
        Iterator var6 = result.iterator();

        while(var6.hasNext()) {
            List<Object> objects = (List)var6.next();
            if (objects.get(0).toString().contains("Location:")) {
                location = objects.get(1).toString();
            }
        }

        return location;
    }

    /**
     * 创建hive的分区
     */
    public String createPartition(Long dtuicTenantId, String job,Integer sourceType,Map<String, Object> actionParam) {
        JSONObject jobJSON = JSONObject.parseObject(job);
        JSONObject jobObj = jobJSON.getJSONObject("job");
        JSONObject parameter = jobObj.getJSONArray("content").getJSONObject(0)
                .getJSONObject("writer").getJSONObject("parameter");

        if (parameter.containsKey("partition") && parameter.containsKey("connection")) {
            JSONObject connection = parameter.getJSONArray("connection").getJSONObject(0);
            String username = parameter.containsKey("username") ? parameter.getString("username") : "";
            String password = parameter.containsKey("password") ? parameter.getString("password") : "";
            String jdbcUrl = connection.getString("jdbcUrl");
            String table = connection.getJSONArray("table").getString(0);

            String partition = parameter.getString("partition");
            Map<String, String> split = new HashMap<>();
            if (StringUtils.countMatches(partition, "/") == 1) {
                //pt=2020/04 分区中带/
                String[] splits = partition.split("=");
                split.put(splits[0], splits[1]);
            } else {
                //pt='asdfasd'/ds='1231231' 2级分区
                split = Splitter.on("/").withKeyValueSeparator("=").split(partition);
            }
            Map<String, String> formattedMap = new HashMap<>();
            for (Map.Entry<String, String> entry : split.entrySet()) {
                String value = entry.getValue();
                String key = entry.getKey();
                if (value.startsWith("'") || value.startsWith("\"")) {
                    value = value.substring(1);
                }
                if (value.endsWith("'") || value.endsWith("\"")) {
                    value = value.substring(0, value.length() - 1);
                }
                formattedMap.put(key, value);
            }
            // fileName  需要处理引号
            parameter.put("fileName",Joiner.on("").withKeyValueSeparator("=").join(formattedMap));
            String join = Joiner.on("',").withKeyValueSeparator("='").join(formattedMap);
            partition = join + "'";
            String sql = String.format("alter table %s add if not exists partition (%s)", table, partition);
            try {
                RetryUtil.executeWithRetry(() -> {
                    LOG.info("create partition dtuicTenantId {} {}", dtuicTenantId, sql);
                    JSONObject pluginInfo = new JSONObject();
                    pluginInfo.put("dbUrl",jdbcUrl);
                    pluginInfo.put("userName",username);
                    pluginInfo.put("pwd",password);
                    pluginInfo.put("driverClassName", DataSourceType.getBaseType(sourceType).getDriverClassName());
                    workerOperator.executeQuery(DataSourceType.getBaseType(sourceType).getTypeName(),pluginInfo.toJSONString(),sql,(String) actionParam.get("engineIdentity"));
                    cleanFileName(parameter);
                    return null;
                }, 3, 2000, false, Lists.newArrayList(SocketTimeoutException.class));
            } catch (Exception e) {
                LOG.error("create partition error", e);
                throw new RdosDefineException("create partition error:" + e.getMessage());
            }
        }
        return jobJSON.toJSONString();
    }


    /**
     * 查找上一次同步位置 通过prometheus
     *
     * @return
     */
    private String getLastSyncLocation(Long taskId, String jobContent, String cycTime,Long dtuicTenantId) {
        JSONObject jsonJob = JSONObject.parseObject(jobContent);

        Timestamp time = new Timestamp(dayFormatterAll.parseDateTime(cycTime).toDate().getTime());
        // 查找上一次成功的job
        ScheduleJob job =  scheduleJobDao.getByTaskIdAndStatusOrderByIdLimit(taskId, RdosTaskStatus.FINISHED.getStatus(), time);
        if (job != null && StringUtils.isNotEmpty(job.getEngineJobId())) {
            try {
                JSONObject reader = (JSONObject) JSONPath.eval(jsonJob, "$.job.content[0].reader");
                Object increCol = JSONPath.eval(reader, "$.parameter.increColumn");
                if (Objects.nonNull(increCol) && Objects.nonNull(job.getExecStartTime()) && Objects.nonNull(job.getExecEndTime())) {
                    String lastEndLocation = this.queryLastLocation(dtuicTenantId, job.getEngineJobId(), job.getExecStartTime().getTime(), job.getExecEndTime().getTime());
                    LOG.info("last job {} applicationId {} startTime {} endTim {} location {}", job.getJobId(), job.getEngineJobId(), job.getExecStartTime(), job.getExecEndTime(), lastEndLocation);
                    reader.getJSONObject("parameter").put("startLocation", lastEndLocation);
                }

            } catch (Exception e) {
                LOG.warn("上游任务没有增量配置:", job.getEngineLog());
            }
        }

        return jsonJob.toJSONString();
    }

    public String queryLastLocation(Long dtUicTenantId, String jobId, long startTime, long endTime) {
        endTime = endTime + 1000 * 60;
        String enginePluginInfo = componentService.listConfigOfComponents(dtUicTenantId, MultiEngineType.HADOOP.getType());
        JSONObject jsonObject = JSONObject.parseObject(enginePluginInfo);
        JSONObject flinkJsonObject = jsonObject.getJSONObject(EComponentType.FLINK.getTypeCode() + "");
        String prometheusHost = flinkJsonObject.getString("prometheusHost");
        String prometheusPort = flinkJsonObject.getString("prometheusPort");
        //prometheus的配置信息 从控制台获取
        PrometheusMetricQuery prometheusMetricQuery = new PrometheusMetricQuery(String.format("%s:%s", prometheusHost, prometheusPort));
        IMetric numReadMetric = MetricBuilder.buildMetric("endLocation", jobId, startTime, endTime, prometheusMetricQuery);
        if (numReadMetric != null) {
            String startLocation = String.valueOf(numReadMetric.getMetric());
            if (StringUtils.isEmpty(startLocation) || "0".equalsIgnoreCase(startLocation)) {
                return null;
            }
            return String.valueOf(numReadMetric.getMetric());
        }
        return null;
    }

    public void cleanFileName(JSONObject parameter) {
        String jobPartition = parameter.getString("fileName").replaceAll("'", "").replaceAll("\"", "").replaceAll(" ", "");
        parameter.put("fileName", jobPartition);
    }


    private String removeIncreConf(String jobContent) {
        JSONObject jobJson = JSONObject.parseObject(jobContent);
        JSONPath.remove(jobJson, "$.job.content[0].reader.parameter.increColumn");
        JSONPath.remove(jobJson, "$.job.content[0].reader.parameter.startLocation");

        return jobJson.toJSONString();
    }

    private boolean isRestore(String job) {
        JSONObject jobJson = JSONObject.parseObject(job);
        Object isRestore = JSONPath.eval(jobJson, "$.job.setting.restore.isRestore");
        return BooleanUtils.toBoolean(String.valueOf(isRestore));
    }

    /**
     * 获取flink任务checkpoint的存储路径
     *
     * @param tenantId 租户id
     * @return checkpoint存储路径
     */
    private String getSavepointPath(Long tenantId) {
        String clusterInfoStr = clusterService.clusterInfo(tenantId);
        JSONObject clusterJson = JSONObject.parseObject(clusterInfoStr);
        JSONObject flinkConf = clusterJson.getJSONObject("flinkConf");
        if (!flinkConf.containsKey(KEY_SAVEPOINT)) {
            return null;
        }

        String savepointPath = flinkConf.getString(KEY_SAVEPOINT);
        LOG.info("savepoint path:{}", savepointPath);

        if (StringUtils.isEmpty(savepointPath)) {
            throw new RdosDefineException("savepoint path can not be null");
        }

        return savepointPath;
    }


    private String buildSyncTaskExecArgs(String savepointPath, String taskParams) throws Exception {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(taskParams.getBytes("UTF-8")));
        String interval = properties.getProperty(KEY_CHECKPOINT_INTERVAL, DEFAULT_VAL_CHECKPOINT_INTERVAL);

        JSONObject confProp = new JSONObject();
        confProp.put(KEY_CHECKPOINT_STATE_BACKEND, savepointPath);
        confProp.put(KEY_CHECKPOINT_INTERVAL, interval);
        return String.format(JOB_SAVEPOINT_ARGS_TEMPLATE, URLEncoder.encode(confProp.toJSONString(), Charsets.UTF_8.name()));
    }


    private String uploadSqlTextToHdfs(Long dtuicTenantId, String content, Integer taskType, String taskName, Long tenantId, Long projectId,
                                       List<ScheduleTaskParamShade> taskParamShades, String cycTime) {
        String hdfsPath = null;
        try {

            //content统一处理参数
            if (StringUtils.isNotBlank(content) && CollectionUtils.isNotEmpty(taskParamShades)) {
                content = jobParamReplace.paramReplace(content, taskParamShades, cycTime);
            }
            // shell任务，创建脚本文件
            String fileName = null;
            if (taskType.equals(EScheduleJobType.SHELL.getVal())) {
                fileName = String.format("shell_%s_%s_%s_%s.sh", tenantId, projectId,
                        taskName, System.currentTimeMillis());
            } else if (taskType.equals(EScheduleJobType.PYTHON.getVal()) ||
                    taskType.equals(EScheduleJobType.NOTEBOOK.getVal())) {
                fileName = String.format("python_%s_%s_%s_%s.py", tenantId, projectId,
                        taskName, System.currentTimeMillis());
            } else if (taskType.equals(EScheduleJobType.DEEP_LEARNING.getVal())) {
                fileName = String.format("learning_%s_%s_%s_%s.py", tenantId, projectId,
                        taskName, System.currentTimeMillis());
            } else if (taskType.equals(EScheduleJobType.SPARK_PYTHON.getVal())) {
                fileName = String.format("pyspark_%s_%s_%s_%s.py", tenantId, projectId,
                        taskName, System.currentTimeMillis());
            }

            if (fileName != null) {
                hdfsPath = environmentContext.getHdfsTaskPath() + fileName;
                if (taskType.equals(EScheduleJobType.SHELL.getVal())) {
                    content = content.replaceAll("\r\n", System.getProperty("line.separator"));
                }

                JSONObject pluginInfoWithComponentType = componentService.getPluginInfoWithComponentType(dtuicTenantId, EComponentType.HDFS);
                String typeName = pluginInfoWithComponentType.getString(ComponentService.TYPE_NAME);
                String hdfsUploadPath = workerOperator.uploadStringToHdfs(typeName, pluginInfoWithComponentType.toJSONString(), content, hdfsPath);
                if(StringUtils.isBlank(hdfsUploadPath)){
                    throw new RdosDefineException("Update task to HDFS failure hdfsUploadPath is blank");
                }
                return hdfsUploadPath;
            }
        } catch (Exception e) {
            LOG.error("", e);
            throw new RdosDefineException("Update task to HDFS failure:" + e.getMessage());
        }
        throw new RdosDefineException("Update task to HDFS failure:");
    }
}
