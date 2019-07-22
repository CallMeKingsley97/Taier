package com.dtstack.rdos.engine.execution.base.restart;

/**
 *
 * 根据日志获取重启策略，重启策略就是调整任务绑定的执行参数
 *
 * @description:
 * @author: maqi
 * @create: 2019/07/16 19:50
 */
public interface IJobRestartStrategy {


    String restart(String taskParam, int retryNum);

}