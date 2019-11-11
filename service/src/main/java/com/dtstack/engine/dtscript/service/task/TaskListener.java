package com.dtstack.engine.dtscript.service.task;

import com.dtstack.engine.common.exception.ExceptionUtil;
import com.dtstack.engine.common.enums.EJobCacheStage;
import com.dtstack.engine.common.pojo.JobResult;
import com.dtstack.engine.dtscript.service.db.dao.RdosEngineJobDAO;
import com.dtstack.engine.dtscript.service.db.dao.RdosEngineJobCacheDAO;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobSubmitExecutor;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.dtscript.service.node.WorkNode;
import com.dtstack.engine.dtscript.service.util.TaskIdUtil;
import com.dtstack.engine.dtscript.service.zk.cache.ZkLocalCache;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 
 * Reason: TODO ADD REASON(可选)
 * Date: 2017年03月03日 下午1:25:18
 * Company: www.dtstack.com
 * @author sishu.yss
 *
 */
public class TaskListener implements Runnable{
	
	private static Logger logger = LoggerFactory.getLogger(TaskListener.class);
	
	private LinkedBlockingQueue<JobClient> queue;
	
	private RdosEngineJobDAO rdosbatchJobDAO = new RdosEngineJobDAO();

	private RdosEngineJobCacheDAO rdosEngineJobCacheDao = new RdosEngineJobCacheDAO();

	private ZkLocalCache zkLocalCache = ZkLocalCache.getInstance();

	public TaskListener(){
		queue = JobSubmitExecutor.getInstance().getQueueForTaskListener();
	}

	@Override
	public void run() {
		while(true){
			try {
				JobClient jobClient  = queue.take();

				if(RestartDealer.getInstance().checkAndRestartForSubmitResult(jobClient)){
					logger.warn("failed submit job restarting, jobId:{} jobResult:{} ...", jobClient.getTaskId(), jobClient.getJobResult());
					continue;
				}

				logger.info("success submit job to Engine, jobId:{} jobResult:{} ...", jobClient.getTaskId(), jobClient.getJobResult());

				//存储执行日志
				String zkTaskId = TaskIdUtil.getZkTaskId(jobClient.getComputeType().getType(), jobClient.getEngineType(), jobClient.getTaskId());

				if(StringUtils.isNotBlank(jobClient.getEngineTaskId())){
					JobResult jobResult = jobClient.getJobResult();
					String appId = jobResult.getData(JobResult.EXT_ID_KEY);
					rdosbatchJobDAO.updateJobEngineId(jobClient.getTaskId(), jobClient.getEngineTaskId(),appId);
					rdosbatchJobDAO.updateSubmitLog(jobClient.getTaskId(), jobClient.getJobResult().getJsonStr());
					WorkNode.getInstance().updateCache(jobClient, EJobCacheStage.IN_SUBMIT_QUEUE.getStage());
					jobClient.doStatusCallBack(RdosTaskStatus.SUBMITTED.getStatus());
					zkLocalCache.updateLocalMemTaskStatus(zkTaskId, RdosTaskStatus.SUBMITTED.getStatus());
				}else{
					rdosbatchJobDAO.submitFail(jobClient.getTaskId(), RdosTaskStatus.FAILED.getStatus(), jobClient.getJobResult().getJsonStr());
					zkLocalCache.updateLocalMemTaskStatus(zkTaskId, RdosTaskStatus.FAILED.getStatus());
					rdosEngineJobCacheDao.deleteJob(jobClient.getTaskId());
				}
			} catch (Throwable e) {
				logger.error("TaskListener run error:{}", ExceptionUtil.getErrorMessage(e));
			}
        }
	}

}
