package com.dtstack.taier.common.engine;

import com.alibaba.fastjson.JSONObject;

/**
 * @author: 小北(xiaobei @ dtstack.com)
 * @description:
 * @create: 2021-12-15 22:38
 **/
public class JdbcInfo {

    private String typeName;
    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClassName;
    private Boolean useConnectionPool;
    private Integer maxPoolSize;
    private Integer minPoolSize;
    private Integer initialPoolSize;
    private Integer jdbcIdel;
    private Integer maxRows;
    private Integer queryTimeout;
    private Integer checkTimeout;
    private JSONObject kerberosConfig;
    private String version;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public JSONObject getKerberosConfig() {
        return kerberosConfig;
    }

    public void setKerberosConfig(JSONObject kerberosConfig) {
        this.kerberosConfig = kerberosConfig;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public Boolean getUseConnectionPool() {
        return useConnectionPool;
    }

    public void setUseConnectionPool(Boolean useConnectionPool) {
        this.useConnectionPool = useConnectionPool;
    }

    public Integer getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(Integer maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public Integer getMinPoolSize() {
        return minPoolSize;
    }

    public void setMinPoolSize(Integer minPoolSize) {
        this.minPoolSize = minPoolSize;
    }

    public Integer getInitialPoolSize() {
        return initialPoolSize;
    }

    public void setInitialPoolSize(Integer initialPoolSize) {
        this.initialPoolSize = initialPoolSize;
    }

    public Integer getJdbcIdel() {
        return jdbcIdel;
    }

    public void setJdbcIdel(Integer jdbcIdel) {
        this.jdbcIdel = jdbcIdel;
    }

    public Integer getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(Integer maxRows) {
        this.maxRows = maxRows;
    }

    public Integer getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Integer queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public Integer getCheckTimeout() {
        return checkTimeout;
    }

    public void setCheckTimeout(Integer checkTimeout) {
        this.checkTimeout = checkTimeout;
    }
}
