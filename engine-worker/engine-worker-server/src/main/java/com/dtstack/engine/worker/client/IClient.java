package com.dtstack.engine.worker.client;

import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.pojo.JobResult;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2020/2/27
 */
public interface IClient {

    void init(Properties prop) throws Exception;

    JobResult submitJob(JobClient jobClient);

    JobResult cancelJob(JobIdentifier jobIdentifier);

    RdosTaskStatus getJobStatus(JobIdentifier jobIdentifier) throws IOException;

	String getJobMaster(JobIdentifier jobIdentifier);
	
	String getMessageByHttp(String path);

	String getJobLog(JobIdentifier jobIdentifier);

	boolean judgeSlots(JobClient jobClient);

	List<String> getContainerInfos(JobIdentifier jobIdentifier);

	String getCheckpoints(JobIdentifier jobIdentifier);

	String getDefaultPluginConfig();

}
