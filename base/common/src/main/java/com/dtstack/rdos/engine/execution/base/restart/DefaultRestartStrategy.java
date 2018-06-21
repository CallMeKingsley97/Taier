package com.dtstack.rdos.engine.execution.base.restart;


import com.dtstack.rdos.engine.execution.base.IClient;

public class DefaultRestartStrategy extends IRestartStrategy {
    @Override
    public boolean checkFailureForEngineDown(String msg) {
        return false;
    }

    @Override
    public boolean checkNOResource(String msg) {
        return false;
    }

    @Override
    public boolean checkCanRestart(String jobId,String engineJobId, IClient client) {
        return false;
    }

    @Override
    public boolean checkCanRestart(String jobId, String msg) {
        return false;
    }

}
