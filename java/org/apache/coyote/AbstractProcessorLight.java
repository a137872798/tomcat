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
package org.apache.coyote;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.juli.logging.Log;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * This is a light-weight abstract processor implementation that is intended as
 * a basis for all Processor implementations from the light-weight upgrade
 * processors to the HTTP/AJP processors.
 * 处理器骨架类    核心处理方法 由子类实现
 */
public abstract class AbstractProcessorLight implements Processor {

    /**
     * DispatchType 包含 非阻塞 读/写   该对象内部 维护了所有待处理的事件
     */
    private Set<DispatchType> dispatches = new CopyOnWriteArraySet<>();


    /**
     * 根据socket 和事件类型来处理   这里相当于是最基础的 分发请求的地方 怎么处理 要看子类实现
     * @param socketWrapper The connection to process   socket包装对象
     * @param status The status of the connection that triggered this additional
     *               processing     代表本次处理的事件类型
     *
     * @return
     * @throws IOException
     */
    @Override
    public SocketState process(SocketWrapperBase<?> socketWrapper, SocketEvent status)
            throws IOException {

        SocketState state = SocketState.CLOSED;
        Iterator<DispatchType> dispatches = null;
        // 进入该方法后 在没有处理完数据流前会不断的自旋 比如 接收到read事件 返回Long 代表数据不足 这里还是会继续进入自旋
        do {
            // dispatches  后面会变成 成员变量.dispatches
            if (dispatches != null) {
                DispatchType nextDispatch = dispatches.next();
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Processing dispatch type: [" + nextDispatch + "]");
                }
                // 根据类型分发事件  由子类实现  这里返回处理完的状态
                state = dispatch(nextDispatch.getSocketStatus());
                if (!dispatches.hasNext()) {
                    // 这里在检查 管道的状态 只有state 为 open 才会往下处理
                    state = checkForPipelinedData(state, socketWrapper);
                }
                // 如果本次事件是断开连接  不做处理
            } else if (status == SocketEvent.DISCONNECT) {
                // Do nothing here, just wait for it to get recycled
                // 如果是 异步 或者是升级
            } else if (isAsync() || isUpgrade() || state == SocketState.ASYNC_END) {
                // 如果是  Timeout 返回的 state 是 LONG
                state = dispatch(status);
                state = checkForPipelinedData(state, socketWrapper);
                // 如果传入的事件 是 写事件  将状态改为 long
            } else if (status == SocketEvent.OPEN_WRITE) {
                // Extra write event likely after async, ignore
                state = SocketState.LONG;
                // 如果是读事件 触发 service方法
            } else if (status == SocketEvent.OPEN_READ) {
                state = service(socketWrapper);
                // 如果是连接失败
            } else if (status == SocketEvent.CONNECT_FAIL) {
                logAccess(socketWrapper);
            } else {
                // Default to closing the socket if the SocketEvent passed in
                // is not consistent with the current state of the Processor
                state = SocketState.CLOSED;
            }

            if (getLog().isDebugEnabled()) {
                getLog().debug("Socket: [" + socketWrapper +
                        "], Status in: [" + status +
                        "], State out: [" + state + "]");
            }

            if (state != SocketState.CLOSED && isAsync()) {
                state = asyncPostProcess();
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Socket: [" + socketWrapper +
                            "], State after async post processing: [" + state + "]");
                }
            }

            // 首次会处理传入的参数 此时 dispatches 还没有初始化
            if (dispatches == null || !dispatches.hasNext()) {
                // Only returns non-null iterator if there are
                // dispatches to process.  这里初始化 dispatches  之后迭代器不为null 会处理下面所有的 dispatchType
                dispatches = getIteratorAndClearDispatches();
            }
            // 代表再次进入循环的条件
        } while (state == SocketState.ASYNC_END ||
                dispatches != null && state != SocketState.CLOSED);

        return state;
    }


    /**
     * 检查 pipeline 的数据 实际上就是调用 service(socketWrapper)
     * @param inState
     * @param socketWrapper
     * @return
     * @throws IOException
     */
    private SocketState checkForPipelinedData(SocketState inState, SocketWrapperBase<?> socketWrapper)
            throws IOException {
        if (inState == SocketState.OPEN) {
            // There may be pipe-lined data to read. If the data isn't
            // processed now, execution will exit this loop and call
            // release() which will recycle the processor (and input
            // buffer) deleting any pipe-lined data. To avoid this,
            // process it now.
            return service(socketWrapper);
        } else {
            return inState;
        }
    }


    /**
     * 将某个待处理请求 添加到 set 中
     * @param dispatchType
     */
    public void addDispatch(DispatchType dispatchType) {
        synchronized (dispatches) {
            dispatches.add(dispatchType);
        }
    }


    /**
     * 获取 迭代器对象
     * @return
     */
    public Iterator<DispatchType> getIteratorAndClearDispatches() {
        // Note: Logic in AbstractProtocol depends on this method only returning
        // a non-null value if the iterator is non-empty. i.e. it should never
        // return an empty iterator.
        Iterator<DispatchType> result;
        synchronized (dispatches) {
            // Synchronized as the generation of the iterator and the clearing
            // of dispatches needs to be an atomic operation.
            result = dispatches.iterator();
            // 返回迭代器的同时将 dispatches 置空 这样其他线程就 获取不到迭代器
            if (result.hasNext()) {
                dispatches.clear();
            } else {
                result = null;
            }
        }
        return result;
    }


    /**
     * 当 异步处理完成时 会清空内部所有的 分发对象
     */
    protected void clearDispatches() {
        synchronized (dispatches) {
            dispatches.clear();
        }
    }


    /**
     * Add an entry to the access log for a failed connection attempt.
     *
     * @param socketWrapper The connection to process
     *
     * @throws IOException If an I/O error occurs during the processing of the
     *         request
     */
    protected void logAccess(SocketWrapperBase<?> socketWrapper) throws IOException {
        // NO-OP by default
    }


    /**
     * Service a 'standard' HTTP request. This method is called for both new
     * requests and for requests that have partially read the HTTP request line
     * or HTTP headers. Once the headers have been fully read this method is not
     * called again until there is a new HTTP request to process. Note that the
     * request type may change during processing which may result in one or more
     * calls to {@link #dispatch(SocketEvent)}. Requests may be pipe-lined.
     *
     * @param socketWrapper The connection to process
     *
     * @return The state the caller should put the socket in when this method
     *         returns
     *
     * @throws IOException If an I/O error occurs during the processing of the
     *         request
     */
    protected abstract SocketState service(SocketWrapperBase<?> socketWrapper) throws IOException;

    /**
     * Process an in-progress request that is not longer in standard HTTP mode.
     * Uses currently include Servlet 3.0 Async and HTTP upgrade connections.
     * Further uses may be added in the future. These will typically start as
     * HTTP requests.
     *
     * @param status The event to process
     *
     * @return The state the caller should put the socket in when this method
     *         returns
     *
     * @throws IOException If an I/O error occurs during the processing of the
     *         request
     */
    protected abstract SocketState dispatch(SocketEvent status) throws IOException;

    protected abstract SocketState asyncPostProcess();

    protected abstract Log getLog();
}
