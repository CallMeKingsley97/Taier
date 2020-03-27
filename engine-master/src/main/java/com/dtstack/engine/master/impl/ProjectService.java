package com.dtstack.engine.master.impl;

import com.dtstack.engine.dao.BatchTaskShadeDao;
import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * @author yuebai
 * @date 2020-01-19
 */
@Service
public class ProjectService {

    private final Logger logger = LoggerFactory.getLogger(ProjectService.class);

    @Autowired
    private BatchTaskShadeDao batchTaskShadeDao;

    public void updateSchedule(@Param("projectId")Long projectId,@Param("appType")Integer appType,@Param("scheduleStatus")Integer scheduleStatus) {
        if (Objects.isNull(projectId) || Objects.isNull(appType) || Objects.isNull(scheduleStatus)) {
            return;
        }
        logger.info("update project {} status {} ",projectId,scheduleStatus);
        batchTaskShadeDao.updateProjectScheduleStatus(projectId,appType,scheduleStatus);
    }
}

