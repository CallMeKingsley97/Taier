package com.dtstack.engine.mapper;

import com.dtstack.engine.domain.RdosEngineJobCache;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Reason:
 * Date: 2017/11/6
 * Company: www.dtstack.com
 * @author xuchao
 */

public interface RdosEngineJobCacheMapper {

    int insert(@Param("jobId")String jobId,  @Param("engineType") String engineType,
               @Param("computeType") Integer computeType, @Param("stage") int stage,
               @Param("jobInfo")String jobInfo, @Param("nodeAddress") String nodeAddress,
               @Param("jobName") String jobName, @Param("jobPriority") Long jobPriority,
               @Param("groupName") String groupName, @Param("jobResource") String jobResource);

    int delete(@Param("jobId")String jobId);

    RdosEngineJobCache getOne(@Param("jobId")String jobId);

    int updateStage(@Param("jobId") String jobId, @Param("stage") Integer stage,@Param("nodeAddress") String nodeAddress,
            @Param("jobPriority") Long jobPriority, @Param("groupName") String groupName);

    List<RdosEngineJobCache> listByStage(@Param("startId") Long id, @Param("nodeAddress") String nodeAddress, @Param("stage") Integer stage, @Param("engineType") String engineType);

    List<RdosEngineJobCache> getByJobIds(@Param("jobIds") List<String> jobIds);

    List<String> listNames(@Param("computeType") Integer computeType,@Param("jobName") String jobName);

    int countByStage(@Param("jobResource") String jobResource, @Param("stage") Integer stage, @Param("nodeAddress") String nodeAddress);

}
