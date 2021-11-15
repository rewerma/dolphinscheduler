/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.worker;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.IStoppable;
import org.apache.dolphinscheduler.common.enums.NodeType;
import org.apache.dolphinscheduler.common.thread.Stopper;
import org.apache.dolphinscheduler.remote.NettyRemotingServer;
import org.apache.dolphinscheduler.remote.command.CommandType;
import org.apache.dolphinscheduler.remote.config.NettyServerConfig;
import org.apache.dolphinscheduler.server.log.LoggerRequestProcessor;
import org.apache.dolphinscheduler.server.log.LoggerServer;
import org.apache.dolphinscheduler.server.worker.config.WorkerConfig;
import org.apache.dolphinscheduler.server.worker.plugin.TaskPluginManager;
import org.apache.dolphinscheduler.server.worker.processor.*;
import org.apache.dolphinscheduler.server.worker.registry.WorkerRegistryClient;
import org.apache.dolphinscheduler.server.worker.runner.RetryReportTaskStatusThread;
import org.apache.dolphinscheduler.server.worker.runner.WorkerManagerThread;
import org.apache.dolphinscheduler.service.alert.AlertClientService;
import org.apache.dolphinscheduler.service.bean.SpringApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.annotation.PostConstruct;
import java.util.Set;

/**
 * worker server
 */
@ComponentScan(value = "org.apache.dolphinscheduler")
@EnableTransactionManagement
public class WorkerServer implements IStoppable {


    /**
     *  netty server
     */
    private final NettyRemotingServer server;

    /**
     *  netty server config
     */
    private final NettyServerConfig serverConfig;

    /**
     *  loggger request processor
     */
    private final LoggerRequestProcessor requestProcessor;

    public WorkerServer() {
        this.serverConfig = new NettyServerConfig();
        this.serverConfig.setListenPort(Constants.RPC_PORT);
        this.server = new NettyRemotingServer(serverConfig);
        this.requestProcessor = new LoggerRequestProcessor();
        this.server.registerProcessor(CommandType.GET_LOG_BYTES_REQUEST, requestProcessor, requestProcessor.getExecutor());
        this.server.registerProcessor(CommandType.ROLL_VIEW_LOG_REQUEST, requestProcessor, requestProcessor.getExecutor());
        this.server.registerProcessor(CommandType.VIEW_WHOLE_LOG_REQUEST, requestProcessor, requestProcessor.getExecutor());
        this.server.registerProcessor(CommandType.REMOVE_TAK_LOG_REQUEST, requestProcessor, requestProcessor.getExecutor());
    }


    /**
     * logger
     */
    private static final Logger logger = LoggerFactory.getLogger(WorkerServer.class);

    /**
     * netty remote server
     */
    private NettyRemotingServer nettyRemotingServer;

    /**
     * worker config
     */
    @Autowired
    private WorkerConfig workerConfig;

    /**
     * spring application context
     * only use it for initialization
     */
    @Autowired
    private SpringApplicationContext springApplicationContext;

    /**
     * alert model netty remote server
     */
    private AlertClientService alertClientService;

    @Autowired
    private RetryReportTaskStatusThread retryReportTaskStatusThread;

    @Autowired
    private WorkerManagerThread workerManagerThread;

    /**
     * worker registry
     */
    @Autowired
    private WorkerRegistryClient workerRegistryClient;

    @Autowired
    private TaskPluginManager taskPluginManager;

    /**
     * worker server startup, not use web service
     *
     * @param args arguments
     */
    public static void main(String[] args) {
        Thread.currentThread().setName(Constants.THREAD_NAME_WORKER_SERVER);
        new SpringApplicationBuilder(WorkerServer.class)
            .web(WebApplicationType.NONE)
            .profiles("worker")
            .run(args);

        final LoggerServer server = new LoggerServer();
        server.start();
    }

    /**
     * worker server run
     */
    @PostConstruct
    public void run() {
        // alert-server client registry
        alertClientService = new AlertClientService(workerConfig.getAlertListenHost(), Constants.ALERT_RPC_PORT);

        // init remoting server
        NettyServerConfig serverConfig = new NettyServerConfig();
        serverConfig.setListenPort(workerConfig.getListenPort());
        this.nettyRemotingServer = new NettyRemotingServer(serverConfig);
        this.nettyRemotingServer.registerProcessor(CommandType.TASK_EXECUTE_REQUEST, new TaskExecuteProcessor(alertClientService, taskPluginManager));
        this.nettyRemotingServer.registerProcessor(CommandType.TASK_KILL_REQUEST, new TaskKillProcessor());
        this.nettyRemotingServer.registerProcessor(CommandType.DB_TASK_ACK, new DBTaskAckProcessor());
        this.nettyRemotingServer.registerProcessor(CommandType.DB_TASK_RESPONSE, new DBTaskResponseProcessor());
        this.nettyRemotingServer.registerProcessor(CommandType.PROCESS_HOST_UPDATE_REQUEST, new HostUpdateProcessor());
        this.nettyRemotingServer.start();

        // worker registry
        try {
            this.workerRegistryClient.registry();
            this.workerRegistryClient.setRegistryStoppable(this);
            Set<String> workerZkPaths = this.workerRegistryClient.getWorkerZkPaths();

            this.workerRegistryClient.handleDeadServer(workerZkPaths, NodeType.WORKER, Constants.DELETE_OP);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        // task execute manager
        this.workerManagerThread.start();

        // retry report task status
        this.retryReportTaskStatusThread.start();

        /*
         * registry hooks, which are called before the process exits
         */
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (Stopper.isRunning()) {
                close("shutdownHook");
            }
        }));
    }

    public void close(String cause) {
        try {
            // execute only once
            if (Stopper.isStopped()) {
                return;
            }

            logger.info("worker server is stopping ..., cause : {}", cause);

            // set stop signal is true
            Stopper.stop();

            try {
                // thread sleep 3 seconds for thread quitely stop
                Thread.sleep(3000L);
            } catch (Exception e) {
                logger.warn("thread sleep exception", e);
            }

            // close
            this.nettyRemotingServer.close();
            this.workerRegistryClient.unRegistry();
            this.alertClientService.close();
            this.springApplicationContext.close();
        } catch (Exception e) {
            logger.error("worker server stop exception ", e);
        }
    }

    @Override
    public void stop(String cause) {
        close(cause);
    }


    /**
     * server start
     */
    public void start()  {
        this.server.start();
        logger.info("logger server started, listening on port : {}", Constants.RPC_PORT);
        Runtime.getRuntime().addShutdownHook(new Thread(WorkerServer.this::stop));
    }

    /**
     * stop
     */
    public void stop() {
        this.server.close();
        logger.info("logger server shut down");
    }
}
