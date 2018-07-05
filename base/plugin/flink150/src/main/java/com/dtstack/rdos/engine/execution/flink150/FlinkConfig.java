package com.dtstack.rdos.engine.execution.flink150;

import com.google.common.base.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.Map;

/**
 * @author sishu.yss
 */
public class FlinkConfig {

    private static final String DEFAULT_FLINK_PLUGIN_ROOT = "/opt/dtstack/flinkplugin";

    private static final String DEFAULT_REMOTE_PLUGIN_ROOT_DIR = "/opt/dtstack/flinkplugin";

    private static final String DEFAULT_FLINK_ZK_NAMESPACE = "/flink150";

    private static final String DEFAULT_JAR_TMP_DIR = "../tmp150";

    private static final String DEFAULT_FLINK_HIGH_AVAILABILITY_STORAGE_DIR = "%s/flink150/ha";

    private static final String HDFS_FLAG = "hdfs";

    private String typeName;

    private String flinkZkAddress;

    private String flinkZkNamespace;

    private String flinkClusterId;

    private String flinkJobMgrUrl;

    private String flinkHighAvailabilityStorageDir;

    private String jarTmpDir;

    private String flinkPluginRoot;

    private String monitorAddress;

    private String remotePluginRootDir;

    private String clusterMode; // 集群运行模式: standalone or yarn

    private String flinkYarnMode; // new or legacy

    private String flinkYarnNewModeMaxSlots; // max slots

    private Map<String, Object> hadoopConf;

    private Map<String, Object> yarnConf;


    public String getFlinkZkAddress() {
        return flinkZkAddress;
    }

    public void setFlinkZkAddress(String flinkZkAddress) {
        this.flinkZkAddress = flinkZkAddress;
    }

    public String getFlinkZkNamespace() {
        if (Strings.isNullOrEmpty(flinkZkNamespace)) {
            return DEFAULT_FLINK_ZK_NAMESPACE;
        }

        return flinkZkNamespace;
    }

    public void setFlinkZkNamespace(String flinkZkNamespace) {
        this.flinkZkNamespace = flinkZkNamespace;
    }

    public String getFlinkClusterId() {
        return flinkClusterId;
    }

    public void setFlinkClusterId(String flinkClusterId) {
        this.flinkClusterId = flinkClusterId;
    }

    public String getJarTmpDir() {
        if (Strings.isNullOrEmpty(jarTmpDir)) {
            return DEFAULT_JAR_TMP_DIR;
        }

        return jarTmpDir;
    }

    public void setJarTmpDir(String jarTmpDir) {
        this.jarTmpDir = jarTmpDir;
    }

    public String getFlinkJobMgrUrl() {
        return flinkJobMgrUrl;
    }

    public void setFlinkJobMgrUrl(String flinkJobMgrUrl) {
        this.flinkJobMgrUrl = flinkJobMgrUrl;
    }

    public String getFlinkHighAvailabilityStorageDir() {
        return flinkHighAvailabilityStorageDir;
    }

    public void setFlinkHighAvailabilityStorageDir(
            String flinkHighAvailabilityStorageDir) {
        this.flinkHighAvailabilityStorageDir = flinkHighAvailabilityStorageDir;
    }

    public void setDefaultFlinkHighAvailabilityStorageDir(String defaultFS) {
        String defaultVal = String.format(DEFAULT_FLINK_HIGH_AVAILABILITY_STORAGE_DIR, defaultFS);
        this.flinkHighAvailabilityStorageDir = defaultVal;
    }

    public void updateFlinkHighAvailabilityStorageDir(String defaultFS) {
        if (Strings.isNullOrEmpty(flinkHighAvailabilityStorageDir)) {
            return;
        }

        if (flinkHighAvailabilityStorageDir.trim().startsWith(HDFS_FLAG)) {
            return;
        }

        flinkHighAvailabilityStorageDir = flinkHighAvailabilityStorageDir.trim();
        flinkHighAvailabilityStorageDir = defaultFS + flinkHighAvailabilityStorageDir;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getFlinkPluginRoot() {
        if (Strings.isNullOrEmpty(flinkPluginRoot)) {
            return DEFAULT_FLINK_PLUGIN_ROOT;
        }

        return flinkPluginRoot;
    }

    public void setFlinkPluginRoot(String flinkPluginRoot) {
        this.flinkPluginRoot = flinkPluginRoot;
    }

    public String getMonitorAddress() {
        return monitorAddress;
    }

    public void setMonitorAddress(String monitorAddress) {
        this.monitorAddress = monitorAddress;
    }

    public String getRemotePluginRootDir() {

        if (Strings.isNullOrEmpty(remotePluginRootDir)) {
            return DEFAULT_REMOTE_PLUGIN_ROOT_DIR;
        }

        return remotePluginRootDir;
    }

    public void setRemotePluginRootDir(String remotePluginRootDir) {
        this.remotePluginRootDir = remotePluginRootDir;
    }

    public String getClusterMode() {
        return clusterMode;
    }

    public void setClusterMode(String clusterMode) {
        this.clusterMode = clusterMode;
    }

    public String getFlinkYarnMode() {
        return flinkYarnMode;
    }

    public void setFlinkYarnMode(String flinkYarnMode) {
        this.flinkYarnMode = flinkYarnMode;
    }

    public int getFlinkYarnNewModeMaxSlots() {
        return StringUtils.isBlank(flinkYarnNewModeMaxSlots) ? 0 : NumberUtils.toInt(flinkYarnNewModeMaxSlots);
    }

    public void setFlinkYarnNewModeMaxSlots(String flinkYarnNewModeMaxSlots) {
        this.flinkYarnNewModeMaxSlots = flinkYarnNewModeMaxSlots;
    }


    public Map<String, Object> getHadoopConf() {
        return hadoopConf;
    }

    public void setHadoopConf(Map<String, Object> hadoopConf) {
        this.hadoopConf = hadoopConf;
    }

    public Map<String, Object> getYarnConf() {
        return yarnConf;
    }

    public void setYarnConf(Map<String, Object> yarnConf) {
        this.yarnConf = yarnConf;
    }
}
