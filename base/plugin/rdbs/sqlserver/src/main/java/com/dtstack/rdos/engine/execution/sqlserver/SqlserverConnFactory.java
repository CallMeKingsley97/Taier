package com.dtstack.rdos.engine.execution.sqlserver;


import com.dtstack.rdos.engine.execution.rdbs.executor.ConnFactory;

public class SqlserverConnFactory extends ConnFactory {

    public SqlserverConnFactory() {
        driverName = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        testSql = "select 1111";
    }

    @Override
    public String getCreateProcedureHeader(String procName) {
        return String.format("create procedure \"%s\" as\n", procName);
    }

    @Override
    public String getCallProc(String procName) {
        return String.format("execute \"%s\"", procName);
    }
}
