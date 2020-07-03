package com.dtstack.engine.master.impl;

import com.dtstack.engine.api.domain.EngineJobCache;
import com.dtstack.engine.api.domain.EngineJobCheckpoint;
import com.dtstack.engine.api.domain.ScheduleJob;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.http.PoolHttpClient;
import com.dtstack.engine.common.util.ApplicationWSParser;
import com.dtstack.engine.master.AbstractTest;
import com.dtstack.engine.master.akka.WorkerOperator;
import org.apache.commons.math3.util.Pair;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Date: 2020/6/5
 * Company: www.dtstack.com
 * @author xiuzhu
 */

@PrepareForTest({PoolHttpClient.class, WorkerOperator.class, ApplicationWSParser.class})
public class StreamTaskServiceTest extends AbstractTest {

	@Mock
	private WorkerOperator workerOperator;

	@Autowired
	@InjectMocks
	StreamTaskService streamTaskService;

	@Before
	public void setup() throws Exception{
		MockitoAnnotations.initMocks(this);
		PowerMockito.mockStatic(WorkerOperator.class);
		when(workerOperator.getJobMaster(any())).thenReturn("http://dtstack01:8088/");

		PowerMockito.mockStatic(PoolHttpClient.class);
		when(PoolHttpClient.get(any())).thenReturn("{\"app\":{\"amContainerLogs\":\"http://dtstack01:8088/ws/v1/cluster/apps/application_9527\"}}");

		PowerMockito.mockStatic(ApplicationWSParser.class);
		when(ApplicationWSParser.parserAmContainerPreViewHttp(any(), any()))
			.thenReturn(new Pair<>("http://dtstack01:8088/app/log", "1024"));
		when(ApplicationWSParser.getAmContainerLogsUrl(any()))
			.thenReturn("http://dtstack01:8088/app/container/log");

	}

	@Test
	@Transactional(isolation = Isolation.READ_UNCOMMITTED)
	@Rollback
	public void testGetCheckPoint() {
		EngineJobCheckpoint engineJobCheckpoint = dataCollection.getEngineJobCheckpoint();

		Long triggerStart = engineJobCheckpoint.getCheckpointTrigger().getTime() - 1;
		Long triggerEnd = engineJobCheckpoint.getCheckpointTrigger().getTime() + 1;
		List<EngineJobCheckpoint> engineJobCheckpoints = streamTaskService.getCheckPoint(
			engineJobCheckpoint.getTaskId(), triggerStart, triggerEnd);
		Assert.notNull(engineJobCheckpoints);
		Assert.isTrue(engineJobCheckpoints.size() > 0);
	}

	@Test
	@Transactional(isolation = Isolation.READ_UNCOMMITTED)
	@Rollback
	public void testGetByTaskIdAndEngineTaskId() {
		EngineJobCheckpoint engineJobCheckpoint = dataCollection.getEngineJobCheckpoint();

		EngineJobCheckpoint resJobCheckpoint = streamTaskService.getByTaskIdAndEngineTaskId(
			engineJobCheckpoint.getTaskId(), engineJobCheckpoint.getCheckpointId());
		Assert.notNull(resJobCheckpoint);
	}

	@Test
	@Transactional(isolation = Isolation.READ_UNCOMMITTED)
	@Rollback
	public void testGetEngineStreamJob() {
		ScheduleJob streamJob = dataCollection.getScheduleJobStream();

		List<String> taskIds = Arrays.asList(new String[]{streamJob.getJobId()});
		List<ScheduleJob> jobs = streamTaskService.getEngineStreamJob(taskIds);
		Assert.notNull(jobs);
		Assert.isTrue(jobs.size() > 0);
	}

	@Test
	@Transactional(isolation = Isolation.READ_UNCOMMITTED)
	@Rollback
	public void testGetTaskIdsByStatus() {
		ScheduleJob streamJob = dataCollection.getScheduleJobStream();

		List<String> taskIds = streamTaskService.getTaskIdsByStatus(streamJob.getStatus());
		Assert.notNull(taskIds);
		Assert.isTrue(taskIds.contains(streamJob.getTaskId()));
	}

	@Test
	@Transactional(isolation = Isolation.READ_UNCOMMITTED)
	@Rollback
	public void testGetTaskStatus() {
		ScheduleJob streamJob = dataCollection.getScheduleJobStream();

		Integer taskStatus = streamTaskService.getTaskStatus(streamJob.getJobId());
		Assert.notNull(taskStatus);
		Assert.isTrue(taskStatus == 14);
	}

	@Test
	@Transactional(isolation = Isolation.READ_UNCOMMITTED)
	@Rollback
	public void testGetRunningTaskLogUrl() throws Exception {

		ScheduleJob streamJob = dataCollection.getScheduleJobStream();
		dataCollection.getEngineJobCache();

		Integer taskStatus = streamTaskService.getTaskStatus(streamJob.getJobId());
		Assert.isTrue(RdosTaskStatus.RUNNING.getStatus().equals(taskStatus));

		Pair<String, String> taskLogUrl = streamTaskService.getRunningTaskLogUrl(streamJob.getJobId());
		Assert.notNull(taskLogUrl.getKey());
		Assert.notNull(taskLogUrl.getValue());
	}

}
