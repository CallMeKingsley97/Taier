package com.dtstack.yarn.client;


import com.dtstack.yarn.DtYarnConfiguration;
import com.dtstack.yarn.am.ApplicationMaster;
import com.dtstack.yarn.api.DtYarnConstants;
import com.dtstack.yarn.common.exceptions.RequestOverLimitException;
import com.dtstack.yarn.util.Utilities;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Client {

    private static final Log LOG = LogFactory.getLog(Client.class);

    private ClientArguments clientArguments;

    private DtYarnConfiguration conf;

    private YarnClient yarnClient;

    private YarnClientApplication newAPP;

    private StringBuffer appFilesRemotePath;

    private ApplicationId applicationId;

    private final FileSystem dfs;

    private static FsPermission JOB_FILE_PERMISSION;

    public Client(DtYarnConfiguration conf) throws IOException, ParseException, ClassNotFoundException, YarnException {
        this.conf = conf;

        this.dfs = FileSystem.get(conf);

        reset();

        yarnClient = YarnClient.createYarnClient();
        yarnClient.init(conf);
        yarnClient.start();
    }

    public void reset() {
        this.appFilesRemotePath = new StringBuffer(1000);
        JOB_FILE_PERMISSION = FsPermission.createImmutable((short) 0644);
    }

    public void init(String[] args) throws IOException, YarnException, ParseException, ClassNotFoundException {

        this.clientArguments = new ClientArguments(args);

        String appSubmitterUserName = System.getenv(ApplicationConstants.Environment.USER.name());
        if (conf.get("hadoop.job.ugi") == null) {
            UserGroupInformation ugi = UserGroupInformation.createRemoteUser(appSubmitterUserName);
            conf.set("hadoop.job.ugi", ugi.getUserName() + "," + ugi.getUserName());
        }

        conf.set(DtYarnConfiguration.LEARNING_AM_MEMORY, String.valueOf(clientArguments.amMem));
        conf.set(DtYarnConfiguration.LEARNING_AM_CORES, String.valueOf(clientArguments.amCores));
        conf.set(DtYarnConfiguration.LEARNING_WORKER_MEMORY, String.valueOf(clientArguments.workerMemory));
        conf.set(DtYarnConfiguration.LEARNING_WORKER_VCORES, String.valueOf(clientArguments.workerVCores));
        conf.set(DtYarnConfiguration.DT_WORKER_NUM, String.valueOf(clientArguments.workerNum));
        conf.set(DtYarnConfiguration.APP_PRIORITY, String.valueOf(clientArguments.priority));
        conf.setBoolean(DtYarnConfiguration.LEARNING_USER_CLASSPATH_FIRST, clientArguments.userClasspathFirst);

        if (clientArguments.queue == null || clientArguments.queue.equals("")) {
            clientArguments.queue = appSubmitterUserName;
        }
        conf.set(DtYarnConfiguration.LEARNING_APP_QUEUE, clientArguments.queue);

        if (clientArguments.confs != null) {
            setConf();
        }

        LOG.info("Requesting a new application from cluster with " + yarnClient.getYarnClusterMetrics().getNumNodeManagers() + " NodeManagers");
        newAPP = yarnClient.createApplication();
    }

    private void setConf() {
        Enumeration<String> confSet = (Enumeration<String>) clientArguments.confs.propertyNames();
        while (confSet.hasMoreElements()) {
            String confArg = confSet.nextElement();
            conf.set(confArg, clientArguments.confs.getProperty(confArg));
        }
    }

    public String submit(String[] args) throws IOException, YarnException, ParseException, ClassNotFoundException {
        reset();

        init(args);

        GetNewApplicationResponse newAppResponse = newAPP.getNewApplicationResponse();
        applicationId = newAppResponse.getApplicationId();
        LOG.info("Got new Application: " + applicationId.toString());

        Path jobConfPath = Utilities.getRemotePath(conf, applicationId, DtYarnConstants.LEARNING_JOB_CONFIGURATION);
        LOG.info("job conf path: " + jobConfPath);
        FSDataOutputStream out = FileSystem.create(jobConfPath.getFileSystem(conf), jobConfPath,
                new FsPermission(JOB_FILE_PERMISSION));
        conf.writeXml(out);
        out.close();
        Map<String, LocalResource> localResources = new HashMap<>();
        localResources.put(DtYarnConstants.LEARNING_JOB_CONFIGURATION,
                Utilities.createApplicationResource(dfs, jobConfPath, LocalResourceType.FILE));

        ApplicationSubmissionContext applicationContext = newAPP.getApplicationSubmissionContext();
        applicationContext.setApplicationId(applicationId);
        applicationContext.setApplicationName(clientArguments.appName);
        applicationContext.setApplicationType(clientArguments.appType.name());
        Path appJarSrc = new Path(clientArguments.appMasterJar);
        Path appJarDst = Utilities.getRemotePath(conf, applicationId, DtYarnConstants.APP_MASTER_JAR);
        LOG.info("Copying " + appJarSrc + " to remote path " + appJarDst.toString());
        dfs.copyFromLocalFile(false, true, appJarSrc, appJarDst);

        localResources.put(DtYarnConstants.APP_MASTER_JAR,
                Utilities.createApplicationResource(dfs, appJarDst, LocalResourceType.FILE));


        Map<String, String> appMasterEnv = new HashMap<>();
        StringBuilder classPathEnv = new StringBuilder("./*");

        for (String cp : conf.getStrings(DtYarnConfiguration.YARN_APPLICATION_CLASSPATH,
                DtYarnConfiguration.DEFAULT_XLEARNING_APPLICATION_CLASSPATH)) {
            classPathEnv.append(':');
            classPathEnv.append(cp.trim());
        }

        if (clientArguments.files != null) {
            Path[] xlearningFilesDst = new Path[clientArguments.files.length];
            LOG.info("Copy xlearning files from local filesystem to remote.");
            for (int i = 0; i < clientArguments.files.length; i++) {
                assert (!clientArguments.files[i].isEmpty());

                if(!clientArguments.files[i].startsWith("hdfs:")) { //local
                    Path xlearningFilesSrc = new Path(clientArguments.files[i]);
                    xlearningFilesDst[i] = Utilities.getRemotePath(
                            conf, applicationId, new Path(clientArguments.files[i]).getName());
                    LOG.info("Copying " + clientArguments.files[i] + " to remote path " + xlearningFilesDst[i].toString());
                    dfs.copyFromLocalFile(false, true, xlearningFilesSrc, xlearningFilesDst[i]);
                    appFilesRemotePath.append(xlearningFilesDst[i].toUri().toString()).append(",");
                } else { //hdfs
                    appFilesRemotePath.append(clientArguments.files[i]).append(",");
                }

            }
            appMasterEnv.put(DtYarnConstants.Environment.FILES_LOCATION.toString(),
                    appFilesRemotePath.deleteCharAt(appFilesRemotePath.length() - 1).toString());

        }

        if (StringUtils.isNotBlank(clientArguments.cacheFiles)) {
            String[] cacheFiles = StringUtils.split(clientArguments.cacheFiles, ",");
            for (String path : cacheFiles) {
                Path pathRemote;
                if (path.contains("#")) {
                    String[] paths = StringUtils.split(path, "#");
                    if (paths.length != 2) {
                        throw new RuntimeException("Error cacheFile path format " + path);
                    }
                    pathRemote = new Path(paths[0]);
                } else {
                    pathRemote = new Path(path);
                }

                if (!pathRemote.getFileSystem(conf).exists(pathRemote)) {
                    throw new IOException("cacheFile path " + pathRemote + " not existed!");
                }
            }
            appMasterEnv.put(DtYarnConstants.Environment.CACHE_FILE_LOCATION.toString(), clientArguments.cacheFiles);
        }

        appMasterEnv.put("CLASSPATH", classPathEnv.toString());
        appMasterEnv.put(DtYarnConstants.Environment.OUTPUTS.toString(), clientArguments.outputs.toString());
        appMasterEnv.put(DtYarnConstants.Environment.INPUTS.toString(), clientArguments.inputs.toString());
        appMasterEnv.put(DtYarnConstants.Environment.APP_TYPE.toString(), clientArguments.appType.toString());
        appMasterEnv.put(DtYarnConstants.Environment.XLEARNING_STAGING_LOCATION.toString(), Utilities.getRemotePath(conf, applicationId, "").toString());
        appMasterEnv.put(DtYarnConstants.Environment.APP_JAR_LOCATION.toString(), appJarDst.toUri().toString());
        appMasterEnv.put(DtYarnConstants.Environment.XLEARNING_JOB_CONF_LOCATION.toString(), jobConfPath.toString());


        /** launch command */
        String launchCmd = new LaunchCommandBuilder(clientArguments, conf).buildCmd();
        if (StringUtils.isNotBlank(launchCmd)) {
            appMasterEnv.put(DtYarnConstants.Environment.DT_EXEC_CMD.toString(), launchCmd);
        } else {
            throw new IllegalArgumentException("Invalid launch cmd for the application");
        }
        LOG.info("launch command: " + launchCmd);

        appMasterEnv.put(DtYarnConstants.Environment.XLEARNING_CONTAINER_MAX_MEMORY.toString(), String.valueOf(newAppResponse.getMaximumResourceCapability().getMemory()));


        LOG.info("Building application master launch command");
        List<String> appMasterArgs = new ArrayList<>(20);
        appMasterArgs.add("${JAVA_HOME}" + "/bin/java");
        appMasterArgs.add("-Xms" + conf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY) + "m");
        appMasterArgs.add("-Xmx" + conf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY) + "m");
        appMasterArgs.add(ApplicationMaster.class.getName());
        appMasterArgs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR
                + "/" + ApplicationConstants.STDOUT);
        appMasterArgs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR
                + "/" + ApplicationConstants.STDERR);

        StringBuilder command = new StringBuilder();
        for (String arg : appMasterArgs) {
            command.append(arg).append(" ");
        }

        LOG.info("Application master launch command: " + command.toString());
        List<String> appMasterLaunchcommands = new ArrayList<>();
        appMasterLaunchcommands.add(command.toString());

        Resource capability = Records.newRecord(Resource.class);
        capability.setMemory(conf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY));
        capability.setVirtualCores(conf.getInt(DtYarnConfiguration.LEARNING_AM_CORES, DtYarnConfiguration.DEFAULT_LEARNING_AM_CORES));
        applicationContext.setResource(capability);
        ContainerLaunchContext amContainer = ContainerLaunchContext.newInstance(
                localResources, appMasterEnv, appMasterLaunchcommands, null, null, null);
//
        applicationContext.setAMContainerSpec(amContainer);

        Priority priority = Records.newRecord(Priority.class);
        priority.setPriority(conf.getInt(DtYarnConfiguration.APP_PRIORITY, DtYarnConfiguration.DEFAULT_LEARNING_APP_PRIORITY));
        applicationContext.setPriority(priority);
        applicationContext.setQueue(conf.get(DtYarnConfiguration.LEARNING_APP_QUEUE, DtYarnConfiguration.DEFAULT_LEARNING_APP_QUEUE));
        System.out.println("hyf submitApplication");
        applicationId = yarnClient.submitApplication(applicationContext);

        return applicationId.toString();
    }

    private void checkArguments(DtYarnConfiguration conf, GetNewApplicationResponse newApplication) {
        int maxMem = newApplication.getMaximumResourceCapability().getMemory();
        LOG.info("Max mem capability of resources in this cluster " + maxMem);
        int maxVCores = newApplication.getMaximumResourceCapability().getVirtualCores();
        LOG.info("Max vcores capability of resources in this cluster " + maxVCores);

        int amMem = conf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY);
        int amCores = conf.getInt(DtYarnConfiguration.LEARNING_AM_CORES, DtYarnConfiguration.DEFAULT_LEARNING_AM_CORES);
        if (amMem > maxMem) {
            throw new RequestOverLimitException("AM memory requested " + amMem +
                    " above the max threshold of yarn cluster " + maxMem);
        }
        if (amMem <= 0) {
            throw new IllegalArgumentException(
                    "Invalid memory specified for application master, exiting."
                            + " Specified memory=" + amMem);
        }
        LOG.info("Apply for am Memory " + amMem + "M");
        if (amCores > maxVCores) {
            throw new RequestOverLimitException("am vcores requested " + amCores +
                    " above the max threshold of yarn cluster " + maxVCores);
        }
        if (amCores <= 0) {
            throw new IllegalArgumentException(
                    "Invalid vcores specified for am, exiting."
                            + "Specified vcores=" + amCores);
        }
        LOG.info("Apply for am vcores " + amCores);

        int workerNum = conf.getInt(DtYarnConfiguration.DT_WORKER_NUM, DtYarnConfiguration.DEFAULT_DT_WORKER_NUM);
        int workerMemory = conf.getInt(DtYarnConfiguration.LEARNING_WORKER_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_WORKER_MEMORY);
        int workerVcores = conf.getInt(DtYarnConfiguration.LEARNING_WORKER_VCORES, DtYarnConfiguration.DEFAULT_LEARNING_WORKER_VCORES);
        if (workerNum < 1) {
            throw new IllegalArgumentException(
                    "Invalid no. of worker specified, exiting."
                            + " Specified container number=" + workerNum);
        }
        LOG.info("Apply for worker number " + workerNum);
        if (workerMemory > maxMem) {
            throw new RequestOverLimitException("Worker memory requested " + workerMemory +
                    " above the max threshold of yarn cluster " + maxMem);
        }
        if (workerMemory <= 0) {
            throw new IllegalArgumentException(
                    "Invalid memory specified for worker, exiting."
                            + "Specified memory=" + workerMemory);
        }
        LOG.info("Apply for worker Memory " + workerMemory + "M");
        if (workerVcores > maxVCores) {
            throw new RequestOverLimitException("Worker vcores requested " + workerVcores +
                    " above the max threshold of yarn cluster " + maxVCores);
        }
        if (workerVcores <= 0) {
            throw new IllegalArgumentException(
                    "Invalid vcores specified for worker, exiting."
                            + "Specified vcores=" + workerVcores);
        }
        LOG.info("Apply for worker vcores " + workerVcores);

    }

    public void kill(String jobId) throws IOException, YarnException {
        ApplicationId appId = ConverterUtils.toApplicationId(jobId);
        yarnClient.killApplication(appId);
    }

    public ApplicationReport getApplicationReport(String jobId) throws IOException, YarnException {
        ApplicationId appId = ConverterUtils.toApplicationId(jobId);
        return yarnClient.getApplicationReport(appId);
    }

    public List<NodeReport> getNodeReports() throws IOException, YarnException {
        return yarnClient.getNodeReports(NodeState.RUNNING);
    }



    public static void main(String[] args) {
        try {
            LOG.info("Initializing Client");
            DtYarnConfiguration conf = new DtYarnConfiguration();
            Client client = new Client(conf);
            client.submit(args);
        } catch (Exception e) {
            LOG.fatal("Error running Client", e);
            System.exit(1);
        }
    }
}
