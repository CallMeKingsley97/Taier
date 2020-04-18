package com.dtstack.engine.master.component;

import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.master.enums.KerberosKey;
import com.dtstack.engine.master.utils.HadoopConf;
import com.dtstack.engine.master.utils.HdfsOperator;
import org.apache.commons.collections.MapUtils;
import org.apache.hadoop.conf.Configuration;

import java.util.Map;

public class HDFSComponent extends BaseComponent {

    public HDFSComponent(Map<String, Object> allConfig) {
        super(allConfig);
    }

    @Override
    public void testConnection() throws Exception {
        HadoopConf hadoopConf = new HadoopConf();
        Configuration configuration = hadoopConf.getHadoopConf(allConfig);

        String principal = MapUtils.getString(allConfig, KerberosKey.PRINCIPAL.getKey());
        String keytabPath = MapUtils.getString(allConfig, KerberosKey.KEYTAB.getKey());
        String krb5Conf = MapUtils.getString(allConfig, KerberosKey.KRB5.getKey());
        //kerberos验证
        loginKerberos(configuration, principal, keytabPath, krb5Conf);

        try {
            //kerberos验证 认证之后 hdfs不重复做认证 com.dtstack.dtcenter.common.hadoop.DtKerberosUtils.needLoginKerberos
            configuration.set("hadoop.security.authorization","false");
            HdfsOperator.getFileSystem(configuration);
        } catch (Exception e){
            throw new RdosDefineException("连接hdfs失败:" + e.getMessage());
        } finally {
            try {
                HdfsOperator.release();
            } catch (Exception e){
                LOG.warn("Close hadoop fileSystem error:{}", e.getMessage());
            }
        }
    }

}

