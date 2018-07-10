package com.dtstack.rdos.engine.execution.flink150;

import com.dtstack.rdos.engine.execution.base.JarFileInfo;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 数据同步插件
 * Date: 2018/5/3
 * Company: www.dtstack.com
 * @author xuchao
 */

public class SyncPluginInfo {

    private static final Logger LOG = LoggerFactory.getLogger(SyncPluginInfo.class);

    private static final String fileSP = File.separator;

    private static final String syncPluginDirName = "syncplugin";

    /**插件jar名称*/
    private static final String syncJarFileName = "flinkx.jar";

    //同步模块在flink集群加载插件
    private String flinkRemoteSyncPluginRoot;

    private String localSyncFileDir;

    //同步模块的monitorAddress, 用于获取错误记录数等信息
    private String monitorAddress;

    private SyncPluginInfo(){
    }

    public static SyncPluginInfo create(FlinkConfig flinkConfig){
        SyncPluginInfo syncPluginInfo = new SyncPluginInfo();
        syncPluginInfo.init(flinkConfig);
        return syncPluginInfo;
    }

    public void init(FlinkConfig flinkConfig){
        this.flinkRemoteSyncPluginRoot = getSyncPluginDir(flinkConfig.getRemotePluginRootDir());
        this.localSyncFileDir = getSyncPluginDir(flinkConfig.getFlinkPluginRoot());
        this.monitorAddress = flinkConfig.getMonitorAddress();
    }

    public List<URL> getClassPaths(List<String> programArgList){
        return flinkRemoteSyncPluginRoot != null ?
                getUserClassPath(programArgList, flinkRemoteSyncPluginRoot) : new ArrayList<>();
    }

    public List<String> createSyncPluginArgs(JobClient jobClient, FlinkClient flinkClient){

        String args = jobClient.getClassArgs();
        List<String> programArgList = Lists.newArrayList();
        if(StringUtils.isNotBlank(args)){
            programArgList.addAll(Arrays.asList(args.split("\\s+")));
        }

        programArgList.add("-monitor");
        if(StringUtils.isNotEmpty(monitorAddress)) {
            programArgList.add(monitorAddress);
        } else {
            programArgList.add(flinkClient.getReqUrl());
        }

        return programArgList;
    }

    public JarFileInfo createAddJarInfo(){
        JarFileInfo jarFileInfo = new JarFileInfo();
        String jarFilePath  = localSyncFileDir + fileSP + syncJarFileName;
        jarFileInfo.setJarPath(jarFilePath);
        return jarFileInfo;
    }

    public String getSyncPluginDir(String pluginRoot){
        return pluginRoot + fileSP + syncPluginDirName;
    }

    // 数据同步专用: 获取flink端插件classpath, 在programArgsList中添加engine端plugin根目录
    private List<URL> getUserClassPath(List<String> programArgList, String flinkSyncPluginRoot) {
        List<URL> urlList = new ArrayList<>();
        if(programArgList == null || flinkSyncPluginRoot == null)
            return urlList;

        int i = 0;
        for(; i < programArgList.size() - 1; ++i)
            if(programArgList.get(i).equals("-job") || programArgList.get(i).equals("--job"))
                break;

        if(i == programArgList.size() - 1)
            return urlList;

        programArgList.add("-pluginRoot");
        programArgList.add(localSyncFileDir);

        String job = programArgList.get(i + 1);

        try {
            job = java.net.URLDecoder.decode(job, "UTF-8");
            programArgList.set(i + 1, job);
            Gson gson = new Gson();
            Map<String, Object> map = gson.fromJson(job, Map.class);
            LinkedTreeMap jobMap = (LinkedTreeMap) map.get("job");

            List<LinkedTreeMap> contentList = (List<LinkedTreeMap>) jobMap.get("content");
            LinkedTreeMap content = contentList.get(0);
            LinkedTreeMap reader = (LinkedTreeMap) content.get("reader");
            String readerName = (String) reader.get("name");
            LinkedTreeMap writer = (LinkedTreeMap) content.get("writer");
            String writerName = (String) writer.get("name");

            Preconditions.checkArgument(StringUtils.isNotEmpty(readerName), "reader name should not be empty");
            Preconditions.checkArgument(StringUtils.isNotEmpty(writerName), "writer ame should not be empty");

            File commonDir = new File(flinkSyncPluginRoot + fileSP + "common");
            File readerDir = new File(flinkSyncPluginRoot + fileSP + readerName);
            File writerDir = new File(flinkSyncPluginRoot + fileSP + writerName);

            urlList.addAll(findJarsInDir(commonDir));
            urlList.addAll(findJarsInDir(readerDir));
            urlList.addAll(findJarsInDir(writerDir));

        } catch (Exception e) {
            LOG.error("", e);
        } finally {
            return urlList;
        }
    }

    private static List<URL> findJarsInDir(File dir)  throws MalformedURLException {
        List<URL> urlList = new ArrayList<>();

        if(dir.exists() && dir.isDirectory()) {
            File[] jarFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".jar");
                }
            });

            for(File jarFile : jarFiles) {
                urlList.add(jarFile.toURI().toURL());
            }

        }

        return urlList;
    }
}
