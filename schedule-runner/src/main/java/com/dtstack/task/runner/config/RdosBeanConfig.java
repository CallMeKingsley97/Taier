package com.dtstack.task.runner.config;

import com.dtstack.dtcenter.common.engine.ConsoleSend;
import com.dtstack.dtcenter.common.engine.EngineSend;
import com.dtstack.dtcenter.common.hadoop.HadoopConf;
import com.dtstack.task.common.env.EnvironmentContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2018/1/3
 */
@Configuration
public class RdosBeanConfig {

    @Autowired
    private EnvironmentContext environmentContext;

    @Bean(name = "consoleSend")
    public ConsoleSend consoleSend() {
        ConsoleSend consoleSend = null;
        if (StringUtils.isNotBlank(environmentContext.getConsoleNode())) {
            consoleSend = new ConsoleSend(environmentContext.getConsoleNode());
        }
        HadoopConf.setConsoleSend(consoleSend);
        return consoleSend;
    }

    @Bean
    public EngineSend engineSend(ConsoleSend consoleSend) {
        EngineSend engineSend = new EngineSend(environmentContext.getEngineNode());
        engineSend.setConsoleSend(consoleSend);
        return engineSend;
    }

}
