package com.dtstack.engine.flink.enums;

import com.dtstack.engine.common.exception.RdosDefineException;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2018/6/29
 */
public enum FlinkYarnMode {

    //yarn session模式
    SESSION,

    //flink 每一个job就是一个flink cluster 任务，部署+提交
    PER_JOB;

    public static FlinkYarnMode mode(String mode) {
        if (FlinkYarnMode.SESSION.name().equalsIgnoreCase(mode)) {
            return SESSION;
        } else if (FlinkYarnMode.PER_JOB.name().equalsIgnoreCase(mode)){
            return PER_JOB;
        }

        throw new RdosDefineException("not support mode: " + mode);
    }

    public static boolean isPerJob(FlinkYarnMode mode) {
        if (mode != null && mode == PER_JOB) {
            return true;
        }
        return false;
    }

}