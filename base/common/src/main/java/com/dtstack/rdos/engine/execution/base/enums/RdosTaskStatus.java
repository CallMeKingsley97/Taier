package com.dtstack.rdos.engine.execution.base.enums;

import com.google.common.base.Strings;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 
 * Reason: TODO ADD REASON(可选)
 * Date: 2017年03月03日 下午1:25:18
 * Company: www.dtstack.com
 * @author sishu.yss
 *
 */
public enum RdosTaskStatus {

	UNSUBMIT(0),CREATED(1),SCHEDULED(2),DEPLOYING(3),RUNNING(4),FINISHED(5),CANCELLING(6),CANCELED(7),FAILED(8), SUBMITFAILD(9),
	SUBMITTING(10), RESTARTING(11), MANUALSUCCESS(12), KILLED(13), SUBMITTED(14), NOTFOUND(15), WAITENGINE(16), WAITCOMPUTE(17),
    FROZEN(18), ENGINEACCEPTED(19), ENGINEDISTRIBUTE(20);
	
	private int status;

    private static List<Integer> canStopStatus = Lists.newArrayList(
            UNSUBMIT.getStatus(),
            CREATED.getStatus(), SCHEDULED.getStatus(),
            DEPLOYING.getStatus(), RUNNING.getStatus(),
            SUBMITTING.getStatus(), RESTARTING.getStatus(),
            SUBMITTED.getStatus(), WAITENGINE.getStatus(),
            WAITCOMPUTE.getStatus(), ENGINEACCEPTED.getStatus(),
            ENGINEDISTRIBUTE.getStatus());

	private static final Logger logger = LoggerFactory.getLogger(RdosTaskStatus.class);
	
	RdosTaskStatus(int status){
		this.status = status;
	}
	
	public Integer getStatus(){
		return this.status;
	}

    /**
     * 需要捕获无法转换异常
     * @param taskStatus
     * @return
     */
    public static RdosTaskStatus getTaskStatus(String taskStatus){

        if(Strings.isNullOrEmpty(taskStatus)){
            return null;
        }else if("error".equalsIgnoreCase(taskStatus)||"failing".equalsIgnoreCase(taskStatus)){
            taskStatus = "FAILED";
        }

	    try {
            return RdosTaskStatus.valueOf(taskStatus);
        }catch (Exception e){
            logger.info("No enum constant :" + taskStatus);
	        return null;
        }
    }

    public static RdosTaskStatus getTaskStatus(int status){
        for(RdosTaskStatus tmp : RdosTaskStatus.values()){
            if(tmp.getStatus() == status){
                return tmp;
            }
        }

        return null;
    }
    
    public static boolean needClean(Byte status){
        return needClean(status.intValue());
    }

    public static boolean needClean(Integer status){

        if(RdosTaskStatus.FINISHED.getStatus().equals(status) || RdosTaskStatus.FAILED.getStatus().equals(status)
                || RdosTaskStatus.SUBMITFAILD.getStatus().equals(status) || RdosTaskStatus.CANCELED.getStatus().equals(status)
                || RdosTaskStatus.KILLED.getStatus().equals(status)){
            return true;
        }
        return false;
    }
    
    public static boolean canStartAgain(Byte status){
		int sta = status.intValue();
        if(sta == RdosTaskStatus.SUBMITTING.getStatus() || sta == RdosTaskStatus.UNSUBMIT.getStatus()
                || sta == RdosTaskStatus.ENGINEACCEPTED.getStatus()){
    	    return true;
        }

        return false;
    }

    public static boolean canSubmitAgain(Byte status){
        int sta = status.intValue();
        if(sta == RdosTaskStatus.ENGINEACCEPTED.getStatus() || sta == RdosTaskStatus.SUBMITTING.getStatus()){
            return true;
        }

        return false;
    }

    public static List<Integer> getCanStopStatus(){
        return canStopStatus;
    }
    
    public static void main(String[] args){
    	System.out.println(RdosTaskStatus.NOTFOUND.name());
    }
}
