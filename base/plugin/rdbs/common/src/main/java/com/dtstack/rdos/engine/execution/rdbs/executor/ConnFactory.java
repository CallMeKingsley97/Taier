package com.dtstack.rdos.engine.execution.rdbs.executor;

import com.dtstack.rdos.commom.exception.RdosException;
import com.dtstack.rdos.common.util.MathUtil;
import com.dtstack.rdos.engine.execution.rdbs.constant.ConfigConstant;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reason:
 * Date: 2018/2/7
 * Company: www.dtstack.com
 * @author xuchao
 */

public abstract class ConnFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ConnFactory.class);

    private AtomicBoolean isFirstLoaded = new AtomicBoolean(true);

    protected String dbURL;

    private String userName;

    private String pwd;

    protected String driverName = null;

    protected String testSql = null;

    public void init(Properties properties) throws ClassNotFoundException {
        synchronized (ConnFactory.class){
            if(isFirstLoaded.get()){
                Class.forName(driverName);
                isFirstLoaded.set(false);
            }

        }
        dbURL = MathUtil.getString(properties.get(ConfigConstant.DB_URL));
        userName = MathUtil.getString(properties.get(ConfigConstant.USER_NAME));
        pwd = MathUtil.getString(properties.get(ConfigConstant.PWD));

        Preconditions.checkNotNull(dbURL, "db url can't be null");
        testConn();
    }

    protected List<String> splitSql(String sql) {
        if(StringUtils.isBlank(sql)) {
            return Collections.emptyList();
        }
        return Arrays.asList(sql.split(";"));
    }

    private void testConn() {
        Connection conn = null;
        Statement stmt = null;
        try{
            conn = getConn();
            stmt = conn.createStatement();
            stmt.execute(testSql);
        }catch (Exception e){
            throw new RdosException("get conn exception:" + e.toString());
        }finally {

            try{
                if(stmt != null){
                    stmt.close();
                }

                if(conn != null){
                    conn.close();
                }
            }catch (Exception e){
                LOG.error("", e);
            }
        }

    }

    public Connection getConn() throws ClassNotFoundException, SQLException, IOException {

        Connection conn;

        if (userName == null) {
            conn = DriverManager.getConnection(dbURL);
        } else {
            conn = DriverManager.getConnection(dbURL, userName, pwd);
        }

        return conn;
    }

    public boolean supportProcedure() {
        return true;
    }

    public List<String> buildSqlList(String sql) {
        throw new UnsupportedOperationException();
    }

    public abstract String getCreateProcedureHeader(String procName);

    public String getCreateProcedureTailer() { return ""; }

    public String getCallProc(String procName) {
        return String.format("call \"%s\"()", procName);
    }

    public String getDropProc(String procName) {
        return String.format("DROP PROCEDURE \"%s\"", procName);
    }
}
