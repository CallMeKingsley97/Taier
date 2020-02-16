package com.dtstack.engine.flink.constrant;

import com.dtstack.engine.base.resource.EngineResourceInfo;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Created by sishu.yss on 2018/6/27.
 */
public class ExceptionInfoConstrant {

    private static Logger logger = LoggerFactory.getLogger(ExceptionInfoConstrant.class);

    /** Add memory restart */
    public final static String FLINK_TASK_LOST_ADDMEMORY_RESTART_EXCEPTION = "TaskManager was lost/killed";
    public final static String FLINK_ASSIGNED_SLOT_REMOVE_ADDMEMORY_RESTART_EXCEPTION = "org.apache.flink.util.FlinkException: The assigned slot container";
    public final static String FLINK_RELEASE_SHARED_SLOT_ADDMEMORY_RESTART_EXCEPTION = "Releasing shared slot parent";
    public final static String RESOURCE_OVER_LIMIT_ADDMEMORY_RESTART_EXCEPTION = EngineResourceInfo.LIMIT_RESOURCE_ERROR;

    /**  undo restart*/
    public final static String FLINK_GET_LOG_ERROR_UNDO_RESTART_EXCEPTION = "Failed to get the stopped task log, please check if job history is enabled";
    public final static String FLINK_UNALE_TO_GET_CLUSTERCLIENT_STATUS_EXCEPTION = "Unable to get ClusterClient status from Application Client";


    private static List<String> needAddMemoryRestartExceptions = Lists.newArrayList();

    static {
        try{
            Field[] fields = ExceptionInfoConstrant.class.getDeclaredFields();
            for(Field f:fields){
                String name = f.getName();
                if(name.indexOf("ADDMEMORY_RESTART_EXCEPTION")>=0){
                    needAddMemoryRestartExceptions.add(f.get(f.getName()).toString());
                }
            }
        }catch (Throwable e){
            logger.error("",e);
        }
    }

    /**
     * add mem
     */
    public static List<String> getNeedAddMemRestartException(){
        return needAddMemoryRestartExceptions;
    }
}
