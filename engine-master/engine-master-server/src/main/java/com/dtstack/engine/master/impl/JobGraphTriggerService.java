package com.dtstack.engine.master.impl;

import com.dtstack.engine.common.enums.EScheduleType;
import com.dtstack.engine.dao.JobGraphTriggerDao;
import com.dtstack.engine.api.domain.JobGraphTrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2019/10/22
 */
@Component
public class JobGraphTriggerService{

    @Autowired
    private JobGraphTriggerDao jobGraphTriggerDao;

    public JobGraphTrigger getTriggerByDate(Timestamp timestamp, int triggerType){
        return jobGraphTriggerDao.getByTriggerTimeAndTriggerType(timestamp, triggerType);
    }

    public boolean checkHasBuildJobGraph(Timestamp todayTime){
        JobGraphTrigger trigger = getTriggerByDate(todayTime, EScheduleType.NORMAL_SCHEDULE.getType());
        return trigger != null;
    }

    public void addJobTrigger(Timestamp timestamp){
        JobGraphTrigger jobGraphTrigger = new JobGraphTrigger();
        jobGraphTrigger.setTriggerTime(timestamp);
        jobGraphTrigger.setTriggerType(0);

        //新增jobTrigger
        jobGraphTriggerDao.insert(jobGraphTrigger);
    }

}
