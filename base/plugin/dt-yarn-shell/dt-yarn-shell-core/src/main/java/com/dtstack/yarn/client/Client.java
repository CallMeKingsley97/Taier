package com.dtstack.yarn.client;


import com.dtstack.yarn.DtYarnConfiguration;
import com.dtstack.yarn.am.ApplicationMaster;
import com.dtstack.yarn.api.DtYarnConstants;
import com.dtstack.yarn.common.exceptions.RequestOverLimitException;
import com.dtstack.yarn.util.KerberosUtils;
import com.dtstack.yarn.util.Utilities;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Master;
import org.apache.hadoop.security.Credentials;
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
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {

    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    private static final AtomicBoolean REFRESH_APP_MASTER_JAR = new AtomicBoolean(true);

    private DtYarnConfiguration conf;
    private final FileSystem dfs;
    private YarnClient yarnClient;
    private Path appMasterJar;
    private String security = "false";
    private String hdfsPrincipal;
    private String hdfsKeytabPath;
    private String hdfsKrb5ConfPath;


    private static FsPermission JOB_FILE_PERMISSION = FsPermission.createImmutable((short) 0644);

    public Client(DtYarnConfiguration conf) throws IOException, ParseException, ClassNotFoundException, YarnException {
        this.conf = conf;
        if ("true".equals(conf.get("security"))){
            security = "true";
            hdfsPrincipal = conf.get("hdfsPrincipal");
            hdfsKeytabPath = conf.get("hdfsKeytabPath");
            hdfsKrb5ConfPath = conf.get("hdfsKrb5ConfPath");
            KerberosUtils.login(hdfsPrincipal, hdfsKeytabPath, hdfsKrb5ConfPath, conf);
        }
        this.dfs = FileSystem.get(conf);

        yarnClient = YarnClient.createYarnClient();
        yarnClient.init(conf);
        yarnClient.start();

        String appMasterJarPath = conf.get(DtYarnConfiguration.DTYARNSHELL_APPMASTERJAR_PATH, DtYarnConfiguration.DEFAULT_DTYARNSHELL_APPMASTERJAR_PATH);
        appMasterJar = Utilities.getRemotePath(conf, appMasterJarPath);
        if (REFRESH_APP_MASTER_JAR.get()) {
            synchronized (REFRESH_APP_MASTER_JAR) {
                if (REFRESH_APP_MASTER_JAR.get()) {
                    if (dfs.exists(appMasterJar)) {
                        if (dfs.delete(appMasterJar)) {
                            LOG.warn("Could not delete remote path " + appMasterJar.toString());
                        }
                    }
                    Path appJarSrc = new Path(JobConf.findContainingJar(ApplicationMaster.class));
                    LOG.info("Copying " + appJarSrc + " to remote path " + appMasterJar.toString());
                    dfs.copyFromLocalFile(false, true, appJarSrc, appMasterJar);
                    REFRESH_APP_MASTER_JAR.set(false);
                }
            }
        }
    }

    public YarnConfiguration init(ClientArguments clientArguments) throws IOException, YarnException, ParseException, ClassNotFoundException {

        YarnConfiguration taskConf = new YarnConfiguration((YarnConfiguration) conf);
        String appSubmitterUserName = System.getenv(ApplicationConstants.Environment.USER.name());
        if (taskConf.get("hadoop.job.ugi") == null) {
            UserGroupInformation ugi = UserGroupInformation.createRemoteUser(appSubmitterUserName);
            taskConf.set("hadoop.job.ugi", ugi.getUserName() + "," + ugi.getUserName());
        }

        taskConf.set(DtYarnConfiguration.LEARNING_AM_MEMORY, String.valueOf(clientArguments.amMem));
        taskConf.set(DtYarnConfiguration.LEARNING_AM_CORES, String.valueOf(clientArguments.amCores));
        taskConf.set(DtYarnConfiguration.LEARNING_WORKER_MEMORY, String.valueOf(clientArguments.workerMemory));
        taskConf.set(DtYarnConfiguration.LEARNING_WORKER_VCORES, String.valueOf(clientArguments.workerVCores));
        taskConf.set(DtYarnConfiguration.DT_WORKER_NUM, String.valueOf(clientArguments.workerNum));
        taskConf.set(DtYarnConfiguration.APP_PRIORITY, String.valueOf(clientArguments.priority));
        taskConf.setBoolean(DtYarnConfiguration.LEARNING_USER_CLASSPATH_FIRST, clientArguments.userClasspathFirst);

        taskConf.setBoolean(DtYarnConfiguration.APP_NODEMANAGER_EXCLUSIVE, clientArguments.exclusive);

        if (clientArguments.confs != null) {
            Enumeration<String> confSet = (Enumeration<String>) clientArguments.confs.propertyNames();
            while (confSet.hasMoreElements()) {
                String confArg = confSet.nextElement();
                taskConf.set(confArg, clientArguments.confs.getProperty(confArg));
            }
        }

        return taskConf;
    }

    public String submit(String[] args) throws IOException, YarnException, ParseException, ClassNotFoundException {
        ClientArguments clientArguments = new ClientArguments(args);

        YarnConfiguration taskConf = init(clientArguments);

        YarnClientApplication newAPP = yarnClient.createApplication();
        GetNewApplicationResponse newAppResponse = newAPP.getNewApplicationResponse();
        ApplicationId applicationId = newAppResponse.getApplicationId();
        LOG.info("Got new Application: " + applicationId.toString());

        Path jobConfPath = Utilities.getRemotePath(taskConf, applicationId, DtYarnConstants.LEARNING_JOB_CONFIGURATION);
        LOG.info("job conf path: " + jobConfPath);
        FSDataOutputStream out = FileSystem.create(jobConfPath.getFileSystem(taskConf), jobConfPath,
                new FsPermission(JOB_FILE_PERMISSION));
        taskConf.writeXml(out);
        out.close();
        Map<String, LocalResource> localResources = new HashMap<>();
        localResources.put(DtYarnConstants.LEARNING_JOB_CONFIGURATION,
                Utilities.createApplicationResource(dfs, jobConfPath, LocalResourceType.FILE));

        ApplicationSubmissionContext applicationContext = newAPP.getApplicationSubmissionContext();
        applicationContext.setApplicationId(applicationId);
        applicationContext.setApplicationName(clientArguments.appName);
        applicationContext.setApplicationType(clientArguments.appType.name());

        localResources.put(DtYarnConstants.APP_MASTER_JAR,
                Utilities.createApplicationResource(dfs, appMasterJar, LocalResourceType.FILE));


        Map<String, String> appMasterEnv = new HashMap<>();
        StringBuilder classPathEnv = new StringBuilder("./*");

        for (String cp : taskConf.getStrings(DtYarnConfiguration.YARN_APPLICATION_CLASSPATH,
                DtYarnConfiguration.DEFAULT_XLEARNING_APPLICATION_CLASSPATH)) {
            classPathEnv.append(':');
            classPathEnv.append(cp.trim());
        }

        if (clientArguments.files != null) {
            StringBuffer appFilesRemotePath = new StringBuffer(1000);
            Path[] xlearningFilesDst = new Path[clientArguments.files.length];
            LOG.info("Copy dtyarnshell files from local filesystem to remote.");
            for (int i = 0; i < clientArguments.files.length; i++) {
                assert (!clientArguments.files[i].isEmpty());

                if (!clientArguments.files[i].startsWith("hdfs:")) { //local
                    Path xlearningFilesSrc = new Path(clientArguments.files[i]);
                    xlearningFilesDst[i] = Utilities.getRemotePath(
                            taskConf, applicationId, new Path(clientArguments.files[i]).getName());
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

                if (!pathRemote.getFileSystem(taskConf).exists(pathRemote)) {
                    throw new IOException("cacheFile path " + pathRemote + " not existed!");
                }
            }
            appMasterEnv.put(DtYarnConstants.Environment.CACHE_FILE_LOCATION.toString(), clientArguments.cacheFiles);
        }

        appMasterEnv.put("CLASSPATH", classPathEnv.toString());
        appMasterEnv.put("HADOOP_HOME", conf.get(DtYarnConfiguration.DT_HADOOP_HOME_DIR));
        appMasterEnv.put(DtYarnConstants.Environment.OUTPUTS.toString(), clientArguments.outputs.toString());
        appMasterEnv.put(DtYarnConstants.Environment.INPUTS.toString(), clientArguments.inputs.toString());
        appMasterEnv.put(DtYarnConstants.Environment.APP_TYPE.toString(), clientArguments.appType.name());
        appMasterEnv.put(DtYarnConstants.Environment.XLEARNING_STAGING_LOCATION.toString(), Utilities.getRemotePath(taskConf, applicationId, "").toString());
        appMasterEnv.put(DtYarnConstants.Environment.XLEARNING_JOB_CONF_LOCATION.toString(), jobConfPath.toString());


        /** launch command */
        LOG.info("Building app launch command");
        String launchCmd = new LaunchCommandBuilder(clientArguments, taskConf).buildCmd();
        if (StringUtils.isNotBlank(launchCmd)) {
            appMasterEnv.put(DtYarnConstants.Environment.DT_EXEC_CMD.toString(), launchCmd);
        } else {
            throw new IllegalArgumentException("Invalid launch cmd for the application");
        }
        LOG.info("app launch command: " + launchCmd);

        appMasterEnv.put(DtYarnConstants.Environment.XLEARNING_CONTAINER_MAX_MEMORY.toString(), String.valueOf(newAppResponse.getMaximumResourceCapability().getMemory()));


        LOG.info("Building application master launch command");
        List<String> appMasterArgs = new ArrayList<>(20);
        appMasterArgs.add("${JAVA_HOME}" + "/bin/java");
        appMasterArgs.add("-Xms" + taskConf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY) + "m");
        appMasterArgs.add("-Xmx" + taskConf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY) + "m");
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

        ByteBuffer token = null;
        if ("true".equals(conf.get("security"))){
            Credentials credentials = new Credentials(UserGroupInformation.getCurrentUser().getCredentials());
            dfs.addDelegationTokens(Master.getMasterPrincipal(conf), credentials);
            DataOutputBuffer dob = new DataOutputBuffer();
            credentials.writeTokenStorageToStream(dob);
            token = ByteBuffer.wrap(dob.getData());
        }

        Resource capability = Records.newRecord(Resource.class);
        capability.setMemory(taskConf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY));
        capability.setVirtualCores(taskConf.getInt(DtYarnConfiguration.LEARNING_AM_CORES, DtYarnConfiguration.DEFAULT_LEARNING_AM_CORES));
        applicationContext.setResource(capability);
        ContainerLaunchContext amContainer = ContainerLaunchContext.newInstance(
                localResources, appMasterEnv, appMasterLaunchcommands, null, token, null);

        applicationContext.setAMContainerSpec(amContainer);

        Priority priority = Records.newRecord(Priority.class);
        priority.setPriority(taskConf.getInt(DtYarnConfiguration.APP_PRIORITY, DtYarnConfiguration.DEFAULT_LEARNING_APP_PRIORITY));
        applicationContext.setPriority(priority);
        applicationContext.setQueue(taskConf.get(DtYarnConfiguration.DT_APP_QUEUE, DtYarnConfiguration.DEFAULT_DT_APP_QUEUE));
        applicationId = yarnClient.submitApplication(applicationContext);

        return applicationId.toString();
    }

    private void checkArguments(DtYarnConfiguration taskConf, GetNewApplicationResponse newApplication) {
        int maxMem = newApplication.getMaximumResourceCapability().getMemory();
        LOG.info("Max mem capability of resources in this cluster " + maxMem);
        int maxVCores = newApplication.getMaximumResourceCapability().getVirtualCores();
        LOG.info("Max vcores capability of resources in this cluster " + maxVCores);

        int amMem = taskConf.getInt(DtYarnConfiguration.LEARNING_AM_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY);
        int amCores = taskConf.getInt(DtYarnConfiguration.LEARNING_AM_CORES, DtYarnConfiguration.DEFAULT_LEARNING_AM_CORES);
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

        int workerNum = taskConf.getInt(DtYarnConfiguration.DT_WORKER_NUM, DtYarnConfiguration.DEFAULT_DT_WORKER_NUM);
        int workerMemory = taskConf.getInt(DtYarnConfiguration.LEARNING_WORKER_MEMORY, DtYarnConfiguration.DEFAULT_LEARNING_WORKER_MEMORY);
        int workerVcores = taskConf.getInt(DtYarnConfiguration.LEARNING_WORKER_VCORES, DtYarnConfiguration.DEFAULT_LEARNING_WORKER_VCORES);
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

    public YarnClient getYarnClient() {
        return yarnClient;
    }

    public List<String> getContainerInfos(String jobId) throws IOException {
        ApplicationId appId = ConverterUtils.toApplicationId(jobId);
        Path cIdPath = Utilities.getRemotePath(conf, appId, "containers");
        FileStatus[] status = dfs.listStatus(cIdPath);
        List<String> infos = new ArrayList<>(status.length);
        for (FileStatus file : status) {
            if (!file.getPath().getName().startsWith("container")) {
                continue;
            }
            FSDataInputStream inputStream = dfs.open(file.getPath());
            InputStreamReader isr = new InputStreamReader(inputStream, "UTF-8");
            BufferedReader br = new BufferedReader(isr);
            StringBuilder lineString = new StringBuilder();
            String line = null;
            while ((line = br.readLine()) != null) {
                lineString.append(line);
            }
            infos.add(lineString.toString());
        }
        return infos;
    }
}