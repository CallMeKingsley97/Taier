package com.dtstack.task.domain;


/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2019/10/22
 */
public class BatchFillDataJob extends AppTenantEntity {

    private String jobName;

    private String runDay;

    private String fromDay;

    private String toDay;

    /**
     * 发起操作的用户
     */
    private long createUserId;

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getRunDay() {
        return runDay;
    }

    public void setRunDay(String runDay) {
        this.runDay = runDay;
    }

    public long getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(long createUserId) {
        this.createUserId = createUserId;
    }

    public String getFromDay() {
        return fromDay;
    }

    public void setFromDay(String fromDay) {
        this.fromDay = fromDay;
    }

    public String getToDay() {
        return toDay;
    }

    public void setToDay(String toDay) {
        this.toDay = toDay;
    }

}
