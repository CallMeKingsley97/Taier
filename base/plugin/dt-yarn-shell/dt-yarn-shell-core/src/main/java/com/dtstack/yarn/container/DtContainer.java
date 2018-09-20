package com.dtstack.yarn.container;

import com.dtstack.yarn.DtYarnConfiguration;
import com.dtstack.yarn.api.ApplicationContainerProtocol;
import com.dtstack.yarn.api.DtYarnConstants;
import com.dtstack.yarn.common.DtContainerStatus;
import com.dtstack.yarn.common.LocalRemotePath;
import com.dtstack.yarn.util.Utilities;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.util.ConverterUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class DtContainer {

    private static final Log LOG = LogFactory.getLog(DtContainer.class);

    private DtYarnConfiguration conf;

    private ApplicationContainerProtocol amClient;

    private DtContainerId containerId;

    private Map<String, String> envs;

    private String role;

    private ContainerStatusNotifier containerStatusNotifier;

    private ProcessLogCollector processLogCollector;

    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();


    private DtContainer() {
        this.conf = new DtYarnConfiguration();
        conf.addResource(new Path(DtYarnConstants.LEARNING_JOB_CONFIGURATION));
        LOG.info("user is " + conf.get("hadoop.job.ugi"));
        containerId = new DtContainerId(ConverterUtils.toContainerId(System
                .getenv(ApplicationConstants.Environment.CONTAINER_ID.name())));
        LOG.info("sub container id: " + containerId);
        this.envs = System.getenv();
        this.role = envs.get(DtYarnConstants.Environment.XLEARNING_TF_ROLE.toString());
    }

    private void init() {
        LOG.info("DtContainer initializing");
        String appMasterHost = System.getenv(DtYarnConstants.Environment.APPMASTER_HOST.toString());
        int appMasterPort = Integer.valueOf(System.getenv(DtYarnConstants.Environment.APPMASTER_PORT.toString()));
        InetSocketAddress addr = new InetSocketAddress(appMasterHost, appMasterPort);
        try {
            amClient = RPC.getProxy(ApplicationContainerProtocol.class,
                    ApplicationContainerProtocol.versionID, addr, conf);
        } catch (IOException e) {
            LOG.error("Connecting to ApplicationMaster " + appMasterHost + ":" + appMasterPort + " failed!");
            LOG.error("Container will suicide!");
            System.exit(1);
        }

        containerStatusNotifier = new ContainerStatusNotifier(amClient, conf, containerId);
        containerStatusNotifier.reportContainerStatusNow(DtContainerStatus.INITIALIZING);
        containerStatusNotifier.start();

    }

    public Configuration getConf() {
        return this.conf;
    }

    public ApplicationContainerProtocol getAmClient() {
        return this.amClient;
    }

    private DtContainerId getContainerId() {
        return this.containerId;
    }

    private Boolean run() throws IOException, InterruptedException {

        downloadInputFiles();

        Date now = new Date();
        containerStatusNotifier.setContainersStartTime(now.toString());
        containerStatusNotifier.reportContainerStatusNow(DtContainerStatus.RUNNING);

        List<String> envList = new ArrayList<>(20);
        envList.add("CLASSPATH=" + "./:" + System.getenv("CLASSPATH"));
        envList.add("LANG=zh_CN.UTF-8");
        String[] env = envList.toArray(new String[envList.size()]);
        String command = envs.get(DtYarnConstants.Environment.DT_EXEC_CMD.toString());

        LOG.info("Executing command:" + command);
        Runtime rt = Runtime.getRuntime();
        Process process = rt.exec(command, env);
        processLogCollector = new ProcessLogCollector(process);
        processLogCollector.start();

        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    uploadOutputFiles();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, 1000, 3000, TimeUnit.MILLISECONDS);

        process.waitFor();

        return process.exitValue() == 0;

    }


    private void reportFailedAndExit() {
        Date now = new Date();
        containerStatusNotifier.setContainersFinishTime(now.toString());
        containerStatusNotifier.reportContainerStatusNow(DtContainerStatus.FAILED);

        if(processLogCollector != null) {
            processLogCollector.stop();
        }

        Utilities.sleep(3000);

        System.exit(-1);
    }

    private void reportSucceededAndExit() {
        Date now = new Date();
        containerStatusNotifier.setContainersFinishTime(now.toString());
        containerStatusNotifier.reportContainerStatusNow(DtContainerStatus.SUCCEEDED);

        if(processLogCollector != null) {
            processLogCollector.stop();
        }

        Utilities.sleep(3000);

        System.exit(0);
    }

    public void downloadInputFiles() throws IOException {
        List<LocalRemotePath> inputs = Arrays.asList(amClient.getInputSplit(containerId));
        if(inputs != null) {
            for(LocalRemotePath input : inputs) {
                LOG.info("my input: " + input.getDfsLocation() + "##" + input.getLocalLocation());
            }
        }
    }

    public void uploadOutputFiles() throws IOException {
        List<LocalRemotePath> outputs = Arrays.asList(amClient.getOutputLocation());
        for (LocalRemotePath s : outputs) {
            LOG.info("Output path: " + s.getLocalLocation() + ":" + s.getDfsLocation());
        }

        if (outputs.size() > 0) {
            for (LocalRemotePath outputInfo : outputs) {
                FileSystem localFs = FileSystem.getLocal(conf);
                Path localPath = new Path(outputInfo.getLocalLocation());
                Path remotePath = new Path(outputInfo.getDfsLocation());
                FileSystem dfs = remotePath.getFileSystem(conf);
                if (dfs.exists(remotePath)) {
                    LOG.info("Container remote output path " + remotePath + "exists, so we has to delete is first.");
                    dfs.delete(remotePath);
                }
                if (localFs.exists(localPath)) {
                    dfs.copyFromLocalFile(false, false, localPath, remotePath);
                    LOG.info("Upload output " + localPath + " to remote path " + remotePath + " finished.");
                }
                localFs.close();
            }
        }

    }



    public static void main(String[] args) {
        DtContainer container = new DtContainer();
        try {
            container.init();
            if (container.run()) {
                LOG.info("DtContainer " + container.getContainerId().toString() + " finish successfully");
                container.reportSucceededAndExit();
            } else {
                LOG.error("DtContainer run failed!");
                container.reportFailedAndExit();
            }
        } catch (Exception e) {
            LOG.error("Some errors has occurred during container running!", e);
            container.reportFailedAndExit();
        }
        Utilities.sleep(3000);
    }


}
