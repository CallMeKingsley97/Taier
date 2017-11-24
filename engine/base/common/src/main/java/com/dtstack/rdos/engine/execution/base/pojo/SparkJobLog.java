package com.dtstack.rdos.engine.execution.base.pojo;

import com.dtstack.rdos.common.util.PublicUtil;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 从spark上获取的任务日志
 * Date: 2017/11/24
 * Company: www.dtstack.com
 * @ahthor xuchao
 */

public class SparkJobLog {

    private static final Logger logger = LoggerFactory.getLogger(SparkJobLog.class);

    private static final String TO_STRING_FORMAT = "{\"driverLog\": %s, \"appLog\": %s}";

    private List<SparkDetailLog> appLogList = Lists.newArrayList();

    private List<SparkDetailLog> driverLogList = Lists.newArrayList();

    public List<SparkDetailLog> getAppLogList() {
        return appLogList;
    }

    public void setAppLogList(List<SparkDetailLog> appLogList) {
        this.appLogList = appLogList;
    }

    public List<SparkDetailLog> getDriverLogList() {
        return driverLogList;
    }

    public void setDriverLogList(List<SparkDetailLog> driverLogList) {
        this.driverLogList = driverLogList;
    }

    public void addAppLog(String key, String value){
        SparkDetailLog detailLog = new SparkDetailLog(key, value);
        appLogList.add(detailLog);
    }

    public void addDriverLog(String key, String value){
        SparkDetailLog detailLog = new SparkDetailLog(key, value);
        driverLogList.add(detailLog);
    }

    class SparkDetailLog{

        private String id;

        private String value;

        SparkDetailLog(String id, String value){
            this.id = id;
            this.value = value;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @Override
    public String toString() {

        try{
            String driverLogStr = PublicUtil.objToString(driverLogList);
            String appLogStr = PublicUtil.objToString(appLogList);
            return String.format(TO_STRING_FORMAT, driverLogStr, appLogStr);

        }catch (Exception e){
            logger.error("", e);
        }

        return "";
    }

    public static void main(String[] args) {
        SparkJobLog sparkJobLog = new SparkJobLog();
        sparkJobLog.addDriverLog("driver-20171108161026-0001", "Launch Command:");
        sparkJobLog.addAppLog("worker-20171108155444-172.16.8.103-48751", "Using Spark's default log4j profile: org/apache/spark/log4j-defaults.properties\\n17/11/08 16:10:32 INFO CoarseGrainedExecutorBackend");
        System.out.println(sparkJobLog);
    }
}
