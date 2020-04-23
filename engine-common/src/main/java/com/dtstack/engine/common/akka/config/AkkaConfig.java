package com.dtstack.engine.common.akka.config;

import com.dtstack.engine.common.akka.Master;
import com.dtstack.engine.common.akka.Worker;
import com.dtstack.engine.common.constrant.ConfigConstant;
import com.dtstack.engine.common.util.AddressUtil;
import com.dtstack.engine.common.util.NetUtils;
import com.google.common.collect.Maps;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;

/**
 * company: www.dtstack.com
 * author: yanxi
 * create: 2020/2/27
 */
public class AkkaConfig {

    private final static String LOCAL_PATH_TEMPLATE = "akka://%s/dagschedulex/%s";
    private final static String REMOTE_PATH_TEMPLATE = "akka.tcp://%s@%s:%s/dagschedulex/%s";
    private static Config AKKA_CONFIG = null;
    private static boolean LOCAL_MODE = false;

    public static void setLocalMode(boolean localMode) {
        LOCAL_MODE = localMode;
    }

    public static void loadConfig(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("unload akka conf.");
        }
        AKKA_CONFIG = config;
    }

    public static String getMasterSystemName() {
        return ConfigConstant.AKKA_MASTER_SYSTEM;
    }

    public static String getMasterName() {
        String keyName = ConfigConstant.AKKA_MASTER_NAME;
        String defaultValue = Master.class.getSimpleName();
        return getValueWithDefault(keyName, defaultValue);
    }

    public static String getMasterAddress() {
        String keyName = ConfigConstant.AKKA_MASTER_MASTERADDRESS;
        String masterAddress = getValueWithDefault(keyName, "");
        if (StringUtils.isBlank(masterAddress)) {
            throw new IllegalArgumentException(keyName + " is null.");
        }
        return masterAddress;
    }

    public static String getMasterPath() {
        String keyName;
        String masterRemotePath;
        if (LOCAL_MODE) {
            keyName = ConfigConstant.AKKA_MASTER_LOCAL_PATH;
            masterRemotePath = String.format(LOCAL_PATH_TEMPLATE, getMasterSystemName(), getMasterName());
        } else {
            keyName = ConfigConstant.AKKA_MASTER_REMOTE_PATH;
            masterRemotePath = String.format(REMOTE_PATH_TEMPLATE, getMasterSystemName(), getAkkaHostname(), getAkkaPort(), getMasterName());
        }
        return getValueWithDefault(keyName, masterRemotePath);
    }

    public static String getWorkerSystemName() {
        return ConfigConstant.AKKA_WORKER_SYSTEM;
    }

    public static String getWorkerName() {
        String keyName = ConfigConstant.AKKA_WORKER_NAME;
        String workerName = Worker.class.getSimpleName();
        return getValueWithDefault(keyName, workerName);
    }

    public static String getWorkerPath() {
        String keyName;
        String workerRemotePath;
        if (LOCAL_MODE) {
            keyName = ConfigConstant.AKKA_WORKER_LOCAL_PATH;
            workerRemotePath = String.format(LOCAL_PATH_TEMPLATE, getWorkerSystemName(), getWorkerName());
        } else {
            keyName = ConfigConstant.AKKA_WORKER_REMOTE_PATH;
            workerRemotePath = String.format(REMOTE_PATH_TEMPLATE, getWorkerSystemName(), getAkkaHostname(), getAkkaPort(), getWorkerName());
        }
        return getValueWithDefault(keyName, workerRemotePath);
    }

    public static String getAkkaHostname() {
        String keyName = ConfigConstant.AKKA_REMOTE_NETTY_TCP_HOSTNAME;
        String defaultIp = AddressUtil.getOneIp();
        return getValueWithDefault(keyName, defaultIp);
    }

    public static int getAkkaPort() {
        String keyName = ConfigConstant.AKKA_REMOTE_NETTY_TCP_PORT;
        return Integer.valueOf(getValueWithDefault(keyName, "2555"));
    }

    public static Long getAkkaAskTimeout() {
        String keyName = ConfigConstant.AKKA_ASK_TIMEOUT;
        return Long.valueOf(getValueWithDefault(keyName, "10000"));
    }

    public static Long getAkkaAskResultTimeout() {
        String keyName = ConfigConstant.AKKA_ASK_RESULTTIMEOUT;
        return Long.valueOf(getValueWithDefault(keyName, "10"));
    }

    public static Config checkIpAndPort(Config config) {
        HashMap<String, Object> configMap = Maps.newHashMap();

        String hostname = config.getString(ConfigConstant.AKKA_REMOTE_NETTY_TCP_HOSTNAME);
        if (StringUtils.isBlank(hostname)) {
            hostname = AddressUtil.getOneIp();
        }
        configMap.put(ConfigConstant.AKKA_REMOTE_NETTY_TCP_HOSTNAME, hostname);

        int port = config.getInt(ConfigConstant.AKKA_REMOTE_NETTY_TCP_PORT);
        int endPort = port + 100;
        port = NetUtils.getAvailablePortRange(hostname, port, endPort);
        configMap.put(ConfigConstant.AKKA_REMOTE_NETTY_TCP_PORT, port);

        Config loadConfig = ConfigFactory.parseMap(configMap).withFallback(config);
        loadConfig(loadConfig);
        return loadConfig;
    }

    private static String getValueWithDefault(String configKey, String defaultValue) {
        String configValue = null;
        if (AKKA_CONFIG.hasPath(configKey)) {
            configValue = AKKA_CONFIG.getString(configKey);
        }
        if (null == configValue) {
            return defaultValue;
        } else {
            return configValue;
        }
    }

    public static long getSystemResourceProbeInterval() {
        String keyName = ConfigConstant.AKKA_NODE_LABELS;
        return Integer.valueOf(getValueWithDefault(keyName, "5000"));
    }

    public static String getNodeLabels() {
        String keyName = ConfigConstant.AKKA_NODE_LABELS;
        return getValueWithDefault(keyName, "default");
    }

    public static long getWorkerTimeout() {
        String keyName = ConfigConstant.AKKA_WORKER_TIMEOUT;
        return Long.valueOf(getValueWithDefault(keyName, "300000"));
    }

    public static String getWorkerLogstoreJdbcUrl() {
        String keyName = ConfigConstant.AKKA_WORKER_LOGSTORE_JDBCURL;
        return getValueWithDefault(keyName, "");
    }

    public static String getWorkerLogstoreUsername() {
        String keyName = ConfigConstant.AKKA_WORKER_LOGSTORE_USERNAME;
        return getValueWithDefault(keyName, "");
    }

    public static String getWorkerLogstorePassword() {
        String keyName = ConfigConstant.AKKA_WORKER_LOGSTORE_PASSWORD;
        return getValueWithDefault(keyName, "");
    }
}
