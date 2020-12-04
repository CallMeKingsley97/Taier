package com.dtstack.sql.rdb;

import com.dtstack.sql.ParseResult;
import com.dtstack.sql.SqlType;
import com.dtstack.sql.handler.IUglySqlHandler;
import com.dtstack.sql.handler.LibraUglySqlHandler;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;

public class AlterSqlParserTest {

    static IUglySqlHandler iUglySqlHandler = new LibraUglySqlHandler();
    static CalciteNodeParser calciteNodeParser = new CalciteNodeParser(iUglySqlHandler);


    @Test
    public void deleteSqlTest() throws Exception {
        String sql = "alter table ee.ss add column type varchar(20)";
        ParseResult parseResult = calciteNodeParser.parseSql(sql, "shixi", new HashMap<>());
        Assert.assertEquals(parseResult.getSqlType(), SqlType.ALTER);
    }
}
