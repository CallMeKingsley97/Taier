package com.dtstack.rdos.engine.service.zk.task;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dtstack.rdos.engine.execution.base.CustomThreadFactory;
import com.dtstack.rdos.engine.service.node.MasterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dtstack.rdos.commom.exception.ExceptionUtil;
import com.dtstack.rdos.common.util.PublicUtil;
import com.dtstack.rdos.engine.service.zk.ZkDistributed;


/**
 * Date: 2017年03月07日 下午1:16:37
 * Company: www.dtstack.com
 *
 * @author sishu.yss
 */
public class MasterListener implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MasterListener.class);

    private AtomicBoolean isMaster = new AtomicBoolean(false);

    private ZkDistributed zkDistributed = ZkDistributed.getZkDistributed();

    private final static int CHECK_INTERVAL = 500;

    private int logOutput = 0;

    public MasterListener() {
        ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(1, new CustomThreadFactory("MasterListener"));
        scheduledService.scheduleWithFixedDelay(
                this,
                0,
                CHECK_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
        try {
            logOutput++;
            isMaster.getAndSet(zkDistributed.setMaster());
            MasterNode.getInstance().setIsMaster(isMaster.get());

            if (PublicUtil.count(logOutput, 15)) {
                logger.warn("MasterListener start again...");
                if (isMaster()) {
                    logger.warn("i am is master...");
                }
            }
        } catch (Throwable e) {
            logger.error("MasterCheck error:{}", ExceptionUtil.getErrorMessage(e));
        }
    }

    public boolean isMaster() {
        return isMaster.get();
    }
}
