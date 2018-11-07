package com.dtstack.yarn.common.type;


import com.dtstack.yarn.DtYarnConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.util.List;

public class Python2Type extends AppType {

    @Override
    public String cmdPrefix(YarnConfiguration config) {
        String python = config.get(DtYarnConfiguration.PYTHON2_PATH);
        return StringUtils.isNotBlank(python) ? python : "python";
    }

    @Override
    public String name() {
        return "PYTHON2";
    }

    @Override
    public void env(List<String> envList) {
        super.env(envList);
        envList.add("PATH=" + "./:" + System.getenv("PATH"));
    }
}