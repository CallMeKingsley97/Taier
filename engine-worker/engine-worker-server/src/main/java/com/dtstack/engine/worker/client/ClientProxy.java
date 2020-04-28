package com.dtstack.engine.worker.client;

import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.JobIdentifier;
import com.dtstack.engine.common.akka.config.AkkaConfig;
import com.dtstack.engine.common.callback.CallBack;
import com.dtstack.engine.common.callback.ClassLoaderCallBackMethod;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.exception.ClientArgumentException;
import com.dtstack.engine.common.exception.ErrorCode;
import com.dtstack.engine.common.exception.LimitResourceException;
import com.dtstack.engine.common.exception.RdosDefineException;
import com.dtstack.engine.common.pojo.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 代理IClient实现类的proxy
 * Date: 2017/12/19
 * Company: www.dtstack.com
 *
 * @author xuchao
 */

public class ClientProxy implements IClient {

    private static final Logger logger = LoggerFactory.getLogger(ClientProxy.class);

    private IClient targetClient;

    private ExecutorService executorService;

    private long timeout = 300000;

    public ClientProxy(IClient targetClient) {
        this.targetClient = targetClient;
        this.timeout = AkkaConfig.getWorkerTimeout();
        executorService = new ThreadPoolExecutor(10, 10, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), new CustomThreadFactory(targetClient.getClass().getSimpleName() + "_" + this.getClass().getSimpleName()));
    }

    @Override
    public void init(Properties prop) throws Exception {
        CompletableFuture.runAsync(() -> {
            try {
                ClassLoaderCallBackMethod.callbackAndReset(new CallBack<String>() {
                    @Override
                    public String execute() throws Exception {
                        targetClient.init(prop);
                        return null;
                    }
                }, targetClient.getClass().getClassLoader(), true);
            } catch (Exception e) {
                throw new RdosDefineException(e.getMessage());
            }
        }, executorService).get(timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public JobResult submitJob(JobClient jobClient) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<JobResult>() {

                        @Override
                        public JobResult execute() throws Exception {
                            return targetClient.submitJob(jobClient);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public JobResult cancelJob(JobIdentifier jobIdentifier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<JobResult>() {

                        @Override
                        public JobResult execute() throws Exception {
                            return targetClient.cancelJob(jobIdentifier);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public RdosTaskStatus getJobStatus(JobIdentifier jobIdentifier) throws IOException {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<RdosTaskStatus>() {

                        @Override
                        public RdosTaskStatus execute() throws Exception {
                            return targetClient.getJobStatus(jobIdentifier);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public String getJobMaster(JobIdentifier jobIdentifier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<String>() {

                        @Override
                        public String execute() throws Exception {
                            return targetClient.getJobMaster(jobIdentifier);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public String getMessageByHttp(String path) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<String>() {

                        @Override
                        public String execute() throws Exception {
                            return targetClient.getMessageByHttp(path);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public String getJobLog(JobIdentifier jobIdentifier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<String>() {

                        @Override
                        public String execute() throws Exception {
                            return targetClient.getJobLog(jobIdentifier);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public boolean judgeSlots(JobClient jobClient) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<Boolean>() {

                        @Override
                        public Boolean execute() throws Exception {
                            return targetClient.judgeSlots(jobClient);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    if (e instanceof ClientArgumentException) {
                        throw new ClientArgumentException(e);
                    } else if (e instanceof LimitResourceException) {
                        throw new LimitResourceException(e.getMessage());
                    } else if (e instanceof RdosDefineException && ((RdosDefineException) e).getErrorCode() == ErrorCode.HTTP_CALL_ERROR) {
                        return false;
                    }
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public List<String> getContainerInfos(JobIdentifier jobIdentifier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<List<String>>() {

                        @Override
                        public List<String> execute() throws Exception {
                            return targetClient.getContainerInfos(jobIdentifier);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

    @Override
    public String getCheckpoints(JobIdentifier jobIdentifier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return ClassLoaderCallBackMethod.callbackAndReset(new CallBack<String>() {

                        @Override
                        public String execute() throws Exception {
                            return targetClient.getCheckpoints(jobIdentifier);
                        }
                    }, targetClient.getClass().getClassLoader(), true);
                } catch (Exception e) {
                    throw new RdosDefineException(e.getMessage());
                }
            }, executorService).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RdosDefineException(e.getMessage());
        }
    }

}
