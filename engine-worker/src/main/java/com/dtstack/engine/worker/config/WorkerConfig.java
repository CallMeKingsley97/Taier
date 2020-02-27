package com.dtstack.engine.worker.config;

import com.dtstack.engine.common.akka.Master;
import com.dtstack.engine.common.akka.Worker;
import com.dtstack.engine.common.util.AddressUtil;
import com.typesafe.config.Config;
import org.apache.commons.lang.StringUtils;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2020/2/27
 */
public class WorkerConfig {

    private final static String REMOTE_PATH_TEMPLATE = "akka.tcp://%s@%s:%s/user/%s";
    private final static String MASTER_CONFIG_PREFIX = "akka.master";
    private final static String WORKER_CONFIG_PREFIX = "akka.worker";
    private static Config WORK_CONFIG = null;

    public static void loadConfig(Config config) {
        WORK_CONFIG = config;
    }

    public static String getMasterSystemName() {
        String name = null;
        if (WORK_CONFIG.hasPath(MASTER_CONFIG_PREFIX)) {
            name = WORK_CONFIG.getString(MASTER_CONFIG_PREFIX + ".masterSystemName");
        }
        if (StringUtils.isBlank(name)) {
            name = Master.class.getSimpleName();
        }
        return name;
    }


    public static String getMasterAddress() {
        String masterAddress = null;
        if (WORK_CONFIG.hasPath(MASTER_CONFIG_PREFIX)) {
            masterAddress = WORK_CONFIG.getString(MASTER_CONFIG_PREFIX + ".masterAddress");
        }
        if (StringUtils.isBlank(masterAddress)) {
            throw new IllegalArgumentException("masterAddress is null.");
        }
        return masterAddress;
    }

    public static String getWorkerSystemName() {
        return "workerSystemName";
    }

    public static String getWorkerName() {
        String name = null;
        if (WORK_CONFIG.hasPath(WORKER_CONFIG_PREFIX)) {
            name = WORK_CONFIG.getString(WORKER_CONFIG_PREFIX + ".workerName");
        }
        if (StringUtils.isBlank(name)) {
            name = Worker.class.getSimpleName();
        }
        return name;
    }

    public static String getWorkerRemotePath() {
        String path = null;
        if (WORK_CONFIG.hasPath(WORKER_CONFIG_PREFIX)) {
            path = WORK_CONFIG.getString(WORKER_CONFIG_PREFIX + ".workerRemotePath");
        }
        if (StringUtils.isBlank(path)) {
            path = String.format(REMOTE_PATH_TEMPLATE, getWorkerSystemName(), getWorkerIp(), getWorkerPort(), getWorkerSystemName());
        }
        return path;
    }

    public static String getWorkerIp() {
        String workerIp = WORK_CONFIG.getString("akka.remote.netty.tcp.hostname");
        if (StringUtils.isBlank(workerIp)) {
            workerIp = AddressUtil.getOneIp();
        }
        return workerIp;
    }

    public static int getWorkerPort() {
        int workerPort = WORK_CONFIG.getInt("akka.remote.netty.tcp.port");
        if (workerPort != 0) {
            workerPort = 10000;
        }
        return workerPort;
    }

}
