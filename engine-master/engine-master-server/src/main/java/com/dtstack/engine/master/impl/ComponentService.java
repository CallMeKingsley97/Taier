package com.dtstack.engine.master.impl;

import com.alibaba.fastjson.JSONObject;
import com.dtstack.engine.api.annotation.Param;
import com.dtstack.engine.api.domain.Queue;
import com.dtstack.engine.api.domain.*;
import com.dtstack.engine.api.dto.ClusterDTO;
import com.dtstack.engine.api.dto.ComponentDTO;
import com.dtstack.engine.api.dto.Resource;
import com.dtstack.engine.api.vo.ClusterVO;
import com.dtstack.engine.api.vo.EngineTenantVO;
import com.dtstack.engine.api.vo.TemplateVo;
import com.dtstack.engine.api.vo.TestConnectionVO;
import com.dtstack.engine.common.annotation.Forbidden;
import com.dtstack.engine.common.exception.EngineAssert;
import com.dtstack.engine.common.exception.ErrorCode;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.common.util.SFTPHandler;
import com.dtstack.engine.dao.*;
import com.dtstack.engine.master.component.ComponentFactory;
import com.dtstack.engine.master.component.ComponentImpl;
import com.dtstack.engine.master.component.SparkComponent;
import com.dtstack.engine.master.component.YARNComponent;
import com.dtstack.engine.master.download.IDownload;
import com.dtstack.engine.master.download.TemplateFileDownload;
import com.dtstack.engine.master.enums.*;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.dtstack.engine.master.router.cache.ConsoleCache;
import com.dtstack.engine.master.utils.EngineUtil;
import com.dtstack.engine.master.utils.HadoopConfTool;
import com.dtstack.engine.master.utils.XmlFileUtil;
import com.dtstack.schedule.common.enums.AppType;
import com.dtstack.schedule.common.enums.Deleted;
import com.dtstack.schedule.common.enums.SftpAuthType;
import com.dtstack.schedule.common.kerberos.KerberosConfigVerify;
import com.dtstack.schedule.common.util.Xml2JsonUtil;
import com.dtstack.schedule.common.util.ZipUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kerby.kerberos.kerb.keytab.Keytab;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ComponentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComponentService.class);

    private final static String ZIP_CONTENT_TYPE = "zip";

    private static String unzipLocation = System.getProperty("user.dir") + File.separator + "unzip";

    private static String downloadLocation = System.getProperty("user.dir") + File.separator + "download";

    private static final String KERBEROS_FILE_SUF = "%sKerberosFile";

    public static final String KERBEROS_PATH = "kerberos";

    private static final String OPEN_KERBEROS = "openKerberos";

    private static final String KERBEROS_CONFIG = "kerberosConfig";

    public static final String SFTP_HADOOP_CONFIG_PATH = "%s%s/%s";

    private static final String SEPARATE = "/";

    @Autowired
    private ComponentDao componentDao;

    @Autowired
    private ClusterDao clusterDao;

    @Autowired
    private EngineDao engineDao;

    @Autowired
    private QueueDao queueDao;

    @Autowired
    private QueueService queueService;

    @Autowired
    private EngineService engineService;

    @Autowired
    private EngineTenantDao engineTenantDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private ConsoleCache consoleCache;

    @Autowired
    private EnvironmentContext env;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private KerberosDao kerberosDao;

    public static final String TYPE_NAME = "typeName";

    /**
     * 组件配置文件映射
     */
    public static Map<Integer,List<String>> componentTypeConfigMapping = new HashMap<>();
    static {
        //hdfs core 需要合并
        componentTypeConfigMapping.put(EComponentType.HDFS.getTypeCode(),Lists.newArrayList("hdfs-site.xml","core-site.xml"));
        componentTypeConfigMapping.put(EComponentType.YARN.getTypeCode(),Lists.newArrayList("yarn-site.xml"));
    }

    /**
     * {
     *     "1":{
     *         "xx":"xx"
     *     }
     * }
     */
    public String listConfigOfComponents(@Param("tenantId") Long dtUicTenantId, @Param("engineType") Integer engineType){
        JSONObject result = new JSONObject();
        Long tenantId = tenantDao.getIdByDtUicTenantId(dtUicTenantId);
        if (tenantId == null){
            return result.toJSONString();
        }

        List<Long> engineIds = engineTenantDao.listEngineIdByTenantId(tenantId);
        if(CollectionUtils.isEmpty(engineIds)){
            return result.toJSONString();
        }

        List<Engine> engines = engineDao.listByEngineIds(engineIds);
        if(CollectionUtils.isEmpty(engines)){
            return result.toJSONString();
        }

        Engine targetEngine = null;
        for (Engine engine : engines) {
            if(engine.getEngineType() == engineType){
                targetEngine = engine;
                break;
            }
        }

        if(targetEngine == null){
            return result.toJSONString();
        }

        List<Component> componentList = componentDao.listByEngineId(targetEngine.getId());
        for (Component component : componentList) {
            result.put(String.valueOf(component.getComponentTypeCode()), JSONObject.parseObject(component.getComponentConfig()));
        }

        return result.toJSONString();
    }

    public Component getOne(@Param("id") Long id) {
        Component component = componentDao.getOne(id);
        if (component == null) {
            throw new RdosDefineException("组件不存在");
        }
        return component;
    }

    public String getSftpClusterKey(Long clusterId) {
        return AppType.CONSOLE.name() + "_" + clusterId;
    }

    private Map<String, String> parseKerberosConfig(Resource resource, String localKerberosConf) throws Exception {
        Map<String, Map<String, String>> confMap = KerberosConfigVerify.parseKerberosFromUpload(resource.getUploadedFileName(), localKerberosConf);
        if (MapUtils.isNotEmpty(confMap)) {
            Map<String, String> map = new HashMap<>();
            for (String key : confMap.keySet()) {
                map.putAll(confMap.get(key));
            }
            return map;
        }
        throw new RdosDefineException("缺少xml配置文件");
    }


    private void addDefaultProperties(EComponentType componentType, Map<String, Object> config) {
        if (EComponentType.SFTP.equals(componentType)) {
            String authType = MapUtils.getString(config, SFTPHandler.KEY_AUTHENTICATION);
            String rsaPath = MapUtils.getString(config, SFTPHandler.KEY_RSA);
            String username = MapUtils.getString(config, SFTPHandler.KEY_USERNAME);
            if (SftpAuthType.RSA.getType().toString().equals(authType) && StringUtils.isBlank(rsaPath) && StringUtils.isNotBlank(username)) {
                rsaPath = String.format(SFTPHandler.DEFAULT_RSA_PATH_TEMPLATE, username);
                config.put(SFTPHandler.KEY_RSA, rsaPath);
            }
        }
    }


    public Map<String, Object> fillKerberosConfig(String allConfString, Long clusterId) {
        JSONObject allConf = JSONObject.parseObject(allConfString);
        allConf.putAll(KerberosConfigVerify.replaceFilePath(allConf, getClusterLocalKerberosDir(clusterId)));
        JSONObject kerberosConfig = allConf.getJSONObject(KERBEROS_CONFIG);
        if (kerberosConfig != null) {
            allConf.put(KERBEROS_CONFIG, KerberosConfigVerify.replaceFilePath(kerberosConfig, getClusterLocalKerberosDir(clusterId)));
        }
        return allConf;
    }

    private void addClusterKerberosConfig(Map<String, Object> config, Long engineId, int componentType) {
        Engine engine = engineService.getOne(engineId);
        KerberosConfig kerberosConfig = kerberosDao.getByClusterId(engine.getClusterId());
        if (Objects.nonNull(kerberosConfig)) {
            Map<String, String> sftpConfig = getSFTPConfig(engine.getClusterId());
            try {
                String clusterLocalKerberosDir = getClusterLocalKerberosDir(engine.getClusterId());
                KerberosConfigVerify.downloadKerberosFromSftp(getSftpClusterKey(engine.getClusterId()), getClusterLocalKerberosDir(engine.getClusterId()), sftpConfig);
                config.putIfAbsent(KerberosKey.KEYTAB.getKey(), clusterLocalKerberosDir + SEPARATE + kerberosConfig.getName());
            } catch (SftpException e) {
                LOGGER.error("downloadKerberosFromSftp error {}", e);
            }
            config.putIfAbsent(KerberosKey.PRINCIPAL.getKey(), kerberosConfig.getPrincipal());
            if (!Objects.equals(EComponentType.HDFS.getTypeCode(), componentType)) {
                Component hdfsComponent = componentDao.getByEngineIdAndComponentType(engineId, EComponentType.HDFS.getTypeCode());
                if (Objects.isNull(hdfsComponent)) {
                    throw new RdosDefineException("开启kerberos后需要预先保存hdfs组件");
                }
                config.putIfAbsent(KerberosKey.HDFS_CONFIG.getKey(), hdfsComponent.getComponentConfig());
                //转换为map
                config.putAll(JSONObject.parseObject(hdfsComponent.getComponentConfig(),Map.class));
            }
        }
    }

    /**
     * 更新缓存
     */
    public void updateCache(Long engineId, Integer componentCode) {
        Set<Long> dtUicTenantIds = new HashSet<>();
        if (Objects.nonNull(componentCode) &&(
                EComponentType.TIDB_SQL.getTypeCode() == componentCode ||
                        EComponentType.LIBRA_SQL.getTypeCode() == componentCode)) {

            //tidb 和libra 没有queue
            List<EngineTenantVO> tenantVOS = engineTenantDao.listEngineTenant(engineId);
            if(CollectionUtils.isNotEmpty(tenantVOS)){
                for (EngineTenantVO tenantVO : tenantVOS) {
                    if(Objects.nonNull(tenantVO) && Objects.nonNull(tenantVO.getTenantId())){
                        dtUicTenantIds.add(tenantVO.getTenantId());
                    }
                }
            }
        } else {
            List<Queue> refreshQueues = queueDao.listByEngineId(engineId);
            if (CollectionUtils.isEmpty(refreshQueues)) {
                return;
            }

            List<Long> queueIds = refreshQueues.stream().map(BaseEntity::getId).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(queueIds)) {
                return;
            }
            List<Long> tenantIds = engineTenantDao.listTenantIdByQueueIds(queueIds);
            dtUicTenantIds = new HashSet<>(tenantDao.listDtUicTenantIdByIds(tenantIds));
        }
        //缓存刷新
        if (!dtUicTenantIds.isEmpty()) {
            for (Long uicTenantId : dtUicTenantIds) {
                consoleCache.publishRemoveMessage(uicTenantId.toString());
            }
        }
    }

    public List<Component> listComponent(Long engineId){
        return componentDao.listByEngineId(engineId);
    }

    public TestConnectionVO testConnections(@Param("componentConfigs") String componentConfigs, @Param("clusterId") Long clusterId, @Param("resources") List<Resource> resources) throws Exception {
        Map<String, Resource> resourceMap = convertToMap(resources);
        JSONObject configs = JSONObject.parseObject(componentConfigs);
        if(configs == null || configs.isEmpty()){
            return TestConnectionVO.EMPTY_RESULT;
        }

        ClusterResourceDescription description = null;
        List<TestConnectionVO.ComponentTestResult> testResults = new ArrayList<>();
        KerberosConfig kerberosOpen = kerberosDao.getByClusterId(clusterId);
        Engine hadoopEngine = engineDao.getByClusterIdAndEngineType(clusterId, MultiEngineType.HADOOP.getType());
        //开启kerberosOpen
        boolean isOpenKerberos = Objects.nonNull(kerberosOpen) && kerberosOpen.getOpenKerberos() == 1;
        for (String key : configs.keySet()) {
            EComponentType type;
            try {
                type = EComponentType.getByConfName(key);
            } catch (Exception e){
                continue;
            }

            Map configMap = configs.getObject(key, Map.class);
            if(isOpenKerberos && Objects.nonNull(hadoopEngine)){
                //如果开启kerberos 添加keytab路径
                addClusterKerberosConfig(configMap,hadoopEngine.getId(),type.getTypeCode());
            }
            setKerberosConfig(clusterId, configMap, resourceMap, key);
            Map<String, Object> kerberosConfig = fillKerberosConfig(JSONObject.toJSONString(configMap), clusterId);
            ComponentImpl component = ComponentFactory.getComponent(kerberosConfig, type);

            TestConnectionVO.ComponentTestResult result = new TestConnectionVO.ComponentTestResult();
            result.setComponentTypeCode(type.getTypeCode());
            try {
                component.checkConfig();
                if(EComponentType.YARN == type){
                    ((YARNComponent)component).initClusterResource(true);
                    description = ((YARNComponent)component).getResourceDescription();
                } else {
                    component.testConnection();
                }

                result.setResult(true);
            } catch (Exception e){
                result.setResult(false);
                result.setErrorMsg(e.getMessage());
            }

            testResults.add(result);
        }

        TestConnectionVO vo = new TestConnectionVO();
        vo.setTestResults(testResults);
        vo.setDescription(description);
        return vo;
    }

    private void setKerberosConfig(Long clusterId, Map configMap, Map<String, Resource> resourceMap, String key) throws Exception {
        EComponentType type = EComponentType.getByConfName(key);
        Component component = componentDao.getByClusterIdAndComponentType(clusterId, type.getTypeCode());
        if (component == null) {
            return;
        }
        JSONObject config = JSONObject.parseObject(component.getComponentConfig());

        String clusterKey = getSftpClusterKey(clusterId);
        String localKerberosConf = env.getLocalKerberosDir() + SEPARATE + clusterKey;
        Boolean openKerberos = MapUtils.getBoolean(configMap, OPEN_KERBEROS, false);
        if (EComponentType.HDFS.equals(type)) {
            if (MapUtils.getString(configMap, HadoopConfTool.DFS_NAMENODE_KERBEROS_PRINCIPAL) != null) {
                KerberosConfigVerify.downloadKerberosFromSftp(clusterKey, localKerberosConf, getSFTPConfig(clusterId));
                configMap.put(OPEN_KERBEROS, true);
            }
        } else if (openKerberos) {
            Resource resource = resourceMap.get(String.format(KERBEROS_FILE_SUF, key));
            if (resource == null) {
                JSONObject kerberosConfig = config.getJSONObject(KERBEROS_CONFIG);
                if (kerberosConfig == null) {
                    throw new RdosDefineException("kerberos配置错误");
                }
                KerberosConfigVerify.downloadKerberosFromSftp(clusterKey, localKerberosConf, getSFTPConfig(clusterId));
                configMap.put(KERBEROS_CONFIG, kerberosConfig);
            } else {
                //test路径
                Map<String, String> map = parseKerberosConfig(resource, localKerberosConf);
                configMap.put(KERBEROS_CONFIG, map);
            }
        }
    }

    private Map<String, Resource> convertToMap(List<Resource> resources) {
        Map<String, Resource> resourceMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(resources)) {
            for (Resource resource : resources) {
                resourceMap.put(resource.getKey(), resource);
            }
        }
        return resourceMap;
    }

    private Map<String, Object> parseAndUploadXmlFile(List<Resource> resources){
        Map<String, Object> confMap = new HashMap<>();
        String upzipLocation = null;
        List<File> xmlFiles;
        try {
            if (CollectionUtils.isEmpty(resources)) {
                throw new RdosDefineException("上传的文件不能为空");
            }

            Resource resource = resources.get(0);
            if (!resource.getFileName().contains(ZIP_CONTENT_TYPE)) {
                throw new RdosDefineException("压缩包格式仅支持ZIP格式");
            }

            //解压缩获得配置文件
            String xmlZipLocation = resource.getUploadedFileName();
            upzipLocation = unzipLocation + File.separator + resource.getFileName();
            try {
                xmlFiles = XmlFileUtil.getFilesFromZip(xmlZipLocation, upzipLocation,null);
            } catch (Exception e) {
                LOGGER.error("解压配置文件格式错误", e);
                throw new RdosDefineException("解压配置文件格式错误");
            }

            try {
                for (File file : xmlFiles) {
                    Map<String, Object> xmlMap = Xml2JsonUtil.xml2map(file);
                    confMap.put(file.getName(),xmlMap);
                }
            } catch (Exception e){
                throw new RdosDefineException("解析配置文件出错:" + e.getMessage());
            }
            return confMap;
        } catch (Exception e) {
            LOGGER.error("parseAndUploadXmlFile file error ", e);
            if (e instanceof RdosDefineException) {
                RdosDefineException rdosDefineException = (RdosDefineException) e;
                throw new RdosDefineException(rdosDefineException.getErrorMessage());
            }
            throw new RdosDefineException(ErrorCode.SERVER_EXCEPTION.getDescription());
        } finally {
            if (StringUtils.isNotBlank(upzipLocation)) {
                ZipUtil.deletefile(upzipLocation);
            }
        }
    }

    public String getHadoopConfigPath(Long clusterId) {
        Cluster cluster = clusterService.getOne(clusterId);
        Map<String, String> sftpConfig = getSFTPConfig(clusterId);
        String path = sftpConfig.get("path");
        if (StringUtils.isBlank(path)) {
            throw new RdosDefineException("sftp组件路径配置不能为空");
        }

        return String.format(SFTP_HADOOP_CONFIG_PATH, path, SFTPHandler.CONSOLE_HADOOP_CONFIG,  cluster.getClusterName());
    }

    public String getClusterLocalKerberosDir(Long clusterId) {
        return env.getLocalKerberosDir() + SEPARATE + getSftpClusterKey(clusterId);
    }

    @Forbidden
    public void addComponentWithConfig(Long engineId, String confName, JSONObject config, boolean updateQueue){
        EComponentType type = EComponentType.getByConfName(confName);

        if (Objects.isNull(config)) {
            config = new JSONObject();
        }
        Component component = componentDao.getByEngineIdAndComponentType(engineId, type.getTypeCode());
        if (component == null) {
            component = new Component();
            component.setEngineId(engineId);
            component.setComponentName(type.getName());
            component.setComponentTypeCode(type.getTypeCode());
            component.setComponentConfig(config.toJSONString());

            componentDao.insert(component);
        } else {
            component.setComponentConfig(config.toJSONString());
            componentDao.update(component);
        }

        if (EComponentType.YARN == type) {
            engineService.updateResource(engineId, config, updateQueue);
        }
    }

    /**
     * 更新集群的kerberos配置
     *
     * @param clusterId
     * @param resources
     */
    @Transactional(rollbackFor = Exception.class)
    public void hadoopKerberosConfig(@Param("clusterId") Long clusterId, @Param("resources") List<Resource> resources) {
        String localKerberosConf = getClusterLocalKerberosDir(clusterId);
        if (CollectionUtils.isNotEmpty(resources)) {
            //上传覆盖之前的文件
            deleteOldFile(localKerberosConf);
            Resource resource = resources.get(0);
            //1.解压keytab文件，可能包括krb5.conf文件
            unzipKeytab(localKerberosConf, resource);
            //2.解析获取principal
            File file = getKeyTabFile(localKerberosConf);
            if(Objects.isNull(file)){
                throw new RdosDefineException("文件缺失");
            }
            String principal = getPrincipal(file);

            //删除原来sftp下的keyTab文件
            deleteOldSftpFile(clusterId);
            //3.上传至sftp
            String remotePath = uploadSftpDir(clusterId, localKerberosConf);
            //4.更新db信息
            updateKerberosConfig(clusterId, resource, remotePath, principal,file.getName());
        } else {
            throw new RdosDefineException("文件缺失");
        }
    }

    private File getKeyTabFile(String dir) {
        File file = null;
        File dirFile = new File(dir);
        if (dirFile.exists() && dirFile.isDirectory()) {
            File[] files = dirFile.listFiles();
            if (files.length > 0) {
                file = Arrays.stream(files).filter(f -> f.getName().endsWith(".keytab")).findFirst().orElseThrow(() -> new RdosDefineException("压缩包中不包含keytab文件"));
            }
        }
        return file;
    }

    private String getPrincipal(File file) {
        if (Objects.nonNull(file)) {
            Keytab keytab = null;
            try {
                keytab = Keytab.loadKeytab(file);
            } catch (IOException e) {
                LOGGER.error("Keytab loadKeytab error ", e);
                throw new RdosDefineException("解析keytab文件失败");
            }
            List<PrincipalName> names = keytab.getPrincipals();
            if (CollectionUtils.isNotEmpty(names)) {
                PrincipalName principalName = names.get(0);
                if (Objects.nonNull(principalName)) {
                    return principalName.getName();
                }
            }
        }
        throw new RdosDefineException("当前keytab文件不包含principal信息");
    }

    /**
     * 更新hdfs yarn 组件信息
     * 开启 kerberos hdfs 添加dfs.namenode.kerberos.principal.pattern
     * yarn 添加 yarn.resourcemanager.principal.pattern
     * spark thrift 开启kerberos 需要手动添加hive-site里面的配置信息
     */
    private void checkKerberosConfig(Map<String, Object> config,Long clusterId,EComponentType componentType,Cluster cluster) {
        KerberosConfig kerberosConfig = kerberosDao.getByClusterId(clusterId);
        if(Objects.isNull(kerberosConfig)){
            return;
        }
        if(Objects.isNull(config) || Objects.isNull(componentType) || Objects.isNull(cluster)){
            return;
        }
        if(EComponentType.HDFS.equals(componentType)){
            config.put("dfs.namenode.kerberos.principal.pattern", "*");
        }

        if (EComponentType.YARN.equals(componentType)) {
            config.put("yarn.resourcemanager.principal.pattern", "*");
        }

        if (EComponentType.SPARK_THRIFT.equals(componentType)) {
            String localConsolePath = env.getLocalKerberosDir() + File.separator
                    + "CONSOLE_" + cluster.getId();
            LOGGER.info("add  SparkThrift hadoopKerberosConfig path {} ", localConsolePath);
            try {
                File hiveFile = new File(localConsolePath + File.separator + "hive-site.xml");
                if (!hiveFile.exists()) {
                    //本地没有下载sftp路径下的配置
                    if (downloadClusterSftpPath(cluster, localConsolePath)) {
                        return;
                    }
                }
                hiveFile = new File(localConsolePath + File.separator + "hive-site.xml");
                if (hiveFile.exists()) {
                    config.putAll(XmlFileUtil.parseAndRead(Lists.newArrayList(hiveFile)));
                }
            } catch (Exception e) {
                LOGGER.error("add  SparkThrift hadoopKerberosConfig file error {}", localConsolePath, e);
            }
        }
    }

    public boolean downloadClusterSftpPath(Cluster cluster, String localConsolePath) {
        if (Objects.isNull(cluster) || StringUtils.isBlank(localConsolePath)) {
            return false;
        }
        Engine hadoopEngine = getEngineByClusterId(cluster.getId());
        Component sftpComponent = componentDao.getByEngineIdAndComponentType(hadoopEngine.getId(), EComponentType.SFTP.getTypeCode());
        if (Objects.isNull(sftpComponent)) {
            return true;
        }
        Map<String, String> sftpMap = convertToMap(sftpComponent.getComponentConfig());
        if (Objects.isNull(sftpMap.get("path"))) {
            return true;
        }
        String hadoopConfigPath = String.format(SFTP_HADOOP_CONFIG_PATH, sftpMap.get("path"), SFTPHandler.CONSOLE_HADOOP_CONFIG, cluster.getClusterName());
        SFTPHandler handler = null;
        try {
            handler = SFTPHandler.getInstance(sftpMap);
            handler.downloadDir(hadoopConfigPath, localConsolePath);
        } catch (Exception e) {
            LOGGER.error("downloadSftpPath file error {}", localConsolePath, e);
        } finally {
            if (Objects.nonNull(handler)) {
                handler.close();
            }
        }
        return false;
    }


    /**
     * 删除之前的文件
     * @param localKerberosConf
     */
    private void deleteOldFile(String localKerberosConf) {
        File file = new File(localKerberosConf);
        if (file.exists()) {
            File[] files = file.listFiles();
            if (Objects.nonNull(files)) {
                for (File oldFile : files) {

                    if (oldFile.getName().endsWith(KerberosKey.KEYTAB.getKey()) && oldFile.isFile()) {

                        try {
                            oldFile.delete();
                        } catch (Exception e) {
                            LOGGER.error("delete hadoopKerberosConfig path {}  file name {} error ", oldFile.getPath(), oldFile.getName(), e);
                        }
                    }
                }
            }
        }
    }

    private void unzipKeytab(String localKerberosConf, Resource resource) {
        try {
            KerberosConfigVerify.getFilesFromZip(resource.getUploadedFileName(), localKerberosConf);
        } catch (Exception e) {
            KerberosConfigVerify.delFile(new File(localKerberosConf));
            throw e;
        }
    }


    public KerberosConfig getKerberosConfig(@Param("clusterId") Long clusterId) {
        KerberosConfig kerberosConfig = kerberosDao.getByClusterId(clusterId);
        return kerberosConfig;
    }

    private void updateKerberosConfig(Long clusterId, Resource resource, String remotePath, String principal,String keytabFileName) {
        KerberosConfig kerberosConfig = kerberosDao.getByClusterId(clusterId);
        kerberosConfig = Optional.ofNullable(kerberosConfig).orElse(new KerberosConfig());
        kerberosConfig.setName(keytabFileName);
        kerberosConfig.setOpenKerberos(1);
        kerberosConfig.setRemotePath(remotePath);
        kerberosConfig.setPrincipal(principal);
        kerberosConfig.setClusterId(clusterId);
        if (kerberosConfig.getId() != null && kerberosConfig.getId() > 0 ) {
            kerberosDao.update(kerberosConfig);
        } else {
            kerberosDao.insert(kerberosConfig);
        }

        //刷新缓存
        List<Engine> engines = engineDao.listByClusterId(clusterId);
        engines.stream().forEach(engine -> updateCache(engine.getId(),null));

    }

    public void deleteOldSftpFile(Long clusterId){
        Map<String, String> confMap = getSFTPConfig(clusterId);
        String path = confMap.get("path");
        if (StringUtils.isBlank(path)) {
            throw new RdosDefineException("SFTP组件的path配置不能为空");
        }
        StringBuilder destDir = new StringBuilder().append(path).append(SEPARATE).append(getSftpClusterKey(clusterId));
        SFTPHandler handler = SFTPHandler.getInstance(confMap);
        try {
            if(handler.isFileExist(destDir.toString())){
                Vector vector = handler.listFile(destDir.toString());
                for (Iterator<ChannelSftp.LsEntry> iterator = vector.iterator(); iterator.hasNext(); ) {
                    ChannelSftp.LsEntry str = iterator.next();
                    String filename = str.getFilename();
                    if (".".equals(filename) || "src/main".equals(filename)) {
                        continue;
                    }
                    if (StringUtils.isNotBlank(filename) && filename.endsWith(KerberosKey.KEYTAB.getKey())) {
                        LOGGER.info("delete hadoopKerberosConfig sftp path {}  file name {} error ", destDir.toString(), filename);
                        try {
                            handler.deleteDir(destDir.toString() + File.separator + filename);
                        } catch (Exception e) {
                            LOGGER.error("delete hadoopKerberosConfig sftp path {}  file name {} error ", destDir.toString(), filename, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("", e);
            if (e instanceof RdosDefineException) {
                throw (RdosDefineException) e;
            } else {
                throw new RdosDefineException("文件上传sftp失败");
            }
        } finally {
            if (handler != null) {
                handler.close();
            }
        }
    }

    public String uploadSftpDir(Long clusterId, String srcDir) {
        Map<String, String> confMap = getSFTPConfig(clusterId);
        String path = confMap.get("path");
        if (StringUtils.isBlank(path)) {
            throw new RdosDefineException("SFTP组件的path配置不能为空");
        }
        StringBuilder destDir = new StringBuilder().append(path).append(SEPARATE).append(getSftpClusterKey(clusterId));
        SFTPHandler handler = SFTPHandler.getInstance(confMap);
        try {
            KerberosConfigVerify.uploadLockFile(srcDir, destDir.toString(), handler);
            handler.uploadDir(path, srcDir);
            if (!handler.isFileExist(destDir.toString())) {
                throw new RdosDefineException("文件上传sftp失败");
            }
        } catch (Exception e) {
            LOGGER.error("", e);
            if (e instanceof RdosDefineException) {
                throw (RdosDefineException) e;
            } else {
                throw new RdosDefineException("文件上传sftp失败");
            }
        } finally {
            if (handler != null) {
                handler.close();
            }
        }

        return destDir.toString();
    }

    public Map<String, String> getSFTPConfig(Long clusterId) {
        Engine hadoopEngine = getEngineByClusterId(clusterId);
        Component sftpComponent = componentDao.getByEngineIdAndComponentType(hadoopEngine.getId(), EComponentType.SFTP.getTypeCode());
        if (sftpComponent == null) {
            throw new RdosDefineException("需要提前配置SFTP组件");
        }
        return convertToMap(sftpComponent.getComponentConfig());
    }

    private Map<String, String> convertToMap(String str) {
        JSONObject sftpObj = JSONObject.parseObject(str);
        Map<String, String> confMap = new HashMap<>();
        for (String key : sftpObj.keySet()) {
            confMap.put(key, sftpObj.getString(key));
        }
        return confMap;
    }


    private Engine getEngineByClusterId(Long clusterId) {
        Cluster cluster = clusterDao.getOne(clusterId);
        if (cluster == null) {
            throw new RdosDefineException(ErrorCode.DATA_NOT_FIND.getDescription());
        }

        Engine hadoopEngine = engineDao.getByClusterIdAndEngineType(clusterId, MultiEngineType.HADOOP.getType());
        if (hadoopEngine == null) {
            throw new RdosDefineException("该集群没有配置[HADOOP]引擎");
        }
        return hadoopEngine;
    }











    @Transactional(rollbackFor = Exception.class)
    public Component addOrUpdateComponent(@Param("clusterId") Long clusterId, @Param("clusterName") String clusterName, @Param("componentConfig") String componentConfig,
                                          @Param("resources") List<Resource> resources, @Param("hadoopVersion") String hadoopVersion,
                                          @Param("kerberosFileName") String kerberosFileName,@Param("componentTemplate") String componentTemplate,
                                          @Param("componentCode") Integer componentCode) {
        if (StringUtils.isBlank(componentConfig)) {
            throw new RdosDefineException("组件信息不能为空");
        }
        if(Objects.isNull(componentCode)){
            throw new RdosDefineException("组件类型不能为空");
        }
        ComponentDTO componentDTO = new ComponentDTO();
        componentDTO.setComponentConfig(componentConfig);
        componentDTO.setComponentTypeCode(componentCode);
        //新增 clusterName 修改clusterId
        if(Objects.isNull(clusterId)){
            clusterId = this.checkClusterWithName(clusterId, clusterName);
        }

        Component sftpComponent = null;
        if (CollectionUtils.isNotEmpty(resources)) {
            //上传资源需要依赖sftp组件
            sftpComponent = componentDao.getByClusterIdAndComponentType(clusterId, EComponentType.SFTP.getTypeCode());
            if (Objects.isNull(sftpComponent)) {
                throw new RdosDefineException("请先配置sftp组件");
            }

        }
        EComponentType componentType = EComponentType.getByCode(componentDTO.getComponentTypeCode());
        MultiEngineType engineType = EComponentType.getEngineTypeByComponent(componentType);
        Engine engine = engineDao.getByClusterIdAndEngineType(clusterId, engineType.getType());
        if (Objects.isNull(engine)) {
            //创建引擎
            engine = new Engine();
            engine.setClusterId(clusterId);
            engine.setEngineType(engineType.getType());
            engine.setEngineName(engineType.getName());
            engineDao.insert(engine);
            LOGGER.info("cluster {} add engine  {} ", clusterId, engine.getId());
        }
        Component addComponent = new ComponentDTO();
        BeanUtils.copyProperties(componentDTO, addComponent);
        Component dbComponent = componentDao.getByClusterIdAndComponentType(clusterId, addComponent.getComponentTypeCode());
        boolean isUpdate = false;
        if (Objects.nonNull(dbComponent)) {
           //更新
            isUpdate = true;
            addComponent = dbComponent;
        }
        addComponent.setHadoopVersion(Optional.ofNullable(hadoopVersion).orElse("hadoop2"));
        if(CollectionUtils.isNotEmpty(resources)){
            addComponent.setUploadFileName(resources.get(0).getFileName());
        }
        addComponent.setComponentName(componentType.getName());
        addComponent.setComponentTypeCode(componentType.getTypeCode());
        addComponent.setEngineId(engine.getId());
        addComponent.setComponentTemplate(componentTemplate);
        if (isUpdate) {
            componentDao.update(addComponent);
        } else {
            componentDao.insert(addComponent);
        }
        if (CollectionUtils.isNotEmpty(resources)) {
            //上传配置文件到sftp 供后续下载
            Map<String, String> map = JSONObject.parseObject(sftpComponent.getComponentConfig(), Map.class);
            SFTPHandler instance = SFTPHandler.getInstance(map);
            String remoteDir = map.get("path") + File.separator + this.buildSftpPath(clusterId, addComponent.getComponentTypeCode());
            for (Resource resource : resources) {
                try {
                    //TODO 测试联通性
                    if (resource.getFileName().equalsIgnoreCase(kerberosFileName)) {
                        this.updateComponentKerberosFile(clusterId, addComponent, instance, remoteDir, resource,addComponent.getId());
                    } else if(isUpdate){
                        this.updateComponentConfigFile(dbComponent, instance, remoteDir, resource);
                    }
                } catch (Exception e){
                    LOGGER.error("update component resource {}  error", resource.getUploadedFileName(), e);
                    throw new RdosDefineException("更新组件失败");
                } finally {
                    try {
                        FileUtils.forceDeleteOnExit(new File(resource.getUploadedFileName()));
                    } catch (IOException e) {
                        LOGGER.error("delete upload file {} error", resource.getUploadedFileName(), e);
                    }
                }
            }
        }
        addComponent.setClusterId(clusterId);
        return addComponent;
    }

    /**
     * 上传配置文件到sftp
     * @param dbComponent
     * @param instance
     * @param remoteDir
     * @param resource
     */
    private void updateComponentConfigFile(Component dbComponent, SFTPHandler instance, String remoteDir, Resource resource) {
        //原来配置
        String  deletePath = remoteDir + File.separator + dbComponent.getUploadFileName();
        //删除原来的文件配置zip
        instance.deleteFile(deletePath);
        //更新为原名
        instance.upload(remoteDir, resource.getUploadedFileName());
        instance.renamePath(remoteDir + File.separator + resource.getUploadedFileName().substring(resource.getUploadedFileName().lastIndexOf(File.separator)+1),
                remoteDir + File.separator + resource.getFileName());
    }


    /**
     * 解压kerberos文件到本地 并上传至sftp
     * * @param clusterId
     * @param addComponent
     * @param instance
     * @param remoteDir
     * @param resource
     * @return
     */
    private String updateComponentKerberosFile(Long clusterId, Component addComponent, SFTPHandler instance, String remoteDir, Resource resource,Long componentId) {
        //kerberos认证文件
        remoteDir = remoteDir + File.separator;
        //删除本地文件夹
        String kerberosPath = this.getLocalKerberosPath(clusterId, addComponent.getComponentTypeCode());
        try {
            FileUtils.deleteDirectory(new File(kerberosPath));
        } catch (IOException e) {
            LOGGER.error("delete old kerberos directory {} error", kerberosPath, e);
        }
        //解压到本地
        this.unzipKeytab(kerberosPath, resource);
        //获取principal
        File file = this.getKeyTabFile(kerberosPath);
        if (Objects.isNull(file)) {
            throw new RdosDefineException("keytab文件缺失");
        }
        String principal = this.getPrincipal(file);
        //删除sftp原来kerberos 的文件夹
        instance.deleteDir(remoteDir);
        //上传kerberos解压后的文件
        instance.uploadDir(remoteDir, kerberosPath);
        //更新数据库kerberos信息
        KerberosConfig kerberosConfig = kerberosDao.getByComponentId(componentId);
        boolean isFirstOpenKerberos = false;
        if(Objects.isNull(kerberosConfig)){
            kerberosConfig = new KerberosConfig();
            isFirstOpenKerberos = true;
        }
        kerberosConfig.setOpenKerberos(1);
        kerberosConfig.setPrincipal(principal);
        kerberosConfig.setName(file.getName());
        kerberosConfig.setRemotePath(remoteDir + File.separator + KERBEROS_PATH);
        kerberosConfig.setClusterId(clusterId);
        kerberosConfig.setComponentId(componentId);
        if (isFirstOpenKerberos) {
            kerberosDao.insert(kerberosConfig);
        } else {
            kerberosDao.update(kerberosConfig);
        }
        return remoteDir;
    }

    /**
     * 移除kerberos配置
     * @param componentId
     */
    public void closeKerberos(@Param("componentId") Long componentId) {
        kerberosDao.delete(componentId);
    }

    private Long checkClusterWithName(@Param("clusterId") Long clusterId, @Param("clusterName") String clusterName) {
        if(StringUtils.isBlank(clusterName)){
            throw new RdosDefineException("集群名称不能为空");
        }
        Cluster cluster = clusterDao.getByClusterName(clusterName);
        if (Objects.isNull(cluster)) {
            //创建集群
            ClusterDTO clusterDTO = new ClusterDTO();
            clusterDTO.setClusterName(clusterName);
            ClusterVO clusterVO = clusterService.addCluster(clusterDTO);
            if (Objects.nonNull(clusterVO)) {
                clusterId = clusterVO.getClusterId();
                LOGGER.info("add cluster {} ", clusterId);
            }
        } else {
            clusterId = cluster.getId();
        }
        return clusterId;
    }


    /**
     * parse zip中xml
     * @param resources
     * @return
     */
    public List<Object> config(@Param("resources") List<Resource> resources,@Param("componentType")Integer componentType) {
        List<Object> datas;
        try {
            Map<String, Object> xmlConfigMap = parseAndUploadXmlFile(resources);
            List<String> xmlName = componentTypeConfigMapping.get(componentType);
            datas = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(xmlName)) {
                //多个配置文件合并为一个map
                Map data = new HashMap();
                for (String xml : xmlName) {
                    Object xmlData = xmlConfigMap.get(xml);
                    if(xmlData instanceof Map){
                        data.putAll((Map) xmlData);
                    }
                }
                datas.add(data);
            } else {
                datas.addAll(xmlConfigMap.values());
            }
        } finally {
            for (Resource resource : resources) {
                try {
                    FileUtils.forceDeleteOnExit(new File(resource.getUploadedFileName()));
                } catch (IOException e) {
                    LOGGER.debug("delete config resource error {} ",resource.getUploadedFileName());
                }
            }
        }
        return datas;
    }


    @Forbidden
    public String buildSftpPath(Long clusterId, Integer componentCode) {
        return AppType.CONSOLE + "_" + clusterId + File.separator + componentCode;
    }


    /**
     * 获取本地kerberos配置地址
     * @param clusterId
     * @param componentCode
     * @return
     */
    @Forbidden
    public String getLocalKerberosPath(Long clusterId,Integer componentCode){
        return env.getLocalKerberosDir() + File.separator + AppType.CONSOLE + "_" + clusterId + File.separator + componentCode + File.separator + KERBEROS_PATH;
    }

    /**
     * 下载文件
     * @param componentId
     * @param downloadType  0:kerberos配置文件 1:配置文件 2:模板文件
     * @return
     */
    public File downloadFile(@Param("componentId") Long componentId, @Param("type") Integer downloadType) {
        Component component = componentDao.getOne(componentId);
        if(Objects.isNull(component)){
            throw new RdosDefineException("组件不存在");
        }
        Long clusterId = componentDao.getClusterIdByComponentId(componentId);
        Component sftpComponent = componentDao.getByClusterIdAndComponentType(clusterId, EComponentType.SFTP.getTypeCode());
        if(Objects.isNull(sftpComponent)){
            throw new RdosDefineException("sftp组件不存在");
        }
        Map<String, String> map = JSONObject.parseObject(sftpComponent.getComponentConfig(), Map.class);
        SFTPHandler instance = SFTPHandler.getInstance(map);
        String remoteDir = map.get("path") + File.separator + this.buildSftpPath(clusterId, component.getComponentTypeCode());
        String localDownLoadPath = downloadLocation + File.separator + component.getId();
        if (DownloadType.Kerberos.getCode() == downloadType) {
            remoteDir = remoteDir + File.separator + KERBEROS_PATH;
            localDownLoadPath = localDownLoadPath + File.separator + KERBEROS_PATH;
            instance.downloadDir(remoteDir, localDownLoadPath);
        } else {
            instance.downloadDir(remoteDir + File.separator + component.getUploadFileName(), localDownLoadPath);
        }
        File file = new File(localDownLoadPath);
        if(!file.exists()){
            throw new RdosDefineException("文件不存在");
        }
        String zipFilename = component.getUploadFileName();
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            //压缩成zip包
            if (Objects.nonNull(files)) {
                if (DownloadType.Kerberos.getCode() == downloadType) {
                    KerberosConfig kerberosConfig = kerberosDao.getByComponentId(componentId);
                    if(Objects.nonNull(kerberosConfig)){
                        zipFilename = kerberosConfig.getName() + "." + ZIP_CONTENT_TYPE;
                    }
                }
                ZipUtil.zipFile(downloadLocation + File.separator + zipFilename, Arrays.stream(files).collect(Collectors.toList()));
            }
            try {
                FileUtils.forceDeleteOnExit(file);
            } catch (IOException e) {
                LOGGER.error("delete upload file {} error", file.getName(), e);
            }
            return new File(downloadLocation + File.separator + zipFilename);
        } else {
            return new File(localDownLoadPath);
        }
    }


    /**
     * 加载各个组件的默认值
     * 解析yml文件转换为前端渲染格式
     *
     * @param componentType
     * @return
     */
    public List<TemplateVo> loadTemplate(@Param("componentType") Integer componentType) {
        Yaml yaml = new Yaml();
        Object result = null;
        EComponentType component = EComponentType.getByCode(componentType);
        try {
            result = yaml.load(this.preLoad(String.format("%s-template.yml",component.getName().toLowerCase())));
        } catch (Exception e) {
            LOGGER.error("load [{}] template error",componentType,e);
            return new ArrayList<>(0);
        }
        if (result instanceof Map) {
            Map<String, Object> configMap = (Map<String, Object>) result;
            List<TemplateVo> templateVos = new ArrayList<>();
            for (String key : configMap.keySet()) {
                if ("required".equalsIgnoreCase(key) || "optional".equalsIgnoreCase(key)) {
                    // 如果required 开头 单个tab选择
                    templateVos.addAll(this.getTemplateVos(configMap));
                } else {
                    Map<String, Object> groupMap = (Map<String, Object>) configMap.get(key);
                    // 不是required 开头 多个数组选择
                    TemplateVo group = new TemplateVo();
                    group.setValue(key);
                    group.setKey(key);
                    group.setType("GROUP");
                    for (String groupKey : groupMap.keySet()) {
                        group.setValues(this.getTemplateVos((Map<String, Object>) groupMap.get(groupKey)));
                    }
                    templateVos.add(group);
                }
            }
            return templateVos;
        }
        return new ArrayList<>(0);
    }

    private List<TemplateVo> getTemplateVos(Map<String, Object> configMap) {
        List<TemplateVo> templateVos = new ArrayList<>();
        for (String key : configMap.keySet()) {
            if ("required".equalsIgnoreCase(key)) {
                Map<String, Object> value = (Map<String, Object>) configMap.get(key);
                for (String s : value.keySet()) {
                    templateVos.add(this.parseKeyValueToVo(s, value, false,true));
                }
            } else if ("optional".equalsIgnoreCase(key)) {
                Map<String, Object> value = (Map<String, Object>) configMap.get(key);
                for (String s : value.keySet()) {
                    templateVos.add(this.parseKeyValueToVo(s, value, false,false));
                }
            }
        }
        return templateVos;
    }

    /**
     * 删除组件
     * @param componentId
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(@Param("componentId") Long componentId){
        Component component = componentDao.getOne(componentId);
        EngineAssert.assertTrue(component != null, ErrorCode.DATA_NOT_FIND.getDescription());

        if(EngineUtil.isRequiredComponent(component.getComponentTypeCode())){
            throw new RdosDefineException(component.getComponentName() + " 是必选组件，不可删除");
        }

        component.setIsDeleted(Deleted.DELETED.getStatus());
        componentDao.update(component);
        kerberosDao.delete(componentId);
        //引擎组件为空 删除引擎
        List<Component> componentList = listComponent(component.getEngineId());
        if (CollectionUtils.isEmpty(componentList)) {
            engineDao.delete(component.getEngineId());
        }
    }

    private String preLoad(String path) throws IOException {
        InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream(path);
        BufferedReader fileReader = new BufferedReader(new InputStreamReader(resourceAsStream));
        String temp;
        StringBuilder stringBuilder = new StringBuilder();
        while ((temp = fileReader.readLine()) != null) {
            stringBuilder.append(temp + "\n");
        }
        return stringBuilder.toString();
    }

    public TemplateVo parseKeyValueToVo(String valueKey, Map<String, Object> value, boolean multiValues,boolean required) {
        TemplateVo templateVo = new TemplateVo();
        templateVo.setKey(valueKey);
        templateVo.setValue("");
        templateVo.setRequired(required);
        Object defaultValue = value.get(valueKey);
        if (defaultValue instanceof List) {
            ArrayList defaultValueList = (ArrayList) defaultValue;
            //选择框
            for (Object o : defaultValueList) {
                if (o instanceof Map) {
                    Map<String, Object> sonMap = (Map<String, Object>) o;
                    String sonKey = new ArrayList<>(sonMap.keySet()).get(0);
                    TemplateVo sonTemplateVo = this.parseKeyValueToVo(sonKey, sonMap, true,required);
                    sonTemplateVo.setRequired(null);
                    templateVo.setType(EFrontType.RADIO.name());
                    if (Objects.isNull(templateVo.getValues())) {
                        templateVo.setValues(new ArrayList<>());
                    }
                    templateVo.getValues().add(sonTemplateVo);
                }
            }
            templateVo.setValue(templateVo.getValues().get(0).getValue());
        } else {
            if (defaultValue instanceof Map) {
                //依赖 radio 的选择的输入框
                Map<String, Object> defaultMap = (Map<String, Object>) defaultValue;
                templateVo.setDependencyKey(String.valueOf(defaultMap.get("dependencyKey")));
                templateVo.setDependencyValue(String.valueOf(defaultMap.get("dependencyValue")));
                templateVo.setType(EFrontType.INPUT.name());
            } else {
                //输入框
                templateVo.setValue(String.valueOf(Optional.ofNullable(defaultValue).orElse("")));
                if (!multiValues) {
                    templateVo.setType(EFrontType.INPUT.name());
                }
            }
        }
        return templateVo;
    }

}
