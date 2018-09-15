package com.dtstack.yarn.am;


import com.dtstack.yarn.DtYarnConfiguration;
import com.dtstack.yarn.api.DtYarnConstants;
import com.dtstack.yarn.common.LocalRemotePath;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.util.ConverterUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *  Retrieve Application Master's arguments
 *
 */
public class AppArguments {

    private static final Log LOG = LogFactory.getLog(AppArguments.class);

    final ApplicationMaster appMaster;

    final Configuration conf;

    final Map<String,String> envs = System.getenv();

    final ApplicationAttemptId applicationAttemptID;

    int workerMemory;

    int workerVCores;

    int workerNum;

    int appPriority;

    String learningAppType;

    /** location of AppMaster.jar on HDFS */
    Path appJarRemoteLocation;

    /**location of job.xml on HDFS */
    Path appConfRemoteLocation;

    /** location of files on HDFS */
    String appFilesRemoteLocation;

    /** location of cacheFiles on HDFS */
    String appCacheFilesRemoteLocation;

    String cmd;

    /** output locations */
    final List<LocalRemotePath> outputInfos = new ArrayList<>();

    /** input locations */
    final List<LocalRemotePath> inputInfos = new ArrayList<>();


    public AppArguments(ApplicationMaster appMaster) {
        LOG.info("AppArguments init begin");
        this.appMaster = appMaster;
        this.conf = new DtYarnConfiguration();
        Path jobConfPath = new Path(DtYarnConstants.LEARNING_JOB_CONFIGURATION);

        LOG.info("job conf path: " + jobConfPath);

        conf.addResource(jobConfPath);

        if (envs.containsKey(ApplicationConstants.Environment.CONTAINER_ID.toString())) {
            ContainerId containerId = ConverterUtils
                    .toContainerId(envs.get(ApplicationConstants.Environment.CONTAINER_ID.toString()));

            LOG.info("hyf container_id: " + containerId.toString());
            applicationAttemptID = containerId.getApplicationAttemptId();

            LOG.info("second applicationAttemptID: " + applicationAttemptID);
        } else {
            throw new IllegalArgumentException(
                    "Application Attempt Id is not available in environment");
        }

        workerMemory = conf.getInt(DtYarnConfiguration.LEARNING_WORKER_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_WORKER_MEMORY);
        workerVCores = conf.getInt(DtYarnConfiguration.LEARNING_WORKER_VCORES, DtYarnConfiguration.DEFAULT_LEARNING_WORKER_VCORES);
        workerNum = conf.getInt(DtYarnConfiguration.DT_WORKER_NUM, DtYarnConfiguration.DEFAULT_DT_WORKER_NUM);
        appPriority = conf.getInt(DtYarnConfiguration.APP_PRIORITY, DtYarnConfiguration.DEFAULT_LEARNING_APP_PRIORITY);

        assert (envs.containsKey(DtYarnConstants.Environment.APP_JAR_LOCATION.toString()));
        appJarRemoteLocation = new Path(envs.get(DtYarnConstants.Environment.APP_JAR_LOCATION.toString()));
        LOG.info("Application jar location: " + appJarRemoteLocation);

        assert (envs.containsKey(DtYarnConstants.Environment.XLEARNING_JOB_CONF_LOCATION.toString()));
        appConfRemoteLocation = new Path(envs.get(DtYarnConstants.Environment.XLEARNING_JOB_CONF_LOCATION.toString()));
        LOG.info("Application conf location: " + appConfRemoteLocation);

        if (envs.containsKey(DtYarnConstants.Environment.FILES_LOCATION.toString())) {
            appFilesRemoteLocation = envs.get(DtYarnConstants.Environment.FILES_LOCATION.toString());
            LOG.info("Application files location: " + appFilesRemoteLocation);
        }

        if (envs.containsKey(DtYarnConstants.Environment.APP_TYPE.toString())) {
            learningAppType = envs.get(DtYarnConstants.Environment.APP_TYPE.toString()).toUpperCase();
        } else {
            learningAppType = DtYarnConfiguration.DEFAULT_APP_TYPE.toUpperCase();
        }

        if (envs.containsKey(DtYarnConstants.Environment.DT_EXEC_CMD.toString())) {
            cmd = envs.get(DtYarnConstants.Environment.DT_EXEC_CMD.toString());
            LOG.info("exec cmd: " + cmd);
        }

        if (envs.containsKey(DtYarnConstants.Environment.CACHE_FILE_LOCATION.toString())) {
            appCacheFilesRemoteLocation = envs.get(DtYarnConstants.Environment.CACHE_FILE_LOCATION.toString());
            LOG.info("Application cacheFiles location: " + appCacheFilesRemoteLocation);
        }

        buildInputLocations();

        buildOutputLocations();

        LOG.info("AppArguments init end...");
    }

    private void buildInputLocations() {
        String rawInputs = envs.get(DtYarnConstants.Environment.INPUTS.toString());
        LOG.info("rawInputs: " + rawInputs);
        if (StringUtils.isBlank(rawInputs)) {
            return;
        }
        String[] inputs = StringUtils.split(rawInputs, "|");
        if (inputs != null && inputs.length > 0) {
            for (String input : inputs) {
                String inputPathTuple[] = StringUtils.split(input, ":");
                if (inputPathTuple.length < 2) {
                    throw new RuntimeException("Error input path format " + rawInputs);
                }
                String pathRemote = inputPathTuple[0];
                LocalRemotePath inputInfo = new LocalRemotePath();
                inputInfo.setDfsLocation(pathRemote);
                String pathLocal = inputPathTuple[1];
                inputInfo.setLocalLocation(pathLocal);
                inputInfos.add(inputInfo);
                LOG.info("Application input " + pathRemote + "#" + pathLocal);
            }
        } else {
            throw new RuntimeException("Error input path format " + rawInputs);
        }
    }

    private void buildOutputLocations() {
        String rawOutputs = envs.get(DtYarnConstants.Environment.OUTPUTS.toString());
        LOG.info("rawOutputs: " + rawOutputs);
        if (StringUtils.isBlank(rawOutputs)) {
            return;
        }
        String[] outputs = StringUtils.split(rawOutputs, ",");
        if (outputs != null && outputs.length > 0) {
            for (String output : outputs) {
                String outputPathTuple[] = StringUtils.split(output, "#");
                if (outputPathTuple.length < 2) {
                    throw new RuntimeException("Error input path format " + rawOutputs);
                }
                String pathRemote = outputPathTuple[0];
                LocalRemotePath outputInfo = new LocalRemotePath();
                outputInfo.setDfsLocation(pathRemote);
                String pathLocal = outputPathTuple[1];
                outputInfo.setLocalLocation(pathLocal);
                outputInfos.add(outputInfo);
                LOG.info("Application output " + pathRemote + "#" + pathLocal);
            }
        } else {
            throw new RuntimeException("Error input path format " + rawOutputs);
        }
    }


}
