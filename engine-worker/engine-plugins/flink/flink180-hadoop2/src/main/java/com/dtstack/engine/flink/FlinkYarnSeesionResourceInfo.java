package com.dtstack.engine.flink;

import com.dtstack.engine.common.util.MathUtil;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.base.resource.AbstractFlinkResourceInfo;
import com.dtstack.engine.flink.constrant.ConfigConstrant;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 用于存储从flink上获取的资源信息
 * Date: 2017/11/24
 * Company: www.dtstack.com
 *
 * @author xuchao
 */

public class FlinkYarnSeesionResourceInfo extends AbstractFlinkResourceInfo {

    private static final Logger logger = LoggerFactory.getLogger(FlinkYarnSeesionResourceInfo.class);

    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public FlinkYarnSeesionResourceInfo() {
    }

    @Override
    public boolean judgeSlots(JobClient jobClient) {
        return judgeYarnSeesionResource(jobClient);
    }

    private boolean judgeYarnSeesionResource(JobClient jobClient) {
        int sqlEnvParallel = 1;
        int mrParallel = 1;

        if (jobClient.getConfProperties().containsKey(ConfigConstrant.SQL_ENV_PARALLELISM)) {
            sqlEnvParallel = MathUtil.getIntegerVal(jobClient.getConfProperties().get(ConfigConstrant.SQL_ENV_PARALLELISM));
        }

        if (jobClient.getConfProperties().containsKey(ConfigConstrant.MR_JOB_PARALLELISM)) {
            mrParallel = MathUtil.getIntegerVal(jobClient.getConfProperties().get(ConfigConstrant.MR_JOB_PARALLELISM));
        }

        return super.judgeFlinkSessionResource(sqlEnvParallel, mrParallel);
    }

    public FlinkYarnSeesionResourceInfo getFlinkSessionSlots(String message, int flinkSessionSlotCount){
        if(StringUtils.isNotBlank(message)){
            try{
                Map<String, Object> taskManagerInfo = OBJECT_MAPPER.readValue(message, Map.class);
                if(taskManagerInfo.containsKey("taskmanagers")){
                    List<Map<String, Object>> taskManagerList = (List<Map<String, Object>>) taskManagerInfo.get("taskmanagers");
                    if (taskManagerList.size()==0){
                        this.addNodeResource(new NodeResourceDetail("1", flinkSessionSlotCount, flinkSessionSlotCount));
                    }else {
                        int totalUsedSlots = 0;
                        for(Map<String, Object> tmp : taskManagerList){
                            int freeSlots = MapUtils.getIntValue(tmp,"freeSlots");
                            int slotsNumber = MapUtils.getIntValue(tmp, "slotsNumber");
                            totalUsedSlots += slotsNumber - freeSlots;
                        }
                        this.addNodeResource(new NodeResourceDetail("1", flinkSessionSlotCount - totalUsedSlots, flinkSessionSlotCount));
                    }
                }
            }catch (Exception e){
                logger.error("", e);
            }
        }
        return this;
    }

}
