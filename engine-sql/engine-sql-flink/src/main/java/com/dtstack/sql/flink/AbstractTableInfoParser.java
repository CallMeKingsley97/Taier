/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 

package com.dtstack.sql.flink;

import com.dtstack.google.common.base.Strings;
import com.dtstack.google.common.collect.Maps;
import com.dtstack.sql.flink.table.AbstractTableInfo;
import com.dtstack.sql.flink.table.AbstractTableParser;
import com.dtstack.sql.flink.table.side.AbstractSideTableInfo;
import com.dtstack.sql.flink.table.side.StreamSideFactory;
import com.dtstack.sql.flink.table.sink.StreamSinkFactory;
import com.dtstack.sql.flink.table.source.StreamSourceFactory;
import com.dtstack.sql.flink.util.DtMathUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Create table statement parsing table structure to obtain specific information
 * Date: 2018/6/25
 * Company: www.dtstack.com
 * @author xuchao
 */

public class AbstractTableInfoParser {

    private final static String TYPE_KEY = "type";

    private final static String SIDE_TABLE_SIGN = "(?i)^PERIOD\\s+FOR\\s+SYSTEM_TIME$";

    private final static Pattern SIDE_PATTERN = Pattern.compile(SIDE_TABLE_SIGN);

    private  Map<String, AbstractTableParser> sourceTableInfoMap = Maps.newConcurrentMap();

    private  Map<String, AbstractTableParser> targetTableInfoMap = Maps.newConcurrentMap();

    private  Map<String, AbstractTableParser> sideTableInfoMap = Maps.newConcurrentMap();

    //Parsing loaded plugin
    public AbstractTableInfo parseWithTableType(int tableType, CreateTableParser.SqlParserResult parserResult,
                                                String localPluginRoot, String pluginLoadMode) throws Exception {
        AbstractTableParser absTableParser = null;
        Map<String, Object> props = parserResult.getPropMap();
        String type = DtMathUtil.getString(props.get(TYPE_KEY));

        if(Strings.isNullOrEmpty(type)){
            throw new RuntimeException("create table statement requires property of type");
        }

        if(tableType == ETableType.SOURCE.getType()){
            boolean isSideTable = checkIsSideTable(parserResult.getFieldsInfoStr());

            if(!isSideTable){
                absTableParser = sourceTableInfoMap.get(type);
                if(absTableParser == null){
                    absTableParser = StreamSourceFactory.getSqlParser(type, localPluginRoot, pluginLoadMode);
                    sourceTableInfoMap.put(type, absTableParser);
                }
            }else{
                absTableParser = sideTableInfoMap.get(type);
                if(absTableParser == null){
                    String cacheType = DtMathUtil.getString(props.get(AbstractSideTableInfo.CACHE_KEY));
                    absTableParser = StreamSideFactory.getSqlParser(type, localPluginRoot, cacheType, pluginLoadMode);
                    sideTableInfoMap.put(type + cacheType, absTableParser);
                }
            }

        }else if(tableType == ETableType.SINK.getType()){
            absTableParser = targetTableInfoMap.get(type);
            if(absTableParser == null){
                absTableParser = StreamSinkFactory.getSqlParser(type, localPluginRoot, pluginLoadMode);
                targetTableInfoMap.put(type, absTableParser);
            }
        }

        if(absTableParser == null){
            throw new RuntimeException(String.format("not support %s type of table", type));
        }

        Map<String, Object> prop = Maps.newHashMap();

        //Shield case
        parserResult.getPropMap().forEach((key,val) -> prop.put(key.toLowerCase(), val));

        return absTableParser.getTableInfo(parserResult.getTableName(), parserResult.getFieldsInfoStr(), prop);
    }

    /**
     * judge dim table of PERIOD FOR SYSTEM_TIME
     * @param tableField
     * @return
     */
    public static boolean checkIsSideTable(String tableField){
        String[] fieldInfos = StringUtils.split(tableField, ",");
        for(String field : fieldInfos){
            Matcher matcher = SIDE_PATTERN.matcher(field.trim());
            if(matcher.find()){
                return true;
            }
        }

        return false;
    }
}
