package com.dtstack.rdos.engine.execution.sparkyarn;

import com.dtstack.rdos.common.util.MathUtil;
import com.dtstack.rdos.common.util.UnitConvertUtil;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.dtstack.rods.engine.execution.base.resource.AbstractYarnResourceInfo;

import java.util.Properties;

/**
 * spark yarn 资源相关
 * Date: 2017/11/30
 * Company: www.dtstack.com
 * @author xuchao
 */

public class SparkYarnResourceInfo extends AbstractYarnResourceInfo {

    private final static String DRIVER_CORE_KEY = "driver.core";

    private final static String DRIVER_MEM_KEY = "driver.memory";

    private final static String DRIVER_MEM_OVERHEAD_KEY = "yarn.driver.memoryOverhead";

    private final static String EXECUTOR_INSTANCES_KEY = "executor.instances";

    private final static String EXECUTOR_MEM_KEY = "executor.memory";

    private final static String EXECUTOR_CORES_KEY = "executor.cores";

    private final static String EXECUTOR_MEM_OVERHEAD_KEY = "yarn.executor.memoryOverhead";

    public final static int DEFAULT_CORES = 1;

    public final static int DEFAULT_INSTANCES = 1;

    public final static int DEFAULT_MEM = 512;

    public final static int DEFAULT_MEM_OVERHEAD = 384;

    @Override
    public boolean judgeSlots(JobClient jobClient) {

        Properties properties = jobClient.getConfProperties();
        int driverCores = DEFAULT_CORES;
        if(properties != null && properties.containsKey(DRIVER_CORE_KEY)){
            driverCores = MathUtil.getIntegerVal(properties.get(DRIVER_CORE_KEY));
        }
        int driverMem = DEFAULT_MEM;
        if(properties != null && properties.containsKey(DRIVER_MEM_KEY)){
            String setMemStr = properties.getProperty(DRIVER_MEM_KEY);
            driverMem = UnitConvertUtil.convert2MB(setMemStr);
        }
        int driverMemOverhead = DEFAULT_MEM_OVERHEAD;
        if(properties != null && properties.containsKey(DRIVER_MEM_OVERHEAD_KEY)){
            String setMemStr = properties.getProperty(DRIVER_MEM_OVERHEAD_KEY);
            driverMemOverhead = UnitConvertUtil.convert2MB(setMemStr);
        }
        driverMem += driverMemOverhead;

        if (!judgeYarnResource(1, driverCores, driverMem)) {
            return false;
        }

        int executorNum = DEFAULT_INSTANCES;
        if(properties != null && properties.containsKey(EXECUTOR_INSTANCES_KEY)){
            executorNum = MathUtil.getIntegerVal(properties.get(EXECUTOR_INSTANCES_KEY));
        }
        int executorCores = DEFAULT_CORES;
        if(properties != null && properties.containsKey(EXECUTOR_CORES_KEY)){
            executorCores = MathUtil.getIntegerVal(properties.get(EXECUTOR_CORES_KEY));
        }

        int executorMem = DEFAULT_MEM;
        if(properties != null && properties.containsKey(EXECUTOR_MEM_KEY)){
            String setMemStr = properties.getProperty(EXECUTOR_MEM_KEY);
            executorMem = UnitConvertUtil.convert2MB(setMemStr);
        }
        int executorMemOverhead = DEFAULT_MEM_OVERHEAD;
        if(properties != null && properties.containsKey(EXECUTOR_MEM_OVERHEAD_KEY)){
            String setMemStr = properties.getProperty(EXECUTOR_MEM_OVERHEAD_KEY);
            executorMemOverhead = UnitConvertUtil.convert2MB(setMemStr);
        }
        executorMem += executorMemOverhead;

        if (!judgeYarnResource(executorNum, executorCores, executorMem)) {
            return false;
        }

        return true;
    }
}
