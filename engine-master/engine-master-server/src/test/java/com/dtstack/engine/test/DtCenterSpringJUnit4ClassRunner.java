package com.dtstack.engine.test;

import org.junit.runners.model.InitializationError;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

public class DtCenterSpringJUnit4ClassRunner extends SpringJUnit4ClassRunner {
    private final static String DICTIONARY_NAME = "DAGScheduleX";
    /**
     * 设置 user.dir,使用项目根目录下的配置文件
     */
    public DtCenterSpringJUnit4ClassRunner(Class<?> clazz) throws InitializationError {
        super(clazz);
        //获得项目文件的根目录
        String s_pre = System.getProperty("user.dir");
        int index = s_pre.indexOf(DICTIONARY_NAME);
        System.setProperty("user.dir", s_pre.substring(0, index + DICTIONARY_NAME.length()));
    }
}
