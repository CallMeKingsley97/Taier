package com.dtstack.engine.master.impl;

import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.enums.EJobCacheStage;
import com.dtstack.engine.common.pojo.ParamAction;
import com.dtstack.engine.common.util.PublicUtil;
import com.dtstack.engine.dao.EngineJobCacheDao;
import com.dtstack.engine.domain.EngineJobCache;
import com.dtstack.engine.master.WorkNode;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.dtstack.engine.master.executor.JobExecutorTrigger;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2019/10/29
 */
@Service
public class NodeRecoverService {

    private static final Logger logger = LoggerFactory.getLogger(NodeRecoverService.class);

    @Autowired
    private JobExecutorTrigger jobExecutorTrigger;

    @Autowired
    private EnvironmentContext environmentContext;

    @Autowired
    private EngineJobCacheDao engineJobCacheDao;

    @Autowired
    private WorkNode workNode;

    /**
     * 接收 master 节点容灾后的消息
     */
    public void masterTriggerNode() {
        logger.info("--- accept masterTriggerNode");
        try {
            jobExecutorTrigger.recoverOtherNode();
            logger.info("--- deal recoverOtherNode done ------");
            recoverJobCaches();
        } catch (Exception e) {
            logger.error("", e);
        }
    }

    public void recoverJobCaches() {
        String localAddress = environmentContext.getLocalAddress();
        try {
            long startId = 0L;
            while (true) {
                List<EngineJobCache> jobCaches = engineJobCacheDao.listByFailover(startId, localAddress, EJobCacheStage.SUBMITTED.getStage());
                if (CollectionUtils.isEmpty(jobCaches)) {
                    break;
                }
                for (EngineJobCache jobCache : jobCaches) {
                    try {
                        ParamAction paramAction = PublicUtil.jsonStrToObject(jobCache.getJobInfo(), ParamAction.class);
                        JobClient jobClient = new JobClient(paramAction);
                        workNode.afterSubmitJob(jobClient);
                        startId = jobCache.getId();
                    } catch (Exception e) {
                        logger.error("", e);
                        //数据转换异常--打日志
                        workNode.dealSubmitFailJob(jobCache.getJobId(), "This task stores information exception and cannot be converted." + e.toString());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("----broker:{} RecoverDealer error:{}", localAddress, e);
        }
    }

}
