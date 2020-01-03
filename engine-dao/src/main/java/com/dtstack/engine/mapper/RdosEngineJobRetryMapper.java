package com.dtstack.engine.mapper;

import com.dtstack.engine.domain.RdosEngineJobRetry;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author toutian
 */
public interface RdosEngineJobRetryMapper {

	void insert(RdosEngineJobRetry rdosEngineJobRetry);

	List<RdosEngineJobRetry> getJobRetryByJobId(@Param("jobId") String jobId);

    String getRetryTaskParams(@Param("jobId")String jobId, @Param("retryNum") int retrynum);

	void removeByJobId(@Param("jobId")String jobId);
}
