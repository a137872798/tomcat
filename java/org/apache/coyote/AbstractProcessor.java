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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.RequestDispatcher;

import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.parser.Host;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * Provides functionality and attributes common to all supported protocols
 * (currently HTTP and AJP) for processing a single request/response.
 */
public abstract class AbstractProcessor extends AbstractProcessorLight implements ActionHook {

    private static final StringManager sm = StringManager.getManager(AbstractProcessor.class);

    // Used to avoid useless B2C conversion on the host name.
    // 这里额外保存了一份 hostChar 数组
    protected char[] hostNameC = new char[0];

    /**
     * processor 内部携带一个 adapter 对象 用于处理请求
     */
    protected Adapter adapter;
    /**
     * 异步状态机
     */
    protected final AsyncStateMachine asyncStateMachine;
    private volatile long asyncTimeout = -1;
    /*
     * Tracks the current async generation when a timeout is dispatched. In the
     * time it takes for a container thread to be allocated and the timeout
     * processing to start, it is possible that the application completes this
     * generation of async processing and starts a new one. If the timeout is
     * then processed against the new generation, response mix-up can occur.
     * This field is used to ensure that any timeout event processed is for the
     * current async generation. This prevents the response mix-up.
     */
    private volatile long asyncTimeoutGeneration = 0;
    /**
     * 内部 携带端点对象  请求就是从这里生成的
     */
    protected final AbstractEndpoint<?> endpoint;
    // coyote 内部的 req res 对象
    protected final Request request;
    protected final Response response;
    /**
     * 套接字包装对象
     */
    protected volatile SocketWrapperBase<?> socketWrapper = null;
    protected volatile SSLSupport sslSupport;


    /**
     * Error state for the request/response currently being processed.
     * 当前处理器异常状态
     */
    private ErrorState errorState = ErrorState.NONE;

    /**
     * 日志相关对象
     */
    protected final UserDataHelper userDataHelper;

    /**
     * 抽象处理器对象    这里初始化了一个 req 对象和 res 对象
     * @param endpoint
     */
    public AbstractProcessor(AbstractEndpoint<?> endpoint) {
        this(endpoint, new Request(), new Response());
    }


    /**
     * 初始化 端点对象用于监听端口 以及 req res  应该就是封装 读取到的数据
     * @param endpoint
     * @param coyoteRequest
     * @param coyoteResponse
     */
    protected AbstractProcessor(AbstractEndpoint<?> endpoint, Request coyoteRequest,
            Response coyoteResponse) {
        this.endpoint = endpoint;
        // 初始化 异步状态机对象
        asyncStateMachine = new AsyncStateMachine(this);
        request = coyoteRequest;
        response = coyoteResponse;
        // 将自身作为钩子 设置进去 实际上后面req 的很多方法最终都是转发到hook上
        response.setHook(this);
        request.setResponse(response);
        request.setHook(this);
        // 日志包装对象
        userDataHelper = new UserDataHelper(getLog());
    }


    /**
     * Update the current error state to the new error state if the new error
     * state is more severe than the current error state.
     * @param errorState The error status details
     * @param t The error which occurred
     *          在处理过程中 如果出现了异常  需要将信息设置到 res中 便于返回给client
     */
    protected void setErrorState(ErrorState errorState, Throwable t) {
        // Use the return value to avoid processing more than one async error
        // in a single async cycle.
        // 尝试通过cas 设置异常状态
        boolean setError = response.setError();
        // 如果当前异常状态是  允许 io 的  而传入的参数是不允许 io 的 那么 代表需要阻塞io  如果2个 errorState.isIoAllowed 相同 那么 代表
        // 不需要对当前状态改动(保持原样)
        boolean blockIo = this.errorState.isIoAllowed() && !errorState.isIoAllowed();
        // 相当于一个 compare 函数 返回更严重的错误对象
        this.errorState = this.errorState.getMostSevere(errorState);
        // Don't change the status code for IOException since that is almost
        // certainly a client disconnect in which case it is preferable to keep
        // the original status code http://markmail.org/message/4cxpwmxhtgnrwh7n
        // 这里将 异常code 设置成500
        if (response.getStatus() < 400 && !(t instanceof IOException)) {
            response.setStatus(500);
        }
        if (t != null) {
            // 并将异常对象 设置到attr 中 记得在 req中好像有通过该属性尝试获取req 绑定的异常的动作
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, t);
        }
        // 如果io 发生了变化 且是异步的 且 本次cas 设置 error标识成功
        if (blockIo && isAsync() && setError) {
            if (asyncStateMachine.asyncError()) {
                processSocketEvent(SocketEvent.ERROR, true);
            }
        }
    }


    protected ErrorState getErrorState() {
        return errorState;
    }


    @Override
    public Request getRequest() {
        return request;
    }


    /**
     * Set the associated adapter.
     *
     * @param adapter the new adapter
     */
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }


    /**
     * Get the associated adapter.
     *
     * @return the associated adapter
     */
    public Adapter getAdapter() {
        return adapter;
    }


    /**
     * Set the socket wrapper being used.
     * @param socketWrapper The socket wrapper
     */
    protected void setSocketWrapper(SocketWrapperBase<?> socketWrapper) {
        this.socketWrapper = socketWrapper;
    }


    /**
     * @return the socket wrapper being used.
     */
    protected final SocketWrapperBase<?> getSocketWrapper() {
        return socketWrapper;
    }


    @Override
    public final void setSslSupport(SSLSupport sslSupport) {
        this.sslSupport = sslSupport;
    }


    /**
     * @return the Executor used by the underlying endpoint.
     */
    protected Executor getExecutor() {
        return endpoint.getExecutor();
    }

    // 异步相关状态的方法 实际上都是转发给 asyncStateMachine 实现的

    @Override
    public boolean isAsync() {
        return asyncStateMachine.isAsync();
    }


    @Override
    public SocketState asyncPostProcess() {
        return asyncStateMachine.asyncPostProcess();
    }


    /**
     * 重写父类的 转发方法
     * @param status The event to process
     *
     * @return
     * @throws IOException
     */
    @Override
    public final SocketState dispatch(SocketEvent status) throws IOException {

        // 如果本次尝试的是写入操作 且写监听器不为空
        if (status == SocketEvent.OPEN_WRITE && response.getWriteListener() != null) {
            // 进行异步操作
            asyncStateMachine.asyncOperation();
            try {
                // 如果数据成功刷盘 返回一个 LONG 状态
                if (flushBufferedWrite()) {
                    return SocketState.LONG;
                }
            } catch (IOException ioe) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Unable to write async data.", ioe);
                }
                // 代表处理过程中出现了异常 这里将异常设置到 req 上
                status = SocketEvent.ERROR;
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, ioe);
            }
            // 如果传入的是读事件  且 监听器不为空
        } else if (status == SocketEvent.OPEN_READ && request.getReadListener() != null) {
            // 处理非阻塞读
            dispatchNonBlockingRead();
            // 如果本次事件是异常
        } else if (status == SocketEvent.ERROR) {
            // An I/O error occurred on a non-container thread. This includes:
            // - read/write timeouts fired by the Poller (NIO & APR)
            // - completion handler failures in NIO2

            // 如果异常还没有被设置
            if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) == null) {
                // Because the error did not occur on a container thread the
                // request's error attribute has not been set. If an exception
                // is available from the socketWrapper, use it to set the
                // request's error attribute here so it is visible to the error
                // handling.
                // 这里才进行设置 也就是 之前的异常优先级会高一些
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, socketWrapper.getError());
            }

            // 如果读写监听器不为空
            if (request.getReadListener() != null || response.getWriteListener() != null) {
                // The error occurred during non-blocking I/O. Set the correct
                // state else the error handling will trigger an ISE.
                // 触发异步操作
                asyncStateMachine.asyncOperation();
            }
        }

        // 获取请求统计对象
        RequestInfo rp = request.getRequestProcessor();
        try {
            // 设置当前处在准备调用service 的步骤
            rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
            // 如果异步分发失败了 设置errorState
            if (!getAdapter().asyncDispatch(request, response, status)) {
                setErrorState(ErrorState.CLOSE_NOW, null);
            }
        } catch (InterruptedIOException e) {
            setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            setErrorState(ErrorState.CLOSE_NOW, t);
            getLog().error(sm.getString("http11processor.request.process"), t);
        }

        // 代表本次处理结束了 也就是现在 tomcat 默认都是异步处理的???
        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        SocketState state;

        // 如果当前出现异常 本次处理结束 将处理req的相关信息填充到requestInfo 中
        if (getErrorState().isError()) {
            request.updateCounters();
            // 代表当前已经结束处理
            state = SocketState.CLOSED;
            // 如果当前正在异步处理中 返回状态为 LONG  好像是长轮询的意思
        } else if (isAsync()) {
            state = SocketState.LONG;
        } else {
            // 正常处理 统计req 信息 以及触发一个 endRequest
            request.updateCounters();
            // 该方法子类实现
            state = dispatchEndRequest();
        }

        if (getLog().isDebugEnabled()) {
            getLog().debug("Socket: [" + socketWrapper +
                    "], Status in: [" + status +
                    "], State out: [" + state + "]");
        }

        return state;
    }


    /**
     * 解析 host 数据
     * @param valueMB
     */
    protected void parseHost(MessageBytes valueMB) {
        if (valueMB == null || valueMB.isNull()) {
            // 如果参数为null 填充host  port  只有 AJP 有默认实现
            populateHost();
            populatePort();
            return;
            // 如果数据长度为0  这里设置 serverName 为 ""
        } else if (valueMB.getLength() == 0) {
            // Empty Host header so set sever name to empty string
            request.serverName().setString("");
            populatePort();
            return;
        }

        // 默认情况下 host 已经从socket中的数据流读取出来了 不过此时是byte[] 数组 这里要转换成 char数组

        // 将数据 以 byte[] 的形式 获取
        ByteChunk valueBC = valueMB.getByteChunk();
        byte[] valueB = valueBC.getBytes();
        int valueL = valueBC.getLength();
        int valueS = valueBC.getStart();
        // 如果host 数组太小 进行扩容
        if (hostNameC.length < valueL) {
            hostNameC = new char[valueL];
        }

        try {
            // Validates the host name
            // 找到 host 中 ： 的位置
            int colonPos = Host.parse(valueMB);

            // Extract the port information first, if any
            // 代表找到了 : 那么 左边对应ip 右边对应端口号
            if (colonPos != -1) {
                int port = 0;
                for (int i = colonPos + 1; i < valueL; i++) {
                    char c = (char) valueB[i + valueS];
                    // 端口不能是 数字外的其他字符
                    if (c < '0' || c > '9') {
                        response.setStatus(400);
                        setErrorState(ErrorState.CLOSE_CLEAN, null);
                        return;
                    }
                    // 相当于 正向 读取 越先读取到的数字 就是后一位的10倍
                    port = port * 10 + c - '0';
                }
                request.setServerPort(port);

                // Only need to copy the host name up to the :
                // 针对存在 : 的情况 这里只要获取 左边的ip 就可以了
                valueL = colonPos;
            }

            // Extract the host name
            // 如果存在端口 只要获取ip的部分 否则将host 对应的整个字符串 设置到 host 中
            for (int i = 0; i < valueL; i++) {
                hostNameC[i] = (char) valueB[i + valueS];
            }
            // 设置 serverName 也就是 serverName 默认 等同于host
            request.serverName().setChars(hostNameC, 0, valueL);

            // 当出现异常时 打印日志 并设置异常状态为400
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException indicates that the host name is invalid
            UserDataHelper.Mode logMode = userDataHelper.getNextMode();
            if (logMode != null) {
                String message = sm.getString("abstractProcessor.hostInvalid", valueMB.toString());
                switch (logMode) {
                    case INFO_THEN_DEBUG:
                        message += sm.getString("abstractProcessor.fallToDebug");
                        //$FALL-THROUGH$
                    case INFO:
                        getLog().info(message, e);
                        break;
                    case DEBUG:
                        getLog().debug(message, e);
                }
            }

            response.setStatus(400);
            setErrorState(ErrorState.CLOSE_CLEAN, e);
        }
    }


    /**
     * Called when a host header is not present in the request (e.g. HTTP/1.0).
     * It populates the server name with appropriate information. The source is
     * expected to vary by protocol.
     * <p>
     * The default implementation is a NO-OP.
     */
    protected void populateHost() {
        // NO-OP
    }


    /**
     * Called when a host header is not present or is empty in the request (e.g.
     * HTTP/1.0). It populates the server port with appropriate information. The
     * source is expected to vary by protocol.
     * <p>
     * The default implementation is a NO-OP.
     */
    protected void populatePort() {
        // NO-OP
    }


    /**
     * 实现了 ActionHook 接口  能够处理req res 中的一些方法
     * @param actionCode Type of the action   代表本次各种事件
     * @param param Action parameter  触发动作时使用的参数
     */
    @Override
    public final void action(ActionCode actionCode, Object param) {
        switch (actionCode) {
        // 'Normal' servlet support
        // 如果本次是提交事件  注意这里没有使用 param
        case COMMIT: {
            // 首先确保 res 还没有提交过 否则就不需要重复处理了
            if (!response.isCommitted()) {
                try {
                    // Validate and write response headers
                    // 准备res 对象  该方法由子类实现 也就是由 对应的 connector 来做  (不同connector 使用的协议也不同)
                    // 此时res 对象还没有写入到  OS 的 网络io 中
                    prepareResponse();
                } catch (IOException e) {
                    setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                }
            }
            break;
        }
        // 如果是关闭事件 实际上也是触发了commit 事件
        case CLOSE: {
            // 当内部 方法执行完后 已经准备好res 对象
            action(ActionCode.COMMIT, null);
            try {
                // 开始真正发送数据体并准备关闭本次连接
                finishResponse();
                // 出现异常时设置 errorState 状态
            } catch (CloseNowException cne) {
                setErrorState(ErrorState.CLOSE_NOW, cne);
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            }
            break;
        }
        // 代表触发了一个确认动作
        case ACK: {
            ack();
            break;
        }
        // 将结果写入到网络IO 中
        case CLIENT_FLUSH: {
            action(ActionCode.COMMIT, null);
            try {
                // 由子类实现
                flush();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                response.setErrorException(e);
            }
            break;
        }
        // 将请求变成可用状态 什么是可用??? 可用意味着什么
        case AVAILABLE: {
            request.setAvailable(available(Boolean.TRUE.equals(param)));
            break;
        }
        // 设置 请求体body  就是将参数转换成 body
        case REQ_SET_BODY_REPLAY: {
            ByteChunk body = (ByteChunk) param;
            setRequestBody(body);
            break;
        }

        // Error handling  尝试判断当前是否已经产生了异常
        case IS_ERROR: {
            ((AtomicBoolean) param).set(getErrorState().isError());
            break;
        }
        // 是否允许网络io
        case IS_IO_ALLOWED: {
            // 通过 errorState 来判断是否允许网络io
            ((AtomicBoolean) param).set(getErrorState().isIoAllowed());
            break;
        }
        // 立即关闭 不会转发到 action(COMMIT)
        case CLOSE_NOW: {
            // Prevent further writes to the response   应该是设置成不允许写入res   因为本次请求已经被完全关闭了
            setSwallowResponse();
            // 设置当前异常状态 为 CLOSE_NOW
            if (param instanceof Throwable) {
                setErrorState(ErrorState.CLOSE_NOW, (Throwable) param);
            } else {
                setErrorState(ErrorState.CLOSE_NOW, null);
            }
            break;
        }

        // 无法输入
        case DISABLE_SWALLOW_INPUT: {
            // Aborted upload or similar.
            // No point reading the remainder of the request.
            // 设置成 无法输入
            disableSwallowRequest();
            // This is an error state. Make sure it is marked as such.
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            break;
        }

        // Request attribute support   本事件 代表往 req 上设置 远端地址
        case REQ_HOST_ADDR_ATTRIBUTE: {
            if (getPopulateRequestAttributesFromSocket() && socketWrapper != null) {
                request.remoteAddr().setString(socketWrapper.getRemoteAddr());
            }
            break;
        }
        // 往 req 中设置 host 属性
        case REQ_HOST_ATTRIBUTE: {
            populateRequestAttributeRemoteHost();
            break;
        }
        // 往 req 中设置 localPort 属性
        case REQ_LOCALPORT_ATTRIBUTE: {
            if (getPopulateRequestAttributesFromSocket() && socketWrapper != null) {
                request.setLocalPort(socketWrapper.getLocalPort());
            }
            break;
        }
        // 设置本地地址
        case REQ_LOCAL_ADDR_ATTRIBUTE: {
            if (getPopulateRequestAttributesFromSocket() && socketWrapper != null) {
                request.localAddr().setString(socketWrapper.getLocalAddr());
            }
            break;
        }
        case REQ_LOCAL_NAME_ATTRIBUTE: {
            if (getPopulateRequestAttributesFromSocket() && socketWrapper != null) {
                request.localName().setString(socketWrapper.getLocalName());
            }
            break;
        }
        case REQ_REMOTEPORT_ATTRIBUTE: {
            if (getPopulateRequestAttributesFromSocket() && socketWrapper != null) {
                request.setRemotePort(socketWrapper.getRemotePort());
            }
            break;
        }

        // SSL request attribute support
        case REQ_SSL_ATTRIBUTE: {
            populateSslRequestAttributes();
            break;
        }
        case REQ_SSL_CERTIFICATE: {
            try {
                sslReHandShake();
            } catch (IOException ioe) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, ioe);
            }
            break;
        }

        // Servlet 3.0 asynchronous support
        case ASYNC_START: {
            asyncStateMachine.asyncStart((AsyncContextCallback) param);
            break;
        }
        case ASYNC_COMPLETE: {
            clearDispatches();
            if (asyncStateMachine.asyncComplete()) {
                processSocketEvent(SocketEvent.OPEN_READ, true);
            }
            break;
        }
        case ASYNC_DISPATCH: {
            if (asyncStateMachine.asyncDispatch()) {
                processSocketEvent(SocketEvent.OPEN_READ, true);
            }
            break;
        }
        case ASYNC_DISPATCHED: {
            asyncStateMachine.asyncDispatched();
            break;
        }
        case ASYNC_ERROR: {
            asyncStateMachine.asyncError();
            break;
        }
        case ASYNC_IS_ASYNC: {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsync());
            break;
        }
        case ASYNC_IS_COMPLETING: {
            ((AtomicBoolean) param).set(asyncStateMachine.isCompleting());
            break;
        }
        case ASYNC_IS_DISPATCHING: {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncDispatching());
            break;
        }
        case ASYNC_IS_ERROR: {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncError());
            break;
        }
        case ASYNC_IS_STARTED: {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncStarted());
            break;
        }
        case ASYNC_IS_TIMINGOUT: {
            ((AtomicBoolean) param).set(asyncStateMachine.isAsyncTimingOut());
            break;
        }
        case ASYNC_RUN: {
            asyncStateMachine.asyncRun((Runnable) param);
            break;
        }
        case ASYNC_SETTIMEOUT: {
            if (param == null) {
                return;
            }
            long timeout = ((Long) param).longValue();
            setAsyncTimeout(timeout);
            break;
        }
        case ASYNC_TIMEOUT: {
            AtomicBoolean result = (AtomicBoolean) param;
            result.set(asyncStateMachine.asyncTimeout());
            break;
        }
        case ASYNC_POST_PROCESS: {
            asyncStateMachine.asyncPostProcess();
            break;
        }

        // Servlet 3.1 non-blocking I/O
        case REQUEST_BODY_FULLY_READ: {
            AtomicBoolean result = (AtomicBoolean) param;
            result.set(isRequestBodyFullyRead());
            break;
        }
        case NB_READ_INTEREST: {
            AtomicBoolean isReady = (AtomicBoolean)param;
            isReady.set(isReadyForRead());
            break;
        }
        case NB_WRITE_INTEREST: {
            AtomicBoolean isReady = (AtomicBoolean)param;
            isReady.set(isReadyForWrite());
            break;
        }
        case DISPATCH_READ: {
            addDispatch(DispatchType.NON_BLOCKING_READ);
            break;
        }
        case DISPATCH_WRITE: {
            addDispatch(DispatchType.NON_BLOCKING_WRITE);
            break;
        }
        case DISPATCH_EXECUTE: {
            executeDispatches();
            break;
        }

        // Servlet 3.1 HTTP Upgrade
        case UPGRADE: {
            doHttpUpgrade((UpgradeToken) param);
            break;
        }

        // Servlet 4.0 Push requests
        case IS_PUSH_SUPPORTED: {
            AtomicBoolean result = (AtomicBoolean) param;
            result.set(isPushSupported());
            break;
        }
        case PUSH_REQUEST: {
            doPush((Request) param);
            break;
        }
        }
    }


    /**
     * Perform any necessary processing for a non-blocking read before
     * dispatching to the adapter.
     */
    protected void dispatchNonBlockingRead() {
        asyncStateMachine.asyncOperation();
    }


    /**
     * {@inheritDoc}
     * <p>
     * Sub-classes of this base class represent a single request/response pair.
     * The timeout to be processed is, therefore, the Servlet asynchronous
     * processing timeout.
     * 判断是否超时  注意 在protocolHandler 中有个定时任务 就是定期扫描所有的processor 并调用该方法
     */
    @Override
    public void timeoutAsync(long now) {
        // 如果传入 的时间戳为负数 直接触发超时事件
        if (now < 0) {
            // 通过socketWrapper 处理一个超时事件
            doTimeoutAsync();
        } else {
            // 获取设置的异步超时时间
            long asyncTimeout = getAsyncTimeout();
            if (asyncTimeout > 0) {
                // 获取上一个触发的异步任务
                long asyncStart = asyncStateMachine.getLastAsyncStart();
                // 代表直到此时 异步任务已经超时了
                if ((now - asyncStart) > asyncTimeout) {
                    // 就是用 socketWrapper 触发一个超时事件
                    doTimeoutAsync();
                }
                // 这里代表 超时时间为 负数 或者为 0   那么需要判断 状态机当前是否不可用 如果不可用的情况  也要触发超时
            } else if (!asyncStateMachine.isAvailable()) {
                // Timeout the async process if the associated web application
                // is no longer running.
                doTimeoutAsync();
            }
        }
    }


    private void doTimeoutAsync() {
        // Avoid multiple timeouts
        // 代表本次调用已经超时了吧
        setAsyncTimeout(-1);
        // 获取 当前异步状态机的 序号 TODO  好像每开始一个新的异步任务就会加1
        asyncTimeoutGeneration = asyncStateMachine.getCurrentGeneration();
        // 处理超时事件
        processSocketEvent(SocketEvent.TIMEOUT, true);
    }


    /**
     * 判断是否发生过超时  也就是 当前asyncTimeoutGeneration 是否更新成 跟异步状态机内部的 generation 一致
     * 这一步会在 doTimeoutAsync() 中触发
     * @return
     */
    @Override
    public boolean checkAsyncTimeoutGeneration() {
        return asyncTimeoutGeneration == asyncStateMachine.getCurrentGeneration();
    }


    public void setAsyncTimeout(long timeout) {
        asyncTimeout = timeout;
    }


    public long getAsyncTimeout() {
        return asyncTimeout;
    }


    /**
     * 将对象进行回收 方便重用
     */
    @Override
    public void recycle() {
        errorState = ErrorState.NONE;
        asyncStateMachine.recycle();
    }


    protected abstract void prepareResponse() throws IOException;


    protected abstract void finishResponse() throws IOException;


    protected abstract void ack();


    protected abstract void flush() throws IOException;


    protected abstract int available(boolean doRead);


    protected abstract void setRequestBody(ByteChunk body);


    protected abstract void setSwallowResponse();


    protected abstract void disableSwallowRequest();


    /**
     * Processors that populate request attributes directly (e.g. AJP) should
     * over-ride this method and return {@code false}.
     *
     * @return {@code true} if the SocketWrapper should be used to populate the
     *         request attributes, otherwise {@code false}.
     */
    protected boolean getPopulateRequestAttributesFromSocket() {
        return true;
    }


    /**
     * Populate the remote host request attribute. Processors (e.g. AJP) that
     * populate this from an alternative source should override this method.
     * 填充远端地址 就是从 socketWrapper 中抽取对应属性
     */
    protected void populateRequestAttributeRemoteHost() {
        if (getPopulateRequestAttributesFromSocket() && socketWrapper != null) {
            request.remoteHost().setString(socketWrapper.getRemoteHost());
        }
    }


    /**
     * Populate the TLS related request attributes from the {@link SSLSupport}
     * instance associated with this processor. Protocols that populate TLS
     * attributes from a different source (e.g. AJP) should override this
     * method.
     */
    protected void populateSslRequestAttributes() {
        try {
            if (sslSupport != null) {
                Object sslO = sslSupport.getCipherSuite();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.CIPHER_SUITE_KEY, sslO);
                }
                sslO = sslSupport.getPeerCertificateChain();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
                }
                sslO = sslSupport.getKeySize();
                if (sslO != null) {
                    request.setAttribute (SSLSupport.KEY_SIZE_KEY, sslO);
                }
                sslO = sslSupport.getSessionId();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.SESSION_ID_KEY, sslO);
                }
                sslO = sslSupport.getProtocol();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.PROTOCOL_VERSION_KEY, sslO);
                }
                request.setAttribute(SSLSupport.SESSION_MGR, sslSupport);
            }
        } catch (Exception e) {
            getLog().warn(sm.getString("abstractProcessor.socket.ssl"), e);
        }
    }


    /**
     * Processors that can perform a TLS re-handshake (e.g. HTTP/1.1) should
     * override this method and implement the re-handshake.
     *
     * @throws IOException If authentication is required then there will be I/O
     *                     with the client and this exception will be thrown if
     *                     that goes wrong
     */
    protected void sslReHandShake() throws IOException {
        // NO-OP
    }


    /**
     * 通过socketWrapper 处理 事件
     * @param event
     * @param dispatch
     */
    protected void processSocketEvent(SocketEvent event, boolean dispatch) {
        SocketWrapperBase<?> socketWrapper = getSocketWrapper();
        if (socketWrapper != null) {
            socketWrapper.processSocket(event, dispatch);
        }
    }


    /**
     * 当前是否已经准备好读事件
     * @return
     */
    protected boolean isReadyForRead() {
        // 如果能读取数据 返回 true
        if (available(true) > 0) {
            return true;
        }

        // 如果 请求体还没有填满readBuffer 那么将读事件注册到selector 上
        if (!isRequestBodyFullyRead()) {
            registerReadInterest();
        }

        return false;
    }


    protected abstract boolean isRequestBodyFullyRead();


    protected abstract void registerReadInterest();


    protected abstract boolean isReadyForWrite();


    /**
     * 执行分发任务
     */
    protected void executeDispatches() {
        // 首先获取 socket包装对象
        SocketWrapperBase<?> socketWrapper = getSocketWrapper();
        // 获取 当前所有待处理的 DispatchType
        Iterator<DispatchType> dispatches = getIteratorAndClearDispatches();
        if (socketWrapper != null) {
            synchronized (socketWrapper) {
                /*
                 * This method is called when non-blocking IO is initiated by defining
                 * a read and/or write listener in a non-container thread. It is called
                 * once the non-container thread completes so that the first calls to
                 * onWritePossible() and/or onDataAvailable() as appropriate are made by
                 * the container.
                 *
                 * Processing the dispatches requires (for APR/native at least)
                 * that the socket has been added to the waitingRequests queue. This may
                 * not have occurred by the time that the non-container thread completes
                 * triggering the call to this method. Therefore, the coded syncs on the
                 * SocketWrapper as the container thread that initiated this
                 * non-container thread holds a lock on the SocketWrapper. The container
                 * thread will add the socket to the waitingRequests queue before
                 * releasing the lock on the socketWrapper. Therefore, by obtaining the
                 * lock on socketWrapper before processing the dispatches, we can be
                 * sure that the socket has been added to the waitingRequests queue.
                 */
                while (dispatches != null && dispatches.hasNext()) {
                    DispatchType dispatchType = dispatches.next();
                    // 待分发的对象都是交由 socketWrapper来处理的  这里传入的 dispatch 为false
                    socketWrapper.processSocket(dispatchType.getSocketStatus(), false);
                }
            }
        }
    }


    /**
     * {@inheritDoc}
     * Processors that implement HTTP upgrade must override this method and
     * provide the necessary token.
     */
    @Override
    public UpgradeToken getUpgradeToken() {
        // Should never reach this code but in case we do...
        throw new IllegalStateException(
                sm.getString("abstractProcessor.httpupgrade.notsupported"));
    }


    /**
     * Process an HTTP upgrade. Processors that support HTTP upgrade should
     * override this method and process the provided token.
     *
     * @param upgradeToken Contains all the information necessary for the
     *                     Processor to process the upgrade
     *
     * @throws UnsupportedOperationException if the protocol does not support
     *         HTTP upgrade
     */
    protected void doHttpUpgrade(UpgradeToken upgradeToken) {
        // Should never happen
        throw new UnsupportedOperationException(
                sm.getString("abstractProcessor.httpupgrade.notsupported"));
    }


    /**
     * {@inheritDoc}
     * Processors that implement HTTP upgrade must override this method.
     */
    @Override
    public ByteBuffer getLeftoverInput() {
        // Should never reach this code but in case we do...
        throw new IllegalStateException(sm.getString("abstractProcessor.httpupgrade.notsupported"));
    }


    /**
     * {@inheritDoc}
     * Processors that implement HTTP upgrade must override this method.
     */
    @Override
    public boolean isUpgrade() {
        return false;
    }


    /**
     * Protocols that support push should override this method and return {@code
     * true}.
     *
     * @return {@code true} if push is supported by this processor, otherwise
     *         {@code false}.
     */
    protected boolean isPushSupported() {
        return false;
    }


    /**
     * Process a push. Processors that support push should override this method
     * and process the provided token.
     *
     * @param pushTarget Contains all the information necessary for the Processor
     *                   to process the push request
     *
     * @throws UnsupportedOperationException if the protocol does not support
     *         push
     */
    protected void doPush(Request pushTarget) {
        throw new UnsupportedOperationException(
                sm.getString("abstractProcessor.pushrequest.notsupported"));
    }


    /**
     * Flush any pending writes. Used during non-blocking writes to flush any
     * remaining data from a previous incomplete write.
     *
     * @return <code>true</code> if data remains to be flushed at the end of
     *         method
     *
     * @throws IOException If an I/O error occurs while attempting to flush the
     *         data
     */
    protected abstract boolean flushBufferedWrite() throws IOException ;


    /**
     * Perform any necessary clean-up processing if the dispatch resulted in the
     * completion of processing for the current request.
     *
     * @return The state to return for the socket once the clean-up for the
     *         current request has completed
     *
     * @throws IOException If an I/O error occurs while attempting to end the
     *         request
     */
    protected abstract SocketState dispatchEndRequest() throws IOException;


    @Override
    protected final void logAccess(SocketWrapperBase<?> socketWrapper) throws IOException {
        // Set the socket wrapper so the access log can read the socket related
        // information (e.g. client IP)
        setSocketWrapper(socketWrapper);
        // Setup the minimal request information
        request.setStartTime(System.currentTimeMillis());
        // Setup the minimal response information
        response.setStatus(400);
        response.setError();
        getAdapter().log(request, response, 0);
    }
}
