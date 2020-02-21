package com.dtstack.engine.worker;

import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import com.dtstack.engine.common.*;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.pojo.JobResult;
import com.dtstack.engine.common.message.*;

import java.util.List;

public class Worker extends AbstractActor{
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public Receive createReceive() {
        return receiveBuilder()
                .match(MessageJudgeSlots.class, msg->{
                    JobClient jobClient = msg.getJobClient();
                    IClient clusterClient = ClientCache.getInstance().getClient(jobClient.getEngineType(), jobClient.getPluginInfo());
                    sender().tell(clusterClient.judgeSlots(jobClient), getSelf());
                })
                .match(MessageSubmitJob.class, msg->{
                    JobClient jobClient = msg.getJobClient();
                    IClient clusterClient = ClientCache.getInstance().getClient(jobClient.getEngineType(), jobClient.getPluginInfo());
                    sender().tell(clusterClient.submitJob(jobClient), getSelf());
                })
                .match(MessageGetJobStatus.class, msg->{
                    RdosTaskStatus status = ClientOperator.getInstance().getJobStatus(msg.getEngineType(), msg.getPluginInfo(), msg.getJobIdentifier());
                    sender().tell(status, getSelf());
                })
                .match(MessageGetEngineLog.class, msg->{
                    String engineLog = ClientOperator.getInstance().getEngineLog(msg.getEngineType(), msg.getPluginInfo(), msg.getJobIdentifier());
                    sender().tell(engineLog, getSelf());
                })
                .match(MessageGetJobMaster.class, msg->{
                    String jobMaster = ClientOperator.getInstance().getJobMaster(msg.getEngineType(), msg.getPluginInfo(), msg.getJobIdentifier());
                    sender().tell(jobMaster, getSelf());
                })
                .match(MessageStopJob.class, msg->{
                    JobResult result = ClientOperator.getInstance().stopJob(msg.getJobClient());
                    sender().tell(result, getSelf());

                })
                .match(MessageGetCheckpoints.class, msg->{
                    String checkPoints = ClientOperator.getInstance().getCheckpoints(msg.getEngineType(), msg.getPluginInfo(), msg.getJobIdentifier());
                    sender().tell(checkPoints, getSelf());
                })
                .match(MessageContainerInfos.class, msg->{
                    List<String> containerInfos = ClientOperator.getInstance().containerInfos(msg.getJobClient());
                    sender().tell(containerInfos, getSelf());
                })
                .build();
    }
}

