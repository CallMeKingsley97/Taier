package com.dtstack.engine.base.util;

import com.dtstack.engine.base.BaseConfig;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.common.util.SFTPHandler;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.HadoopKerberosName;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.kerby.kerberos.kerb.keytab.Keytab;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class KerberosUtils {

    private static final Logger logger = LoggerFactory.getLogger(KerberosUtils.class);

    private static final String USER_DIR = System.getProperty("user.dir");
    private static final String LOCAL_KEYTAB_DIR = USER_DIR + "/keytab";
    private static final String KRB5_CONF = "java.security.krb5.conf";
    private static final String KERBEROS_AUTH = "hadoop.security.authentication";
    private static final String SECURITY_TO_LOCAL = "hadoop.security.auth_to_local";
    private static final String KERBEROS_AUTH_TYPE = "kerberos";

    private static Map<String, String> principalMap = new ConcurrentHashMap<>();

    /**
     * @param config        任务外层配置
     * @param supplier
     * @param configuration 集群如yarn配置信息
     * @param <T>
     * @return
     * @throws Exception
     */
    public static <T> T login(BaseConfig config, Supplier<T> supplier, Configuration configuration) throws Exception {

        if (Objects.isNull(config) || !config.isOpenKerberos()) {
            return supplier.get();
        }

        synchronized(KerberosUtils.class) {
            String fileName = config.getPrincipalFile();
            String remoteDir = config.getRemoteDir();
            String localDir = LOCAL_KEYTAB_DIR + remoteDir;

            File path = new File(localDir);
            if (!path.exists()) {
                path.mkdirs();
            }

            logger.info("fileName:{}, remoteDir:{}, localDir:{}, sftpConf:{}", fileName, remoteDir, localDir, config.getSftpConf());
            SFTPHandler handler = SFTPHandler.getInstance(config.getSftpConf());
            String keytabPath = handler.loadOverrideFromSftp(fileName, remoteDir, localDir, false);

            String krb5ConfName = config.getKrbName();
            String krb5ConfPath = "";
            if (StringUtils.isNotBlank(krb5ConfName)) {
                krb5ConfPath = handler.loadOverrideFromSftp(krb5ConfName, config.getRemoteDir(), localDir, true);
            }

            try {
                handler.close();
            } catch (Exception e) {
            }

            String principal = KerberosUtils.getPrincipal(keytabPath);
            config.setPrincipalName(principal);
            config.setPrincipalPath(keytabPath);

            logger.info("kerberos login, principal:{}, keytabPath:{}, krb5ConfPath:{}", principal, keytabPath, krb5ConfPath);
            return KerberosUtils.loginKerberosWithCallBack(
                    configuration,
                    keytabPath,
                    principal,
                    krb5ConfPath,
                    supplier
            );
        }
    }

    /**
     * @see HadoopKerberosName#setConfiguration(org.apache.hadoop.conf.Configuration)
     * @param allConfig
     * @param keytabPath
     * @param principal
     * @param krb5Conf
     * @param supplier
     * @param <T>
     * @return
     */
    private static synchronized <T> T loginKerberosWithCallBack(Configuration allConfig, String keytabPath, String principal, String krb5Conf, Supplier<T> supplier) {
        if (StringUtils.isNotEmpty(krb5Conf)) {
            System.setProperty(KRB5_CONF, krb5Conf);
        }
        /*如果需要走/etc/krb5.conf认证  在allConfig添加hadoop.security.authentication kerberos 即可
            case KERBEROS:
            case KERBEROS_SSL:
          如果krb5.conf 不在对应的/etc/下 需要手动指定目录的  在配置文件中hadoop.security.auth_to_local
          需要手动配置rules
          <property>
            <name>hadoop.security.auth_to_local</name>
            <value>
            RULE:[1:$1@$0](^.*@DTSTACK\.COM)s/^(.*)@DTSTACK\.COM/$1/g
            RULE:[2:$1@$0](^.*@DTSTACK\.COM)s/^(.*)@DTSTACK\.COM/$1/g
            </value>
          </property>
        */
        if (Objects.isNull(allConfig.get(SECURITY_TO_LOCAL))) {
            allConfig.set(KERBEROS_AUTH, KERBEROS_AUTH_TYPE);
        }

        try {
            sun.security.krb5.Config.refresh();
            UserGroupInformation.setConfiguration(allConfig);

            UserGroupInformation ugi = UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, keytabPath);
            logger.info("userGroupInformation current user = {} ugi user  = {} ", UserGroupInformation.getCurrentUser(), ugi.getUserName());
            return ugi.doAs((PrivilegedExceptionAction<T>) supplier::get);
        } catch (Exception e) {
            logger.error("{}", keytabPath, e);
            throw new RdosDefineException("kerberos校验失败, Message:" + e.getMessage());
        }
    }

    public static synchronized String getPrincipal(String filePath) {
        String nowPrincipal = principalMap.computeIfAbsent(filePath, k -> {
            Keytab keytab = null;
            try {
                keytab = Keytab.loadKeytab(new File(filePath));
            } catch (IOException e) {
                logger.error("Principal {} parse error e: {}!", filePath, e.getMessage());
                throw new RdosDefineException("keytab文件解析异常", e);
            }
            List<PrincipalName> principals = keytab.getPrincipals();
            String principal = "";
            if (principals.size() != 0) {
                principal = principals.get(0).getName();
            } else {
                logger.error("Principal must not be null!");
            }
            logger.info("getPrincipal filePath:{} principal:{}", filePath, principal);
            return principal;
        });
        return nowPrincipal;
    }

    public static String getKeytabPath(BaseConfig config) {
        String fileName = config.getPrincipalFile();
        String remoteDir = config.getRemoteDir();
        String localDir = LOCAL_KEYTAB_DIR + remoteDir;

        File path = new File(localDir);
        if (!path.exists()) {
            path.mkdirs();
        }

        logger.info("fileName:{}, remoteDir:{}, localDir:{}, sftpConf:{}", fileName, remoteDir, localDir, config.getSftpConf());
        SFTPHandler handler = SFTPHandler.getInstance(config.getSftpConf());
        String keytabPath = handler.loadFromSftp(fileName, remoteDir, localDir);
        logger.info("keytabPath:{}", keytabPath);
        return keytabPath;
    }

    public static Configuration convertMapConfToConfiguration(Map<String,Object> allConfig) {
        if(MapUtils.isEmpty(allConfig)){
            return null;
        }
        Configuration conf = new Configuration();
        for (String key : allConfig.keySet()) {
            conf.set(key, String.valueOf(allConfig.get(key)));
        }
        return conf;
    }
}
