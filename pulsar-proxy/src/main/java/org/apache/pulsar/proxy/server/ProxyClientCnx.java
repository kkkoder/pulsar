/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.proxy.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import org.apache.pulsar.PulsarVersion;
import org.apache.pulsar.client.impl.ClientCnx;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.common.api.AuthData;
import org.apache.pulsar.common.api.proto.PulsarApi;
import org.apache.pulsar.common.protocol.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class ProxyClientCnx extends ClientCnx {

    private String clientAuthRole;
    private String clientAuthMethod;
    private int protocolVersion;
    private boolean forwardAuthorizationCredentials;
    private Supplier<CompletableFuture<AuthData>> clientAuthDataSupplier;

    public ProxyClientCnx(ClientConfigurationData conf, EventLoopGroup eventLoopGroup, String clientAuthRole,
                          Supplier<CompletableFuture<AuthData>> clientAuthDataSupplier,
                          String clientAuthMethod, int protocolVersion,
                          boolean forwardAuthorizationCredentials) {
        super(conf, eventLoopGroup);
        this.clientAuthRole = clientAuthRole;
        this.clientAuthMethod = clientAuthMethod;
        this.protocolVersion = protocolVersion;
        this.forwardAuthorizationCredentials = forwardAuthorizationCredentials;
        this.clientAuthDataSupplier = clientAuthDataSupplier;
    }

    @Override
    protected CompletableFuture<ByteBuf> newConnectCommand() throws Exception {
        authenticationDataProvider = authentication.getAuthData(remoteHostName);
        AuthData authData = authenticationDataProvider.authenticate(AuthData.INIT_AUTH_DATA);
        return clientAuthDataSupplier.get().thenApply(clientAuthData -> {
                    if (log.isDebugEnabled()) {
                        log.debug("New Connection opened via ProxyClientCnx with params clientAuthRole = {}," +
                                        " clientAuthData = {}, clientAuthMethod = {}",
                                clientAuthRole, clientAuthData, clientAuthMethod);
                    }

                    return Commands.newConnect(authentication.getAuthMethodName(), authData, this.protocolVersion,
                            PulsarVersion.getVersion(), proxyToTargetBrokerAddress, clientAuthRole, clientAuthData,
                            clientAuthMethod);
                }
        );
    }

    @Override
    protected void handleAuthChallenge(PulsarApi.CommandAuthChallenge authChallenge) {
        boolean isRefresh = Arrays.equals(
                AuthData.REFRESH_AUTH_DATA_BYTES,
                authChallenge.getChallenge().getAuthData().toByteArray()
        );

        if (!forwardAuthorizationCredentials || !isRefresh) {
            super.handleAuthChallenge(authChallenge);
            return;
        }

        try {
            clientAuthDataSupplier.get()
                    .thenAccept(authData -> {
                        sendAuthResponse(authData, clientAuthMethod);
                    });
        } catch (Exception e) {
            log.error("{} Error mutual verify: {}", ctx.channel(), e);
        }
    }

    private void sendAuthResponse(AuthData authData, String authMethod) {
        ByteBuf response = Commands.newAuthResponse(
                authMethod,
                authData,
                protocolVersion,
                PulsarVersion.getVersion()
        );

        if (log.isDebugEnabled()) {
            log.debug("{} Mutual auth {}", ctx.channel(), authentication.getAuthMethodName());
        }

        ctx.writeAndFlush(response).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                log.warn("{} Failed to send response for mutual auth to broker: {}", ctx.channel(),
                        writeFuture.cause().getMessage());
            }
        });
    }

    private static final Logger log = LoggerFactory.getLogger(ProxyClientCnx.class);
}
