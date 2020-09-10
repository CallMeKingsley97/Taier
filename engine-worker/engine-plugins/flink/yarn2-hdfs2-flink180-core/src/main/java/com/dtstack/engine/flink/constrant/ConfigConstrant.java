package com.dtstack.engine.flink.constrant;


/**
 * 
 * @author sishu.yss
 *
 */
public class ConfigConstrant {

    public static final String SPLIT = "_";

    public static final String SQL_ENV_PARALLELISM = "sql.env.parallelism";

    public static final String MR_JOB_PARALLELISM = "mr.job.parallelism";

    public static final String FLINK_TASK_RUN_MODE_KEY = "flinkTaskRunMode";

    public final static String JOBMANAGER_MEMORY_MB = "jobmanager.memory.mb";
    public final static String TASKMANAGER_MEMORY_MB = "taskmanager.memory.mb";
    public final static String CONTAINER = "container";
    public final static String SLOTS = "slots";

    public static final String JOBMANAGER_COMPONEN = "jobmanager";
    public static final String TASKMANAGER_COMPONEN = "taskmanager";

    /**
     * the minimum memory should be higher than the min heap cutoff
     */
    public final static int MIN_JM_MEMORY = 1024;
    public final static int MIN_TM_MEMORY = 1024;
    /**
     * plugin load by classpath or ship mode
     */
    public final static String FLINK_PLUGIN_LOAD_MODE = "pluginLoadMode";
    public static final String FLINK_PLUGIN_CLASSPATH_LOAD = "classpath";
    public static final String FLINK_PLUGIN_SHIPFILE_LOAD = "shipfile";

    public static final String KAFKA_SFTP_KEYTAB = "kafka.sftp.keytab";
    public static final String SECURITY_KERBEROS_LOGIN_KEYTAB= "security.kerberos.login.keytab";
}
