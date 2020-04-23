package com.dtstack.engine.worker;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.alibaba.fastjson.JSONObject;
import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.akka.RpcService;
import com.dtstack.engine.common.akka.config.AkkaConfig;
import com.dtstack.engine.common.akka.message.WorkerInfo;
import com.dtstack.engine.common.util.AddressUtil;
import com.dtstack.engine.common.util.LogCountUtil;
import com.dtstack.engine.worker.metric.SystemResourcesMetricsAnalyzer;
import com.dtstack.engine.worker.service.JobService;
import com.google.common.collect.Lists;
import com.typesafe.config.Config;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @Auther: jiangjunjie
 * @Date: 2020/3/3
 * @Description:
 */
public class AkkaWorkerServerImpl implements WorkerServer<WorkerInfo, ActorSelection>, RpcService<Config>, Runnable {

    private static final Logger logger = LoggerFactory.getLogger(AkkaWorkerServerImpl.class);


    private static AkkaWorkerServerImpl akkaWorkerServer = new AkkaWorkerServerImpl();

    public static AkkaWorkerServerImpl getAkkaWorkerServer() {
        return akkaWorkerServer;
    }

    private AkkaWorkerServerImpl() {
    }

    private int logOutput = 0;
    private final static int MULTIPLES = 30;
    private final static int CHECK_INTERVAL = 1000;
    private static Random random = new Random();

    private ActorSystem system;
    private String hostname;
    private Integer port;
    private String workerRemotePath;
    private FiniteDuration askResultTimeout;
    private Timeout askTimeout;
    private ActorSelection activeMasterActor;
    private MonitorNode monitorNode;

    private SystemResourcesMetricsAnalyzer systemResourcesMetricsAnalyzer = new SystemResourcesMetricsAnalyzer();

    @Override
    public void start(Config config) {
        this.system = AkkaConfig.initActorSystem(AkkaConfig.getWorkerSystemName());

        this.system.actorOf(Props.create(JobService.class));

        this.hostname = AkkaConfig.getAkkaHostname();
        this.port = AkkaConfig.getAkkaPort();
        this.workerRemotePath = AkkaConfig.getWorkerPath(hostname, port);
        this.askResultTimeout = Duration.create(AkkaConfig.getAkkaAskResultTimeout(), TimeUnit.SECONDS);
        this.askTimeout = Timeout.create(java.time.Duration.ofSeconds(AkkaConfig.getAkkaAskTimeout()));

        ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(3, new CustomThreadFactory(this.getClass().getSimpleName()));

        if (!AkkaConfig.isLocalMode()) {
            monitorNode = new MonitorNode();
            scheduledService.scheduleWithFixedDelay(
                    monitorNode,
                    CHECK_INTERVAL * 10,
                    CHECK_INTERVAL * 10,
                    TimeUnit.MILLISECONDS);
        }

        scheduledService.scheduleWithFixedDelay(
                this,
                0,
                CHECK_INTERVAL,
                TimeUnit.MILLISECONDS);

        long systemResourceProbeInterval = AkkaConfig.getSystemResourceProbeInterval();
        systemResourcesMetricsAnalyzer.instantiateSystemMetrics(systemResourceProbeInterval);
        scheduledService.scheduleWithFixedDelay(
                systemResourcesMetricsAnalyzer,
                systemResourceProbeInterval,
                systemResourceProbeInterval,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void heartBeat(WorkerInfo workerInfo) {
        ActorSelection actorSelection = getActiveMasterAddress();
        if (null == actorSelection) {
            if (null != monitorNode) {
                logger.info("worker not connect available nodes, availableNodes:{} disableNodes:{}", monitorNode.availableNodes, monitorNode.disableNodes);
            }
            return;
        }
        try {
            if (MapUtils.isNotEmpty(systemResourcesMetricsAnalyzer.getMetrics())) {
                String systemResource = JSONObject.toJSONString(systemResourcesMetricsAnalyzer.getMetrics());
                workerInfo.setSystemResource(systemResource);
            }
            Future<Object> future = Patterns.ask(actorSelection, workerInfo, askTimeout);
            Object result = Await.result(future, askResultTimeout);
        } catch (Throwable e) {
            if (monitorNode != null) {
                monitorNode.add(activeMasterActor);
                logger.error("Can't send WorkerInfo to master, availableNodes:{} disableNodes:{}, happens error:{}", monitorNode.availableNodes,  monitorNode.disableNodes, e);
            }
            activeMasterActor = null;
        }
    }


    @Override
    public ActorSelection getActiveMasterAddress() {
        if (activeMasterActor != null) {
            return activeMasterActor;
        }
        if (monitorNode == null) {
            activeMasterActor = system.actorSelection(AkkaConfig.getMasterPath());
        } else {
            int size = monitorNode.availableNodes.size();
            if (size != 0) {
                int index = random.nextInt(size);
                String ipAndPort = Lists.newArrayList(monitorNode.availableNodes).get(index);
                String[] hostInfo = ipAndPort.split(":");
                if (hostInfo.length == 2) {
                    String masterRemotePath = AkkaConfig.getMasterPath(hostInfo[0], hostInfo[1]);
                    activeMasterActor = system.actorSelection(masterRemotePath);
                    logger.info("get an ActorSelection of masterRemotePath:{}", masterRemotePath);
                }
            }
        }
        return activeMasterActor;
    }

    @Override
    public void run() {
        WorkerInfo workerInfo = new WorkerInfo(hostname, port, workerRemotePath, System.currentTimeMillis());
        heartBeat(workerInfo);
        if (LogCountUtil.count(logOutput++, MULTIPLES)) {
            logger.info("WorkerBeatListener Running workerRemotePath:{} gap:[{} ms]...", workerRemotePath, CHECK_INTERVAL * MULTIPLES);
        }
    }

    @Override
    public Config loadConfig() {
        return null;
    }


    private class MonitorNode implements Runnable {

        private volatile Set<String> availableNodes = new CopyOnWriteArraySet();
        private volatile Set<String> disableNodes = new CopyOnWriteArraySet();

        public MonitorNode() {
            this.availableNodes.addAll(Arrays.asList(AkkaConfig.getMasterAddress().split(",")));
        }

        @Override
        public void run() {
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("availableNodes--->{},disableNodes---->{}", availableNodes, disableNodes);
                }
                if (disableNodes.size() > 0) {
                    Iterator<String> iterators = disableNodes.iterator();
                    while (iterators.hasNext()) {
                        String uri = iterators.next();
                        String[] up = uri.split(":");
                        if (AddressUtil.telnet(up[0], Integer.parseInt(up[1]))) {
                            availableNodes.add(uri);
                            disableNodes.remove(uri);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("", e);
            }
        }

        public void add(ActorSelection actorSelection) {
            String ip = actorSelection.anchorPath().address().host().get();
            String port = actorSelection.anchorPath().address().port().get().toString();
            String ipAndPort = String.format("%s:%s", ip, port);
            availableNodes.remove(ipAndPort);
            disableNodes.add(ipAndPort);
        }
    }
}
