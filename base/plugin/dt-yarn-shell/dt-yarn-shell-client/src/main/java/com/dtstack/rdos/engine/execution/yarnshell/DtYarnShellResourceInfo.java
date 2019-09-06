package com.dtstack.rdos.engine.execution.yarnshell;


import com.dtstack.rdos.commom.exception.ClientArgumentException;
import com.dtstack.rdos.commom.exception.LimitResourceException;
import com.dtstack.rdos.commom.exception.RdosException;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.dtstack.rods.engine.execution.base.resource.AbstractYarnResourceInfo;
import com.dtstack.yarn.client.ClientArguments;
import com.google.common.collect.Lists;

import java.util.List;


/**
 * 用于存储从dt-yarn-shell上获取的资源信息
 * Date: 2018/9/14
 * Company: www.dtstack.com
 *
 * @author jingzhen
 */
public class DtYarnShellResourceInfo extends AbstractYarnResourceInfo {


    @Override
    public boolean judgeSlots(JobClient jobClient) {
        ClientArguments clientArguments;
        try {
            String[] args = DtYarnShellUtil.buildPythonArgs(jobClient);
            clientArguments = new ClientArguments(args);
        } catch (Exception e) {
            throw new ClientArgumentException(e);
        }

        int amCores = clientArguments.getAmCores();
        int amMem = clientArguments.getAmMem();

        int workerCores = clientArguments.getWorkerVCores();
        int workerMem = clientArguments.getWorkerMemory();
        int workerNum = clientArguments.getWorkerNum();

        return this.judgeResource(amCores, amMem, workerNum, workerCores, workerMem);
    }

    private boolean judgeResource(int amCores, int amMem, int workerNum, int workerCores, int workerMem) {
        if (workerNum == 0 || workerMem == 0 || workerCores == 0) {
            throw new LimitResourceException("Yarn task resource configuration error，" +
                    "instance：" + workerNum + ", coresPerInstance：" + workerCores + ", memPerInstance：" + workerMem);
        }
        if (totalFreeCore == 0 || totalFreeMem == 0) {
            return false;
        }

        List<InstanceInfo> instanceInfos = Lists.newArrayList(
                InstanceInfo.newRecord(1, amCores, amMem),
                InstanceInfo.newRecord(workerNum, workerCores, workerMem));
        return judgeYarnResource(instanceInfos);
    }
}
