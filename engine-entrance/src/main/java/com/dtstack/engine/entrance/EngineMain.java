package com.dtstack.engine.entrance;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import com.dtstack.engine.common.Service;
import com.dtstack.engine.common.log.LogbackComponent;
import com.dtstack.engine.common.util.ShutdownHookUtil;
import com.dtstack.engine.common.util.SystemPropertyUtil;
import com.dtstack.engine.master.Master;
import com.dtstack.engine.master.config.CacheConfig;
import com.dtstack.engine.master.config.MybatisConfig;
import com.dtstack.engine.master.config.RdosBeanConfig;
import com.dtstack.engine.master.config.SdkConfig;
import com.dtstack.engine.master.config.ThreadPoolConfig;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.dtstack.engine.router.RouterService;
import com.google.common.collect.Lists;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * Date: 2017年02月17日 下午8:57:21
 * Company: www.dtstack.com
 *
 * @author sishu.yss
 */
public class EngineMain {

    private static final Logger logger = LoggerFactory.getLogger(EngineMain.class);

    private static final List<Class<? extends Service>> SERVICES = new ArrayList<Class<? extends Service>>();

    static {
        SERVICES.add(RouterService.class);
    }

    private static final List<Closeable> CLOSEABLES = Lists.newArrayList();


    private static EnvironmentContext environmentContext;

    public static void main(String[] args) throws Exception {
        try {
            setSystemProperty();
            LogbackComponent.setupLogger();

            ApplicationContext context = new AnnotationConfigApplicationContext(
                    EnvironmentContext.class, CacheConfig.class, ThreadPoolConfig.class,
                    MybatisConfig.class, RdosBeanConfig.class, SdkConfig.class);
            environmentContext = (EnvironmentContext) context.getBean("environmentContext");

            setHadoopUserName();
            // init service
            initServices(context);
            // add hook
            ShutdownHookUtil.addShutdownHook(EngineMain::shutdown, EngineMain.class.getSimpleName(), logger);

            ActorSystem system = ActorSystem.create("AkkaRemoteMaster", ConfigFactory.load("master.conf"));
            // Create an actor
            ActorRef actorRef = system.actorOf(Props.create(Master.class), "Master");
        } catch (Throwable e) {
            logger.error("only engine-master start error:{}", e);
            System.exit(-1);
        }
    }

    private static void setSystemProperty() {
        SystemPropertyUtil.setSystemUserDir();
    }

    private static void setHadoopUserName() {
        SystemPropertyUtil.setHadoopUserName(environmentContext.getHadoopUserName());
    }

    private static void initServices(ApplicationContext context) throws Exception {
        for (Class<? extends Service> serviceClass : SERVICES) {
            Class<? extends Service> c = serviceClass.asSubclass(Service.class);
            Service service = c.getConstructor(ApplicationContext.class).newInstance(context);
            service.initService();
            CLOSEABLES.add(service);
        }
    }

    private static void shutdown() {
        for (Closeable closeable : CLOSEABLES) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    logger.error("", e);
                }
            }
        }
    }
}
