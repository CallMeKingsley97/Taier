package com.dtstack.engine.dummy;

import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.pojo.JobResult;
import com.dtstack.engine.worker.client.AbstractClient;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Properties;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2020/4/13
 */
public class DummyClient extends AbstractClient {
    @Override
    public void init(Properties prop) throws Exception {
    }


    @Override
    public String getJobLog(JobIdentifier jobId) {
        return StringUtils.EMPTY;
    }

    @Override
    public boolean judgeSlots(JobClient jobClient) {
        return true;
    }

    @Override
    public JobResult cancelJob(JobIdentifier jobIdentifier) {
        return JobResult.createSuccessResult(jobIdentifier.getTaskId(), jobIdentifier.getEngineJobId());
    }

    @Override
    public RdosTaskStatus getJobStatus(JobIdentifier jobIdentifier) throws IOException {
        return RdosTaskStatus.FINISHED;
    }

    @Override
    public String getJobMaster(JobIdentifier jobIdentifier) {
        return StringUtils.EMPTY;
    }

    @Override
    public String getMessageByHttp(String path) {
        return StringUtils.EMPTY;
    }

    @Override
    protected JobResult processSubmitJobWithType(JobClient jobClient) {
        return JobResult.createSuccessResult(jobClient.getTaskId(), jobClient.getTaskId());
    }
}
