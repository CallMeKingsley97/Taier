package com.dtstack.engine.flink.parser;

import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.common.JarFileInfo;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class PrepareOperator {
	
	private static Pattern pattern = Pattern.compile("^(?!--)(?i)\\s*add\\s+jar\\s+with\\s+(\\S+)(\\s+AS\\s+(\\S+))?");
	private static Pattern keytabPattern = Pattern.compile("^(?!--)(?i)\\s*add\\s+file\\s+with\\s+(\\S+)?");

	public static JarFileInfo parseSql(String sql) {

		Matcher matcher = pattern.matcher(sql);
		if(!matcher.find()){
			throw new RdosDefineException("not a addJar operator:" + sql);
		}

		JarFileInfo jarFileInfo = new JarFileInfo();
		jarFileInfo.setJarPath(matcher.group(1));

		if(matcher.groupCount() == 3){
			jarFileInfo.setMainClass(matcher.group(3));
		}

		return jarFileInfo;
	}

	public static boolean verific(String sql){
		return pattern.matcher(sql).find();
	}

	public static String getFileName(String sql){
		Matcher matcher = keytabPattern.matcher(sql);
		if(!matcher.find()){
			throw new RdosDefineException("getFileName error: " + sql);
		}
		return matcher.group(1);
	}

	public static boolean verificKeytab(String sql){
		return keytabPattern.matcher(sql).find();
	}

	/*
	 * handle add jar statements and comment statements on the same line
	 * " --desc \n\n ADD JAR WITH xxxx"
	 */

	public static void handleFirstSql(List<String> sqlLists) {
		String[] sqls = sqlLists.get(0).split("\\n");
		if (sqls.length == 0) {
			return;
		}
		sqlLists.remove(0);
		sqlLists.addAll(Arrays.asList(sqls));
	}



}
