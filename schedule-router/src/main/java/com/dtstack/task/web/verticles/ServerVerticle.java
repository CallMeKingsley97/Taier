package com.dtstack.task.web.verticles;

import com.dtstack.task.common.env.EnvironmentContext;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * @author sishu.yss
 */

public class ServerVerticle extends AbstractVerticle{

    private static final Logger LOG = LoggerFactory.getLogger(ServerVerticle.class);

	private static ApplicationContext context;

	private static EnvironmentContext environmentContext;

	private static String root ="/api/task";

	private static String uploadLocation = System.getProperty("user.dir") + File.separator + "upload";

    @Override
    public void start(Future<Void> future) throws Exception {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setUploadsDirectory(uploadLocation).setDeleteUploadedFilesOnEnd(false));
        router.route().handler(CorsHandler.create("*")
                .allowedHeaders(allowHearders())
                .allowedMethods(allowMethods()));
        router.route().handler(CookieHandler.create());
        router.postWithRegex(root + "/.*").handler(new AllRequestVerticle(context)::request);
        vertx.createHttpServer(new HttpServerOptions().setCompressionSupported(true))
                .requestHandler(router::accept)
                .listen(config().getInteger("http.port", environmentContext.getHttpPort()),
                        config().getString("http.address", environmentContext.getHttpAddress()), result -> {
                            if (result.succeeded()){
                                future.complete();
                            }else{
                                future.fail(result.cause());
                                LOG.error("", result.cause());
                                System.exit(-1);
                            }
                        });
    }

    private Set<String> allowHearders() {
        Set<String> allowHeaders = new HashSet<String>();
        allowHeaders.add("x-requested-with");
        allowHeaders.add("Access-Control-Allow-Origin");
        allowHeaders.add("origin");
        allowHeaders.add("Content-Type");
        allowHeaders.add("accept");
        return allowHeaders;
    }

    private Set<HttpMethod> allowMethods() {
        Set<HttpMethod> allowMethods = new HashSet<>();
        allowMethods.add(HttpMethod.POST);
        return allowMethods;
    }

	public static void setContext(ApplicationContext context) {
		ServerVerticle.context = context;
	}

	public static void setEnvironmentContext(EnvironmentContext environmentContext) {
		ServerVerticle.environmentContext = environmentContext;
	}

}
