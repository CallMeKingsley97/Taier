package com.dtstack.yarn.am;

import com.dtstack.yarn.DtYarnConfiguration;
import com.dtstack.yarn.api.ApplicationContext;
import com.dtstack.yarn.api.DtYarnConstants;
import com.dtstack.yarn.container.ContainerEntity;
import com.dtstack.yarn.container.DtContainer;
import com.dtstack.yarn.container.DtContainerId;
import com.dtstack.yarn.util.DebugUtil;
import com.dtstack.yarn.util.Utilities;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.service.CompositeService;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.util.Records;

import java.io.IOException;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;


public class ApplicationMaster extends CompositeService {

    private static final Log LOG = LogFactory.getLog(ApplicationMaster.class);

    AMRMClientAsync<AMRMClient.ContainerRequest> amrmAsync;

    NMClientAsync nmAsync;

    private ApplicationMessageService messageService;

    private RMCallbackHandler rmCallbackHandler;

    private ApplicationWebService webService;

    final Configuration conf = new DtYarnConfiguration();

    final ApplicationContext applicationContext;

    ApplicationAttemptId applicationAttemptID;

    private AMRMClient.ContainerRequest workerContainerRequest;

    AppArguments appArguments;

    /** An RPC Service listening the container status */
    ApplicationContainerListener containerListener;


    private ApplicationMaster(String name) {
        super(name);
        Path jobConfPath = new Path(DtYarnConstants.LEARNING_JOB_CONFIGURATION);
        LOG.info("user.dir: " + System.getProperty("user.dir"));
        LOG.info("user.name: " + System.getProperty("user.name"));
        conf.addResource(jobConfPath);
        applicationContext = new RunningAppContext(this);
        messageService = new ApplicationMessageService(this.applicationContext, conf);
        appArguments = new AppArguments(this);
        containerListener = new ApplicationContainerListener(applicationContext, conf);
    }

    private ApplicationMaster() {
        this(ApplicationMaster.class.getName());
    }

    private void init() {
        LOG.info("appmaster init start...");
        rmCallbackHandler = new RMCallbackHandler();
        amrmAsync = AMRMClientAsync.createAMRMClientAsync(1000, rmCallbackHandler);
        amrmAsync.init(conf);

        NMCallbackHandler nmAsyncHandler = new NMCallbackHandler(this);
        this.nmAsync = NMClientAsync.createNMClientAsync(nmAsyncHandler);
        this.nmAsync.init(conf);

        addService(amrmAsync);
        addService(nmAsync);
        addService(messageService);
        addService(containerListener);

        try {
            serviceStart();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LOG.info("appmaster init end...");
    }

    private void register() {
        LOG.info("appmaster register start...");
        try {
            LOG.info("amrmAsync: " + amrmAsync);
            amrmAsync.registerApplicationMaster(this.messageService.getServerAddress().getHostName(),
                    this.messageService.getServerAddress().getPort(), null);
        } catch (Exception e) {
            LOG.info("app master register failed: " + DebugUtil.stackTrace(e));
            throw new RuntimeException("Registering application master failed,", e);
        }
        LOG.info("appmaster register end...");
    }

    private void unregister(FinalApplicationStatus finalStatus, String diagnostics) {
        try {
            amrmAsync.unregisterApplicationMaster(finalStatus, diagnostics,
                    null);
            amrmAsync.stop();
        } catch (Exception e) {
            LOG.error("Error while unregister Application", e);
        }
    }

    private void buildContainerRequest() {
        Priority priority = Records.newRecord(Priority.class);
        priority.setPriority(appArguments.appPriority);
        Resource workerCapability = Records.newRecord(Resource.class);
        workerCapability.setMemory(appArguments.workerMemory);
        workerCapability.setVirtualCores(appArguments.workerVCores);
        workerContainerRequest = new AMRMClient.ContainerRequest(workerCapability, null, null, priority);
    }

    private List<String> buildContainerLaunchCommand(int containerMemory) {
        List<String> containerLaunchcommands = new ArrayList<>();
        LOG.info("Setting up container command");
        Vector<CharSequence> vargs = new Vector<>(10);
        vargs.add("${JAVA_HOME}" + "/bin/java");
        vargs.add("-Xmx" + containerMemory + "m");
        vargs.add("-Xms" + containerMemory + "m");
        String javaOpts = conf.get(DtYarnConfiguration.XLEARNING_CONTAINER_EXTRA_JAVA_OPTS, DtYarnConfiguration.DEFAULT_XLEARNING_CONTAINER_JAVA_OPTS_EXCEPT_MEMORY);
        if (!StringUtils.isBlank(javaOpts)) {
            vargs.add(javaOpts);
        }
        vargs.add(DtContainer.class.getName());
        vargs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + ApplicationConstants.STDOUT);
        vargs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + ApplicationConstants.STDERR);

        StringBuilder containerCmd = new StringBuilder();
        for (CharSequence str : vargs) {
            containerCmd.append(str).append(" ");
        }
        containerLaunchcommands.add(containerCmd.toString());
        LOG.info("Container launch command: " + containerLaunchcommands.toString());
        return containerLaunchcommands;
    }

    private boolean run() throws IOException, NoSuchAlgorithmException, InterruptedException {
        LOG.info("ApplicationMaster Starting ...");

        register();

        LOG.info("Try to allocate " + appArguments.workerNum + " worker containers");
        buildContainerRequest();
        List<String> workerContainerLaunchCommands = buildContainerLaunchCommand(appArguments.workerMemory);
        Map<String, LocalResource> containerLocalResource = buildContainerLocalResource();
        Map<String, String> workerContainerEnv = new ContainerEnvBuilder(DtYarnConstants.WORKER, this).build();

        for(int i = 0; i < appArguments.workerNum; ++i) {
            amrmAsync.addContainerRequest(workerContainerRequest);
        }

        for(int i = 0; i < appArguments.workerNum; ++i) {
            Container container = rmCallbackHandler.take();
            LOG.info("containerAddress: " + container.getNodeHttpAddress());
            launchContainer(containerLocalResource, workerContainerEnv,
                    workerContainerLaunchCommands, container, i);
            containerListener.registerContainer(-1, new DtContainerId(container.getId()));
        }

        while(!containerListener.isFinished()) {
            Utilities.sleep(1000);
            List<ContainerEntity> entities = containerListener.getFailedContainerEntities();

            if(entities.isEmpty()) {
                continue;
            }

            for(int i = 0; i < entities.size(); ++i) {
                amrmAsync.addContainerRequest(workerContainerRequest);
            }

            for(int i = 0; i < entities.size(); ++i) {
                Container container = rmCallbackHandler.take();
                launchContainer(containerLocalResource, workerContainerEnv,
                        workerContainerLaunchCommands, container, entities.get(i).getLane());
                containerListener.registerContainer(entities.get(i).getLane(), new DtContainerId(container.getId()));
            }

        }

        if(containerListener.isFailed()) {
            unregister(FinalApplicationStatus.FAILED, containerListener.getFailedMsg());
            return false;
        } else {
            unregister(FinalApplicationStatus.SUCCEEDED, "Task is success.");
            return true;
        }

    }

    private Map<String, LocalResource> buildContainerLocalResource() {
        URI defaultUri = new Path(conf.get("fs.defaultFS")).toUri();
        Map<String, LocalResource> containerLocalResource = new HashMap<>();
        try {
            containerLocalResource.put(DtYarnConstants.APP_MASTER_JAR,
                    Utilities.createApplicationResource(appArguments.appJarRemoteLocation.getFileSystem(conf),
                            appArguments.appJarRemoteLocation,
                            LocalResourceType.FILE));
            containerLocalResource.put(DtYarnConstants.LEARNING_JOB_CONFIGURATION,
                    Utilities.createApplicationResource(appArguments.appConfRemoteLocation.getFileSystem(conf),
                            appArguments.appConfRemoteLocation,
                            LocalResourceType.FILE));

            if (appArguments.appFilesRemoteLocation != null) {
                String[] xlearningFiles = StringUtils.split(appArguments.appFilesRemoteLocation , ",");
                for (String file : xlearningFiles) {
                    Path path = new Path(file);
                    containerLocalResource.put(path.getName(),
                            Utilities.createApplicationResource(path.getFileSystem(conf),
                                    path,
                                    LocalResourceType.FILE));
                }
            }

            if (appArguments.appCacheFilesRemoteLocation != null) {
                String[] cacheFiles = StringUtils.split(appArguments.appCacheFilesRemoteLocation, ",");
                for (String path : cacheFiles) {
                    Path pathRemote;
                    String aliasName;
                    if (path.contains("#")) {
                        String[] paths = StringUtils.split(path, "#");
                        if (paths.length != 2) {
                            throw new RuntimeException("Error cacheFile path format " + appArguments.appCacheFilesRemoteLocation);
                        }
                        pathRemote = new Path(paths[0]);
                        aliasName = paths[1];
                    } else {
                        pathRemote = new Path(path);
                        aliasName = pathRemote.getName();
                    }
                    URI pathRemoteUri = pathRemote.toUri();
                    if (pathRemoteUri.getScheme() == null || pathRemoteUri.getHost() == null) {
                        pathRemote = new Path(defaultUri.toString(), pathRemote.toString());
                    }
                    LOG.info("Cache file remote path is " + pathRemote + " and alias name is " + aliasName);
                    containerLocalResource.put(aliasName,
                            Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                                    pathRemote,
                                    LocalResourceType.FILE));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error while build container local resource", e);
        }
        return containerLocalResource;
    }

    /**
     * Async Method telling NMClientAsync to launch specific container
     *
     * @param container the container which should be launched
     * @return is launched success
     */
    private void launchContainer(Map<String, LocalResource> containerLocalResource,
                                 Map<String, String> containerEnv,
                                 List<String> containerLaunchcommands,
                                 Container container, int index) throws IOException {
        System.out.println("container nodeId: " + container.getNodeId().toString());
        LOG.info("Setting up launch context for containerID="
                + container.getId());

        containerEnv.put(DtYarnConstants.Environment.XLEARNING_TF_INDEX.toString(), String.valueOf(index));
        ContainerLaunchContext ctx = ContainerLaunchContext.newInstance(
                containerLocalResource, containerEnv, containerLaunchcommands, null, null, null);

        try {
            LOG.info("nmAsync.class: " + nmAsync.getClass().getName());
            LOG.info("nmAsync.client: " + nmAsync.getClient());
            nmAsync.startContainerAsync(container, ctx);
        } catch (Exception e) {
            LOG.info("exception: " + DebugUtil.stackTrace(e));
            DebugUtil.pause();
        }

    }


    public static void main(String[] args) {
        ApplicationMaster appMaster;
        try {
            appMaster = new ApplicationMaster();
            appMaster.init();
            boolean tag;
            try {
                tag = appMaster.run();
            } catch(Throwable t) {
                tag = false;
                String stackTrace = DebugUtil.stackTrace(t);
                appMaster.unregister(FinalApplicationStatus.FAILED, stackTrace);
                LOG.error(stackTrace);
            }

            if (tag) {
                LOG.info("Application completed successfully.");
                System.exit(0);
            } else {
                LOG.info("Application failed.");
                System.exit(1);
            }
        } catch (Exception e) {
            LOG.fatal("Error running ApplicationMaster", e);
            System.exit(1);
        }
    }
}
