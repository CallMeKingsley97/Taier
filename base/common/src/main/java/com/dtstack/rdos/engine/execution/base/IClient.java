package com.dtstack.rdos.engine.execution.base;

import com.dtstack.rdos.engine.execution.base.enums.RdosTaskStatus;
import com.dtstack.rdos.engine.execution.base.pojo.EngineResourceInfo;
import com.dtstack.rdos.engine.execution.base.pojo.JobResult;

import java.io.IOException;
import java.util.Properties;

/**
 * FIXME 添加新的方法的时候注意要对{@link ClientProxy}一起修改
 * Date: 2017/2/20
 * Company: www.dtstack.com
 * @author xuchao
 */

public interface IClient {

    void init(Properties prop) throws Exception;

    JobResult submitJob(JobClient jobClient);

    JobResult cancelJob(String jobId);

    RdosTaskStatus getJobStatus(String jobId) throws IOException;

	String getJobMaster();
	
	String getMessageByHttp(String path);

	String getJobLog(String jobId);

	EngineResourceInfo getAvailSlots();

}
