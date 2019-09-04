package com.dtstack.rdos.engine.execution.base;

import com.dtstack.rdos.commom.exception.ClientAccessException;
import com.dtstack.rdos.common.config.ConfigParse;
import com.dtstack.rdos.common.util.MD5Util;
import com.dtstack.rdos.common.util.MathUtil;
import com.dtstack.rdos.common.util.PublicUtil;
import com.dtstack.rdos.engine.execution.base.enums.EngineType;
import com.dtstack.rdos.engine.execution.base.loader.DtClassLoader;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 插件客户端
 * TODO 所有存储的client都是clientproxy
 * Date: 2018/2/5
 * Company: www.dtstack.com
 * @author xuchao
 */

public class ClientCache {
    
    private static final Logger LOG = LoggerFactory.getLogger(ClientCache.class);

    private static final String MD5_SUM_KEY = "md5sum";
    private static final String MD5_ZIP_KEY = "md5zip";

    private static String userDir = System.getProperty("user.dir");

    private Map<String, IClient> defaultClientMap = Maps.newConcurrentMap();

    private Map<String, Map<String, IClient>> cache = Maps.newConcurrentMap();

    private static ClientCache singleton = new ClientCache();

    private ClientCache(){}

    public static ClientCache getInstance(){
        return singleton;
    }

    public void initLocalPlugin(List<Map<String, Object>> clientParamsList) throws Exception {
        for(Map<String, Object> params : clientParamsList){
            String clientTypeStr = (String) params.get(ConfigParse.TYPE_NAME_KEY);
            if(clientTypeStr == null){
                throw new Exception("node.yml of engineTypes setting error, typeName must not be null!!!");
            }

            loadComputerPlugin(clientTypeStr);
            IClient client = ClientFactory.createPluginClass(clientTypeStr);

            Properties clusterProp = new Properties();
            clusterProp.putAll(params);
            String paramsStr = PublicUtil.objToString(params);
            String pluginInfoMd5 = MD5Util.getMD5String(paramsStr);
            clusterProp.put(MD5_SUM_KEY, pluginInfoMd5);
            client.init(clusterProp);

            String key = EngineType.getEngineTypeWithoutVersion(clientTypeStr);
            addDefaultClient(key, client, pluginInfoMd5);
        }

        LOG.warn("init local plugin success,{}", defaultClientMap.toString());
    }

    /**
     * pluginKey是不带版本信息的
     * @param pluginKey
     * @param pluginInfo
     * @return
     */
    public IClient getClient(String pluginKey, String pluginInfo) throws ClientAccessException {
        try {
            if(Strings.isNullOrEmpty(pluginInfo)){
                return getDefaultPlugin(pluginKey);
            }

            Map<String, IClient> clientMap = cache.computeIfAbsent(pluginKey, k -> Maps.newConcurrentMap());

            Properties properties = PublicUtil.jsonStrToObject(pluginInfo, Properties.class);

            String md5plugin = MD5Util.getMD5String(pluginInfo);
            String md5sum = null;
            if(!properties.containsKey(MD5_SUM_KEY) || (md5sum = MathUtil.getString(properties.get(MD5_SUM_KEY))) == null){
                String md5zip = MathUtil.getString(properties.get(MD5_ZIP_KEY));
                if (md5zip == null) {
                    md5zip = "";
                }
                md5sum = md5zip + md5plugin;
                properties.setProperty(MD5_SUM_KEY, md5sum);
            }

            IClient client = clientMap.get(md5sum);
            if(client == null){
                synchronized (clientMap) {
                    client = clientMap.get(md5sum);
                    if (client == null){
                        client = buildPluginClient(pluginInfo);
                        client.init(properties);
                        clientMap.putIfAbsent(md5sum, client);
                    }
                }
            }

            return client;
        } catch (Throwable e) {
            throw new ClientAccessException(e);
        }
    }

    private IClient getDefaultPlugin(String pluginKey){
        IClient defaultClient = defaultClientMap.get(pluginKey);
        if(defaultClient == null){
            LOG.error("-------can't find plugin by key:{}", pluginKey);
            return null;
        }

        return defaultClient;
    }

    public IClient buildPluginClient(String pluginInfo) throws Exception {

        Map<String, Object> params = PublicUtil.jsonStrToObject(pluginInfo, Map.class);
        String clientTypeStr = MathUtil.getString(params.get(ConfigParse.TYPE_NAME_KEY));
        loadComputerPlugin(clientTypeStr);
        return ClientFactory.createPluginClass(clientTypeStr);
    }

    private void loadComputerPlugin(String pluginType) throws Exception{

        if(ClientFactory.checkContainClassLoader(pluginType)){
            return;
        }

        String plugin = String.format("%s/plugin/%s", userDir, pluginType);
        File finput = new File(plugin);
        if(!finput.exists()){
            throw new Exception(String.format("%s directory not found",plugin));
        }

        ClientFactory.addClassLoader(pluginType, getClassLoad(finput));
    }

    private URLClassLoader getClassLoad(File dir) throws IOException {
        File[] files = dir.listFiles();
        List<URL> urlList = new ArrayList<>();
        int index = 0;
        if (files!=null && files.length>0){
            for(File f : files){
                String jarName = f.getName();
                if(f.isFile() && jarName.endsWith(".jar")){
                    urlList.add(f.toURI().toURL());
                    index = index+1;
                }
            }
        }
        URL[] urls = urlList.toArray(new URL[urlList.size()]);
        return new DtClassLoader(urls, this.getClass().getClassLoader());
    }

    private void addDefaultClient(String pluginKey, IClient client, String pluginInfoMd5){

        if(defaultClientMap.get(pluginKey) != null){
            LOG.error("------setting error: conflict default plugin key:{}-----", pluginKey);
        }

        defaultClientMap.putIfAbsent(pluginKey, client);

        Map<String, IClient> clientMap = cache.computeIfAbsent(pluginKey, key -> Maps.newConcurrentMap());
        clientMap.put(pluginInfoMd5, client);
    }
}
