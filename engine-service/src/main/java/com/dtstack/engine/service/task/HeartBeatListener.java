package com.dtstack.engine.service.task;

import com.dtstack.engine.common.exception.ExceptionUtil;
import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.service.data.BrokerHeartNode;
import com.dtstack.engine.service.zookeeper.ZkDistributed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dtstack.engine.common.util.PublicUtil;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * Date: 2017年03月07日 下午1:16:37
 * Company: www.dtstack.com
 *
 * @author sishu.yss
 */
public class HeartBeatListener implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HeartBeatListener.class);

    private final static int CHECK_INTERVAL = 1000;

    private ZkDistributed zkDistributed = ZkDistributed.getZkDistributed();

    private static HeartBeatListener listener = null;

    private int logOutput = 0;

    public static void init(){
        listener = new HeartBeatListener();
    }

    private HeartBeatListener() {
        ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(1, new CustomThreadFactory("HeartBeatListener"));
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
            BrokerHeartNode brokerHeartNode = BrokerHeartNode.initBrokerHeartNode();
            brokerHeartNode.setSeq(1L);
            brokerHeartNode.setAlive(true);
            zkDistributed.updateSynchronizedLocalBrokerHeartNode(zkDistributed.getLocalAddress(), brokerHeartNode, false);
            if (PublicUtil.count(logOutput, 10)) {
                logger.warn("HeartBeatListener start again...");
            }
        } catch (Throwable e) {
            logger.error(ExceptionUtil.getErrorMessage(e));
        }
    }
}
