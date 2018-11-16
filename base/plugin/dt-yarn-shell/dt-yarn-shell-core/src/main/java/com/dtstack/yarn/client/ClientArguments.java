package com.dtstack.yarn.client;

import com.dtstack.yarn.DtYarnConfiguration;
import com.dtstack.yarn.common.type.AppType;
import com.dtstack.yarn.common.JobPriority;
import com.google.gson.Gson;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class ClientArguments {

    private static final Logger LOG = LoggerFactory.getLogger(ClientArguments.class);

    private Options allOptions;
    String appName;
    AppType appType;
    int amMem;
    int amCores;
    int workerMemory;
    int workerVCores;
    int workerNum;
    int psMemory;
    int psVCores;
    int psNum;
    String[] files;
    Configuration remoteConf;
    String[] libJars;
    String launchCmd;
    int priority;
    String queue;
    String userPath;
    String appMasterJar;
    Boolean isRenameInputFile;
    public Boolean userClasspathFirst;
    public int streamEpoch;
    public Boolean inputStreamShuffle;
    public Class<?> inputFormatClass;
    public Class<?> outputFormatClass;
    Properties confs;
    String pythonVersion;
    String cmdOpts;
    String outputs;
    String inputs;
    String cacheFiles;
    String uploadFiles;
    Boolean exclusive;



    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public AppType getAppType() {
        return appType;
    }

    public void setAppType(AppType appType) {
        this.appType = appType;
    }

    public int getAmMem() {
        return amMem;
    }

    public void setAmMem(int amMem) {
        this.amMem = amMem;
    }

    public int getAmCores() {
        return amCores;
    }

    public void setAmCores(int amCores) {
        this.amCores = amCores;
    }

    public int getWorkerMemory() {
        return workerMemory;
    }

    public void setWorkerMemory(int workerMemory) {
        this.workerMemory = workerMemory;
    }

    public int getWorkerVCores() {
        return workerVCores;
    }

    public void setWorkerVCores(int workerVCores) {
        this.workerVCores = workerVCores;
    }

    public int getWorkerNum() {
        return workerNum;
    }

    public void setWorkerNum(int workerNum) {
        this.workerNum = workerNum;
    }

    public int getPsMemory() {
        return psMemory;
    }

    public void setPsMemory(int psMemory) {
        this.psMemory = psMemory;
    }

    public int getPsVCores() {
        return psVCores;
    }

    public void setPsVCores(int psVCores) {
        this.psVCores = psVCores;
    }

    public int getPsNum() {
        return psNum;
    }

    public void setPsNum(int psNum) {
        this.psNum = psNum;
    }

    public String[] getFiles() {
        return files;
    }

    public void setFiles(String[] files) {
        this.files = files;
    }

    public Configuration getRemoteConf() {
        return remoteConf;
    }

    public void setRemoteConf(Configuration remoteConf) {
        this.remoteConf = remoteConf;
    }

    public String[] getLibJars() {
        return libJars;
    }

    public void setLibJars(String[] libJars) {
        this.libJars = libJars;
    }

    public String getLaunchCmd() {
        return launchCmd;
    }

    public void setLaunchCmd(String launchCmd) {
        this.launchCmd = launchCmd;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getUserPath() {
        return userPath;
    }

    public void setUserPath(String userPath) {
        this.userPath = userPath;
    }

    public String getAppMasterJar() {
        return appMasterJar;
    }

    public void setAppMasterJar(String appMasterJar) {
        this.appMasterJar = appMasterJar;
    }

    public Boolean getRenameInputFile() {
        return isRenameInputFile;
    }

    public void setRenameInputFile(Boolean renameInputFile) {
        isRenameInputFile = renameInputFile;
    }

    public Boolean getUserClasspathFirst() {
        return userClasspathFirst;
    }

    public void setUserClasspathFirst(Boolean userClasspathFirst) {
        this.userClasspathFirst = userClasspathFirst;
    }

    public int getStreamEpoch() {
        return streamEpoch;
    }

    public void setStreamEpoch(int streamEpoch) {
        this.streamEpoch = streamEpoch;
    }

    public Boolean getInputStreamShuffle() {
        return inputStreamShuffle;
    }

    public void setInputStreamShuffle(Boolean inputStreamShuffle) {
        this.inputStreamShuffle = inputStreamShuffle;
    }

    public Class<?> getInputFormatClass() {
        return inputFormatClass;
    }

    public void setInputFormatClass(Class<?> inputFormatClass) {
        this.inputFormatClass = inputFormatClass;
    }

    public Class<?> getOutputFormatClass() {
        return outputFormatClass;
    }

    public void setOutputFormatClass(Class<?> outputFormatClass) {
        this.outputFormatClass = outputFormatClass;
    }

    public Properties getConfs() {
        return confs;
    }

    public void setConfs(Properties confs) {
        this.confs = confs;
    }

    public String getPythonVersion() {
        return pythonVersion;
    }

    public void setPythonVersion(String pythonVersion) {
        this.pythonVersion = pythonVersion;
    }

    public String getCmdOpts() {
        return cmdOpts;
    }

    public void setCmdOpts(String cmdOpts) {
        this.cmdOpts = cmdOpts;
    }

    public String getOutputs() {
        return outputs;
    }

    public void setOutputs(String outputs) {
        this.outputs = outputs;
    }

    public String getInputs() {
        return inputs;
    }

    public void setInputs(String inputs) {
        this.inputs = inputs;
    }

    public String getCacheFiles() {
        return cacheFiles;
    }

    public void setCacheFiles(String cacheFiles) {
        this.cacheFiles = cacheFiles;
    }

    public String getUploadFiles() {
        return uploadFiles;
    }

    public void setUploadFiles(String uploadFiles) {
        this.uploadFiles = uploadFiles;
    }

    public Boolean getExclusive() {
        return exclusive;
    }

    public void setExclusive(Boolean exclusive) {
        this.exclusive = exclusive;
    }

    public ClientArguments(String[] args) throws IOException, ParseException, ClassNotFoundException {
        this.init();
        this.cliParser(args);
    }

    private void init() {
        appName = "";
        appType = AppType.fromString("");
        amMem = DtYarnConfiguration.DEFAULT_LEARNING_AM_MEMORY;
        amCores = DtYarnConfiguration.DEFAULT_LEARNING_AM_CORES;
        workerMemory = DtYarnConfiguration.DEFAULT_LEARNING_WORKER_MEMORY;
        workerVCores = DtYarnConfiguration.DEFAULT_LEARNING_WORKER_VCORES;
        workerNum = DtYarnConfiguration.DEFAULT_DT_WORKER_NUM;
        pythonVersion = DtYarnConfiguration.DEFAULT_LEARNING_PYTHON_VERSION;
        files = null;
        cacheFiles = "";
        libJars = null;
        launchCmd = "";
        outputs = "";
        inputs = "";
        cmdOpts = "";
        appMasterJar = "";
        userPath = "";
        priority = DtYarnConfiguration.DEFAULT_LEARNING_APP_PRIORITY;
        queue = "default";
        userClasspathFirst = DtYarnConfiguration.DEFAULT_LEARNING_USER_CLASSPATH_FIRST;
        exclusive = DtYarnConfiguration.DEFAULT_APP_NODEMANAGER_EXCLUSIVE;


        allOptions = new Options();
        allOptions.addOption("test", "test", false, "test mode");
        allOptions.addOption("appName", "app-name", true,
                "set the Application name");
        allOptions.addOption("appType", "app-type", true,
                "set the Application type, default \"XLEARNING\"");

        allOptions.addOption("amMemory", "am-memory", true,
                "Amount of memory in MB to be requested to run the application master");
        allOptions.addOption("amCores", "am-cores", true,
                "Amount of vcores to be requested to run the application master");

        allOptions.addOption("workerMemory", "worker-memory", true,
                "Amount of memory in MB to be requested to run worker");
        allOptions.addOption("workerCores", "worker-cores", true,
                "Amount of vcores to be requested to run worker");
        allOptions.addOption("workerNum", "worker-num", true,
                "No. of containers on which the worker needs to be executed");

        allOptions.addOption("psMemory", "ps-memory", true,
                "Amount of memory in MB to be requested to run ps");
        allOptions.addOption("psCores", "ps-cores", true,
                "Amount of vcores to be requested to run ps");
        allOptions.addOption("psNum", "ps-num", true,
                "No. of containers on which the ps needs to be executed");

        allOptions.addOption("files", "files", true,
                "Location of the XLearning files used in container");
        allOptions.addOption("jars", "jars", true,
                "Location of the XLearning lib jars used in container");

        allOptions.addOption("launchCmd", "launch-cmd", true, "Cmd for XLearning program");
        allOptions.addOption("userPath", "user-path", true,
                "add the user set PATH");

        allOptions.addOption("cacheFile", "cache-file", true,
                "cache the XLearning hdfs file to local fs");

        allOptions.addOption("output", "output", true,
                "upload local file to hdfs");

        allOptions.addOption("cacheArchive", "cacheArchive", true,
                "add the XLearning hdfsPackage PATH");
        allOptions.addOption("priority", "priority", true, "Application Priority. Default DEFAULT");
        allOptions.addOption("queue", "queue", true,
                "RM Queue in which this application is to be submitted");
        allOptions.addOption("userClasspathFirst", "user-classpath-first", true,
                "whether user add classpath first or not, default:true");

        allOptions.addOption("inputformatShuffle", "inputformat-shuffle", true,
                "If inputformat-enable is true, whether shuffle data in worker or not, default:false");
        allOptions.addOption("inputFormatClass", "inputformat", true,
                "The inputformat class, default:org.apache.hadoop.mapred.TextInputFormat");
        allOptions.addOption("outputFormatClass", "outputformat", true,
                "The outputformat class, default:org.apache.hadoop.mapred.lib.TextMultiOutputFormat");
        allOptions.addOption("streamEpoch", "stream-epoch", true,
                "The num of epoch for stream input.");
        allOptions.addOption("inputStrategy", "input-strategy", true,
                "The input strategy for user data input, DOWNLOAD,PLACEHOLDER or STREAM, default:DOWNLOAD");
        allOptions.addOption("outputStrategy", "output-strategy", true,
                "The output strategy for user result output, UPLOAD or STREAM, default:UPLOAD");

        allOptions.addOption("help", "help", false, "Print usage");

        allOptions.addOption("cmdOpts", "cmd-opts", true, "command opts");

        allOptions.addOption("pythonVersion", "python-version", true, "python version");

        allOptions.addOption("remoteDfsConfig", "remote-dfs-config", true, "hdfs config for files waiting for uploading");

        allOptions.addOption("exclusive", "exclusive", true, "app nodemanager exclusive");


        OptionBuilder.withArgName("property=value");
        OptionBuilder.hasArgs(Integer.MAX_VALUE);
        OptionBuilder
                .withValueSeparator('=');
        OptionBuilder
                .withDescription("XLearning configure");
        Option conf = OptionBuilder
                .create("conf");
        allOptions.addOption(conf);

        OptionBuilder.withArgName("property#value");
        OptionBuilder.hasArgs(Integer.MAX_VALUE);
        OptionBuilder
                .withValueSeparator('#');
        OptionBuilder
                .withDescription("dfs location,representing the source data of XLearning");
        Option property = OptionBuilder
                .create("input");
        allOptions.addOption(property);

    }

    private void adjustParams() {

    }

    private void cliParser(String[] args) throws ParseException, IOException, ClassNotFoundException {
        CommandLine commandLine = new BasicParser().parse(allOptions, args);
        if (commandLine.getOptions().length == 0 || commandLine.hasOption("help")) {
            printUsage(allOptions);
            System.exit(0);
        }

        if (commandLine.hasOption("app-name")) {
            appName = commandLine.getOptionValue("app-name");
        }

        if (appName.trim().equals("")) {
            appName = "XLearning-" + System.currentTimeMillis();
        }

        if (commandLine.hasOption("app-type")) {
            appType = AppType.fromString(commandLine.getOptionValue("app-type").trim());
        }

        if (commandLine.hasOption("am-memory")) {
            amMem = getNormalizedMem(commandLine.getOptionValue("am-memory"));
        }

        if (commandLine.hasOption("am-cores")) {
            String workerVCoresStr = commandLine.getOptionValue("am-cores");
            amCores = Integer.parseInt(workerVCoresStr);
        }

        if (commandLine.hasOption("worker-memory")) {
            workerMemory = getNormalizedMem(commandLine.getOptionValue("worker-memory"));
        }

        if (commandLine.hasOption("worker-cores")) {
            String workerVCoresStr = commandLine.getOptionValue("worker-cores");
            workerVCores = Integer.parseInt(workerVCoresStr);
        }

        if (commandLine.hasOption("worker-num")) {
            String workerNumStr = commandLine.getOptionValue("worker-num");
            workerNum = Integer.parseInt(workerNumStr);
        }

        if (commandLine.hasOption("priority")) {
            String priorityStr = commandLine.getOptionValue("priority");
            for (JobPriority e : JobPriority.values()) {
                if (priorityStr.equals(e.toString())) {
                    priority = e.ordinal();
                }
            }
        }

        if (commandLine.hasOption("queue")) {
            queue = commandLine.getOptionValue("queue");
        }

        if (commandLine.hasOption("output")) {
            outputs = commandLine.getOptionValue("output");
            LOG.info("my outputs: " + outputs);
        }

        if (commandLine.hasOption("user-path")) {
            userPath = commandLine.getOptionValue("user-path");
        }

        if (commandLine.hasOption(CliOptions.OPT_FILES)) {
            files = StringUtils.split(commandLine.getOptionValue(CliOptions.OPT_FILES), ",");
        }

        if (commandLine.hasOption(CliOptions.OPT_REMOTE_DFS_CONFIG)) {
            String json = commandLine.getOptionValue(CliOptions.OPT_REMOTE_DFS_CONFIG);
            Map<String, String> map = new Gson().fromJson(json, Map.class);
            remoteConf = new Configuration();
            remoteConf.set("fs.hdfs.impl.disable.cache", "true");
            remoteConf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
            for (Map.Entry<String, String> entry : map.entrySet()) {
                remoteConf.set(entry.getKey(), entry.getValue());
            }
        }

        if (commandLine.hasOption("jars")) {
            libJars = StringUtils.split(commandLine.getOptionValue("jars"), ",");
        }

        if (commandLine.hasOption("userClasspathFirst")) {
            String classpathFirst = commandLine.getOptionValue("userClasspathFirst");
            userClasspathFirst = Boolean.parseBoolean(classpathFirst);
        }

        if (commandLine.hasOption("launch-cmd")) {
            launchCmd = commandLine.getOptionValue("launch-cmd");
        }

        if (commandLine.hasOption(CliOptions.OPT_CMD_OPTS)) {
            cmdOpts = commandLine.getOptionValue(CliOptions.OPT_CMD_OPTS);
        }

        if (commandLine.hasOption(CliOptions.OPT_PYTHON_VERTION)) {
            pythonVersion = commandLine.getOptionValue(CliOptions.OPT_PYTHON_VERTION);
        }

        if (commandLine.hasOption("isRenameInputFile")) {
            String renameInputFile = commandLine.getOptionValue("isRenameInputFile");
            isRenameInputFile = Boolean.parseBoolean(renameInputFile);
        }

        if (commandLine.hasOption("inputformat-shuffle")) {
            String inputStreamShuffleStr = commandLine.getOptionValue("inputformat-shuffle");
            inputStreamShuffle = Boolean.parseBoolean(inputStreamShuffleStr);
        }

        if (commandLine.hasOption("inputformat")) {
            inputFormatClass = Class.forName(commandLine.getOptionValue("inputformat"));
        }

        if (commandLine.hasOption("outputformat")) {
            outputFormatClass = Class.forName(commandLine.getOptionValue("outputformat"));
        }

        if (commandLine.hasOption("input")) {
            inputs =  commandLine.getOptionValue("input");
            LOG.info("my inputs: " + inputs);
        }

        if (commandLine.hasOption("stream-epoch")) {
            String streamEpochStr = commandLine.getOptionValue("stream-epoch");
            streamEpoch = Integer.parseInt(streamEpochStr);
        }

        if (commandLine.hasOption("cacheFile")) {
            cacheFiles = commandLine.getOptionValue("cacheFile");
        }

        if (commandLine.hasOption("uploadFile")) {
            uploadFiles = commandLine.getOptionValue("uploadFile");
        }

        if (commandLine.hasOption("exclusive")) {
            String exclusiveStr = commandLine.getOptionValue("exclusive");
            exclusive = Boolean.parseBoolean(exclusiveStr);
        }

    }

    private void printUsage(Options opts) {
        new HelpFormatter().printHelp("Client", opts);
    }

    private int getNormalizedMem(String rawMem) {
        if (rawMem.endsWith("G") || rawMem.endsWith("g")) {
            return Integer.parseInt(rawMem.substring(0, rawMem.length() - 1)) * 1024;
        } else if (rawMem.endsWith("M") || rawMem.endsWith("m")) {
            return Integer.parseInt(rawMem.substring(0, rawMem.length() - 1));
        } else {
            return Integer.parseInt(rawMem);
        }
    }

}