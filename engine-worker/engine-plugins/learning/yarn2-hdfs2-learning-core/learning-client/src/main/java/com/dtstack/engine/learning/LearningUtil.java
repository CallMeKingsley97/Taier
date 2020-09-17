package com.dtstack.engine.learning;


import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.util.PluginInfoUtil;
import com.google.gson.Gson;
import org.apache.commons.lang.StringUtils;
import sun.misc.BASE64Decoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class LearningUtil {

    private static final BASE64Decoder DECODER = new BASE64Decoder();

    public static String[] buildPythonArgs(JobClient jobClient) throws IOException {
        String exeArgs = jobClient.getClassArgs();
        String[] args = exeArgs.split("\\s+");
        for(int i = 0; i < args.length - 1; ++i) {
            if("--launch-cmd".equals(args[i]) || "--cmd-opts".equals(args[i]) || "--remote-dfs-config".equals(args[i])) {
                args[i+1] = new String(DECODER.decodeBuffer(args[i+1]), "UTF-8");
            }
        }

        List<String> argList = new ArrayList<>();
        argList.addAll(Arrays.asList(args));

        Properties confProperties = jobClient.getConfProperties();
        confProperties.stringPropertyNames().stream()
                .map(String::trim)
                .forEach(key -> {
                    String value = confProperties.getProperty(key).trim();
                    String newKey = key.replaceAll("\\.", "-");

                    if (key.contains("priority")) {
                        newKey = "priority";
                        value = String.valueOf(jobClient.getPriority()).trim();
                    }
                    argList.add("--" + newKey);
                    argList.add(value);
                });

        //pluginInfo --> --remote-dfs-config
        if(StringUtils.isNotEmpty(jobClient.getPluginInfo())){
            argList.add("--remote-dfs-config");
            Object hdfsConf = PluginInfoUtil.getSpecKeyConf(jobClient.getPluginInfo(), PluginInfoUtil.HADOOP_CONF_KEY);
            argList.add(new Gson().toJson(hdfsConf));
        }

        return argList.toArray(new String[argList.size()]);
    }

}
