/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.util.Objects;

/**
 * socket 处理器基类
 * @param <S>
 */
public abstract class SocketProcessorBase<S> implements Runnable {

    /**
     * 内部包含一个 socket 的包装对象
     */
    protected SocketWrapperBase<S> socketWrapper;
    /**
     * 本次socket 相关事件
     */
    protected SocketEvent event;

    public SocketProcessorBase(SocketWrapperBase<S> socketWrapper, SocketEvent event) {
        reset(socketWrapper, event);
    }


    public void reset(SocketWrapperBase<S> socketWrapper, SocketEvent event) {
        Objects.requireNonNull(event);
        this.socketWrapper = socketWrapper;
        this.event = event;
    }


    @Override
    public final void run() {
        // 这里加锁是因为该对象可能会同时以 写事件和读事件触发
        synchronized (socketWrapper) {
            // It is possible that processing may be triggered for read and
            // write at the same time. The sync above makes sure that processing
            // does not occur in parallel. The test below ensures that if the
            // first event to be processed results in the socket being closed,
            // the subsequent events are not processed.
            if (socketWrapper.isClosed()) {
                return;
            }
            doRun();
        }
    }


    protected abstract void doRun();
}
