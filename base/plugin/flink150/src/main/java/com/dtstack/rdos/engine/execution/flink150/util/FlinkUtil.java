package com.dtstack.rdos.engine.execution.flink150.util;

import com.dtstack.rdos.engine.execution.base.enums.ClassLoaderType;
import com.dtstack.rdos.engine.execution.base.enums.ComputeType;
import com.dtstack.rdos.engine.execution.base.enums.EJobType;
import com.dtstack.rdos.engine.execution.flink150.FlinkClientBuilder;
import com.dtstack.rdos.engine.execution.flink150.FlinkClusterClientManager;
import com.dtstack.rdos.engine.execution.flink150.constrant.ConfigConstrant;
import com.dtstack.rdos.engine.execution.flink150.enums.FlinkYarnMode;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.client.program.ProgramInvocationException;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.yarn.YarnClusterClient;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.List;
import java.util.Properties;

/**
 * Reason:
 * Date: 2017/2/21
 * Company: www.dtstack.com
 * @author xuchao
 */

public class FlinkUtil {

    private static final Logger logger = LoggerFactory.getLogger(FlinkUtil.class);

    private static final String URL_SPLITE = "/";

    private static String fileSP = File.separator;

    //http://${addr}/proxy/${applicationId}/
    private static final String FLINK_URL_FORMAT = "http://%s/proxy/%s/";

    private static final String YARN_RM_WEB_KEY_PREFIX = "yarn.resourcemanager.webapp.address.";



    public static PackagedProgram buildProgram(String fromPath, String toPath, List<URL> classpaths, EJobType jobType,
                                               String entryPointClass, String[] programArgs,
                                               SavepointRestoreSettings spSetting, Configuration hadoopConf)
            throws FileNotFoundException, ProgramInvocationException {
        if (fromPath == null) {
            throw new IllegalArgumentException("The program JAR file was not specified.");
        }

        File jarFile = downloadJar(fromPath, toPath, hadoopConf);

        ClassLoaderType classLoaderType = ClassLoaderType.getClassLoaderType(jobType);

        // Get assembler class
        PackagedProgram program = entryPointClass == null ?
                new PackagedProgram(jarFile, classpaths, classLoaderType, programArgs) :
                new PackagedProgram(jarFile, classpaths, classLoaderType, entryPointClass, programArgs);

        program.setSavepointRestoreSettings(spSetting);

        return program;
    }

    public static String getTmpFileName(String fileUrl, String toPath){
        String name = fileUrl.substring(fileUrl.lastIndexOf(URL_SPLITE) + 1);
        String tmpFileName = toPath  + fileSP + name;
        return tmpFileName;
    }

    public static File downloadJar(String fromPath, String toPath, Configuration hadoopConf) throws FileNotFoundException {
        String localJarPath = FlinkUtil.getTmpFileName(fromPath, toPath);
        if(!FileUtil.downLoadFile(fromPath, localJarPath, hadoopConf)){
            //如果不是http 或者 hdfs协议的从本地读取
            File localFile = new File(fromPath);
            if(localFile.exists()){
                return localFile;
            }
            return null;
        }

        File jarFile = new File(localJarPath);

        // Check if JAR file exists
        if (!jarFile.exists()) {
            throw new FileNotFoundException("JAR file does not exist: " + jarFile);
        } else if (!jarFile.isFile()) {
            throw new FileNotFoundException("JAR file is not a file: " + jarFile);
        }

        return jarFile;
    }


    /**
     *
     * FIXME 仅针对sql执行方式,暂时未找到区分设置source,transform,sink 并行度的方式
     * 设置job运行的并行度
     * @param properties
     */
    public static int getEnvParallelism(Properties properties){
        String parallelismStr = properties.getProperty(ConfigConstrant.SQL_ENV_PARALLELISM);
        return StringUtils.isNotBlank(parallelismStr)?Integer.parseInt(parallelismStr):1;
    }


    /**
     * 针对MR类型整个job的并发度设置
     * @param properties
     * @return
     */
    public static int getJobParallelism(Properties properties){
        String parallelismStr = properties.getProperty(ConfigConstrant.MR_JOB_PARALLELISM);
        return StringUtils.isNotBlank(parallelismStr)?Integer.parseInt(parallelismStr):1;
    }

    /**
     * get task run mode
     * @param properties
     * @return
     */
    public static FlinkYarnMode getTaskRunMode(Properties properties, ComputeType computeType){
        String modeStr = properties.getProperty(ConfigConstrant.FLINK_TASK_RUN_MODE_KEY);
        if (StringUtils.isEmpty(modeStr)){
            if(ComputeType.STREAM == computeType){
                return FlinkYarnMode.PER_JOB;
            } else {
                return FlinkYarnMode.NEW;
            }
        }
        return FlinkYarnMode.mode(modeStr);
    }


    public static String getReqUrl(FlinkClusterClientManager flinkClusterClientManager){
        ClusterClient<ApplicationId> clusterClient = flinkClusterClientManager.getClusterClient();
        boolean isYarnClusterClient = clusterClient instanceof YarnClusterClient;
        if(!isYarnClusterClient){
            return clusterClient.getWebInterfaceURL();
        }

        try{
            return getLegacyReqUrl(flinkClusterClientManager);
        }catch (Exception e){
            logger.error("", e);
            return clusterClient.getWebInterfaceURL();
        }
    }

    public static String getLegacyReqUrl(FlinkClusterClientManager flinkClusterClientManager){
        String url = "";
        try{
            FlinkClientBuilder flinkClientBuilder = flinkClusterClientManager.getFlinkClientBuilder();
            YarnClient yarnClient = flinkClientBuilder.getYarnClient();
            YarnConfiguration yarnConf = flinkClientBuilder.getYarnConf();
            Field rmClientField = yarnClient.getClass().getDeclaredField("rmClient");
            rmClientField.setAccessible(true);
            Object rmClient = rmClientField.get(yarnClient);

            Field hField = rmClient.getClass().getSuperclass().getDeclaredField("h");
            hField.setAccessible(true);
            //获取指定对象中此字段的值
            Object h = hField.get(rmClient);

            Field currentProxyField = h.getClass().getDeclaredField("currentProxy");
            currentProxyField.setAccessible(true);
            Object currentProxy = currentProxyField.get(h);

            Field proxyInfoField = currentProxy.getClass().getDeclaredField("proxyInfo");
            proxyInfoField.setAccessible(true);
            String proxyInfoKey = (String) proxyInfoField.get(currentProxy);

            String key = YARN_RM_WEB_KEY_PREFIX + proxyInfoKey;
            String addr = yarnConf.get(key);

            if(addr == null) {
                addr = yarnConf.get("yarn.resourcemanager.webapp.address");
            }


            ApplicationId appId = (ApplicationId) flinkClusterClientManager.getClusterClient().getClusterId();
            YarnApplicationState yarnApplicationState = yarnClient.getApplicationReport(appId).getYarnApplicationState();
            if (YarnApplicationState.RUNNING != yarnApplicationState){
                logger.error("curr flink application {} state is not running!", appId);
            }

            url = String.format(FLINK_URL_FORMAT, addr, appId);
        }catch (Exception e){
            logger.error("Getting URL failed" + e);
        }

        logger.info("get req url=" + url);
        return url;
    }

}