package com.dtstack.engine.sparkyarn.sparkext;

import com.dtstack.engine.base.filesystem.FilesystemManager;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.sparkyarn.sparkyarn.SparkYarnConfig;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import org.apache.hadoop.conf.Configuration;
import org.apache.spark.SparkConf;
import org.apache.spark.deploy.yarn.ClientArguments;
import org.apache.spark.deploy.yarn.DtCDHClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * 修改Saprk yarn client ---> 修改提交之前的配置包打包
 * Date: 2018/5/9
 * Company: www.dtstack.com
 *
 * @author xuchao
 */

public class ClientExt extends DtCDHClient {

    private static final Logger LOG = LoggerFactory.getLogger(ClientExt.class);

    /**
     * 是否从本地的环境变量加载
     */
    private boolean isLocal = true;

    private SparkYarnConfig sparkYarnConfig;

    private static String userDir = System.getProperty("user.dir");

    private static String tmpHadoopFilePath = userDir + "/tmpHadoopConf";

    public static String XML_SUFFIX = ".xml";
    public static String CONF_SUFFIX = ".conf";

    private SparkConf sparkConf;

    private Configuration hadoopConf;

    private FilesystemManager filesystemManager;

    public ClientExt(FilesystemManager filesystemManager, ClientArguments args, Configuration hadoopConf, SparkConf sparkConf) {
        super(args, hadoopConf, sparkConf);
        this.filesystemManager = filesystemManager;
        this.sparkConf = sparkConf;
        this.hadoopConf = hadoopConf;
    }

    public void setSparkYarnConfig(SparkYarnConfig sparkYarnConfig) {
        this.sparkYarnConfig = sparkYarnConfig;
    }

    @Override
    public void loadHadoopConf(scala.collection.mutable.HashMap hadoopConfFiles) {
        if (!Strings.isNullOrEmpty(sparkYarnConfig.getConfHdfsPath())) {
            isLocal = false;
        }

        if (isLocal) {
            loadConfFromLocal(hadoopConfFiles);
        } else {
            String confDirName = this.creatDirIfPresent();
            this.loadConfFromDir(hadoopConfFiles, confDirName);
        }

    }

    private String creatDirIfPresent() {
        String confMd5Sum = sparkYarnConfig.getMd5sum();
        String confFileDirName = String.format("%s/%s", tmpHadoopFilePath, confMd5Sum);
        String remotePath = sparkYarnConfig.getConfHdfsPath();
        File dirFile = new File(confFileDirName);

        try {
            Files.createParentDirs(dirFile);
        } catch (IOException e) {
            throw new RdosDefineException(String.format("can not create dir '%s' on engine", dirFile.getParent()));
        }

        if (dirFile.exists()) {
            File[] files = dirFile.listFiles();
            if (files != null && files.length > 0) {
                return confFileDirName;
            }
        } else {
            if (!dirFile.mkdir()) {
                throw new RdosDefineException(String.format("can not create dir '%s' on engine", confFileDirName));
            }
        }


        boolean downLoadSuccess = filesystemManager.downloadDir(remotePath, confFileDirName);
        LOG.info("downloadDir status is: {} ", downLoadSuccess);

//        try {
//            Files.createParentDirs(dirFile);
//        } catch (IOException e) {
//            throw new RdosDefineException(String.format("can not create dir '%s' on engine", dirFile.getParent()));
//        }
//
//        if (dirFile.exists()) {
//            File[] files = dirFile.listFiles();
//            if (files != null && files.length > 0) {
//                return confFileDirName;
//            }
//        } else {
//            if (!dirFile.mkdir()) {
//                throw new RdosDefineException(String.format("can not create dir '%s' on engine", confFileDirName));
//            }
//        }
//
//        boolean downloadFlag = false;
//        if (sparkYarnConfig.getSftpConf() != null && StringUtils.isNotBlank(sparkYarnConfig.getSftpConf().getHost())) {
//            downloadFlag = this.downloadFileFromSftp(confFileDirName);
//        }
//        if (!downloadFlag){
//            downloadFlag = this.downloadFileFromHdfs(confFileDirName);
//        }
//        if (!downloadFlag){
//            throw new RuntimeException("----download file exception---");
//        }
        return confFileDirName;
    }

    public void loadConfFromDir(scala.collection.mutable.HashMap hadoopConfFiles, String confDirName) {
        File confDir = new File(confDirName);
        File[] files = confDir.listFiles((dir, name) -> name.endsWith(XML_SUFFIX) || name.endsWith(CONF_SUFFIX));
        for (File file : files) {
            String fileName = file.getName();
            hadoopConfFiles.put(fileName, file);
        }
    }

//    private boolean downloadFileFromHdfs(String confFileDirName) {
//        String hdfsPath = sparkYarnConfig.getConfHdfsPath();
//        try {
//            Map<String, String> files = FileUtil.downLoadDirFromHdfs(hdfsPath, confFileDirName, hadoopConf);
//            LOG.info("download file from Hdfs, fileSize: " + files.size());
//            if (!files.isEmpty()) {
//                return true;
//            }
//        } catch (Exception e) {
//            LOG.error("", e);
//            try {
//                //下载失败后文件可能没有成功下载或下载不全，直接删除该目录
//                FileUtil.deleteFile(confFileDirName);
//            } catch (Exception e1) {
//                LOG.error("", e1);
//            }
//        }
//        return false;
//    }
//
//    private boolean downloadFileFromSftp(String confFileDirName) {
//        //从Sftp下载文件到目录下
//        SftpConfig sftpConf = sparkYarnConfig.getSftpConf();
//        String sftpPath = sparkYarnConfig.getConfHdfsPath();
//
//        SFTPHandler handler = null;
//        try {
//            handler = SFTPHandler.getInstance(sftpConf);
//            int files = handler.downloadDir(sftpPath, confFileDirName);
//            LOG.info("download file from SFTP, fromPath:{} toPath:{} fileSize:{}", sftpPath, confFileDirName, files);
//            if (files > 0) {
//                return true;
//            }
//        } catch (Throwable e) {
//            LOG.error("download file from SFTP error, fromPath:{} toPath:{}", sftpPath, confFileDirName, e);
//            try {
//                //下载失败后文件可能没有成功下载或下载不全，直接删除该目录
//                FileUtil.deleteFile(confFileDirName);
//            } catch (Exception e1) {
//                LOG.error("", e1);
//            }
//        } finally {
//            if (handler != null) {
//                handler.close();
//            }
//        }
//        return false;
//    }

}
