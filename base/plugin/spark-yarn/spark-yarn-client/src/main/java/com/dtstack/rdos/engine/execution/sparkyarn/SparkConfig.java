package com.dtstack.rdos.engine.execution.sparkyarn;

import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Created by sishu.yss on 2018/3/9.
 */
public class SparkConfig {

    private static Logger logger = LoggerFactory.getLogger(SparkConfig.class);

    private static String spark_executor_memory = "512m";

    private static String spark_cores_max = "1";

    private static String spark_executor_instances = "1";

    private static String spark_executor_cores = "1";

    private static String spark_submit_deployMode="cluster";

    private static String spark_master="yarn";

    //executor 向driver 发送心跳的间隔时间
    private static final String spark_executor_heartbeartinterval = "600s";

    //spark 所有网络传输的超时时间
    private static final String spark_network_timeout = "600s";

    //rpc 请求操作在超时前等待的持续时间
    private static final String spark_rpc_ask_timeout = "600s";

    //如果设置为 "true" , 则执行任务的推测执行. 这意味着如果一个或多个任务在一个阶段中运行缓慢, 则将重新启动它们
    private static final String spark_speculation = "true";

    private static final String spark_yarn_maxAppAttempts = "0";

    public static void initDefautlConf(SparkConf sparkConf){
        try{
            Field[] fields = SparkConfig.class.getDeclaredFields();
            for(Field field:fields){
                String name = field.getName().replaceAll("_",".");
                field.setAccessible(true);
                sparkConf.set(name,String.valueOf(field.get(SparkConfig.class)));
            }
        }catch(Exception e){
            logger.error("",e);
        }

    }
}
