/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.connector;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.SessionTrackingMode;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.apache.catalina.core.AsyncContextImpl;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.SessionConfig;
import org.apache.catalina.util.URLEncoder;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Adapter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.ServerCookie;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;


/**
 * Implementation of a request processor which delegates the processing to a
 * Coyote processor.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * coyote 处理器
 */
public class CoyoteAdapter implements Adapter {

    private static final Log log = LogFactory.getLog(CoyoteAdapter.class);

    // -------------------------------------------------------------- Constants

    /**
     * 当前遵循的规范  servlet 3.1
     */
    private static final String POWERED_BY = "Servlet/3.1 JSP/2.3 " +
            "(" + ServerInfo.getServerInfo() + " Java/" +
            System.getProperty("java.vm.vendor") + "/" +
            System.getProperty("java.runtime.version") + ")";

    private static final EnumSet<SessionTrackingMode> SSL_ONLY =
        EnumSet.of(SessionTrackingMode.SSL);

    public static final int ADAPTER_NOTES = 1;


    /**
     * 是否支持反斜杠
     */
    protected static final boolean ALLOW_BACKSLASH =
        Boolean.parseBoolean(System.getProperty("org.apache.catalina.connector.CoyoteAdapter.ALLOW_BACKSLASH", "false"));


    /**
     * 每个线程的名字都保存在 ThreadMap 中
     */
    private static final ThreadLocal<String> THREAD_NAME =
            new ThreadLocal<String>() {

                @Override
                protected String initialValue() {
                    return Thread.currentThread().getName();
                }

    };

    // ----------------------------------------------------------- Constructors


    /**
     * Construct a new CoyoteProcessor associated with the specified connector.
     *
     * @param connector CoyoteConnector that owns this processor
     *                  使用一个指定的connector 来初始化适配器对象
     */
    public CoyoteAdapter(Connector connector) {

        super();
        this.connector = connector;

    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The CoyoteConnector with which this processor is associated.
     * 关联的connector 对象
     */
    private final Connector connector;


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(CoyoteAdapter.class);


    // -------------------------------------------------------- Adapter Methods

    /**
     * 异步分发请求
     * @param req   这里使用了 coyote 封装的 req 和 res 对象   应该是在哪个地方将 req 交由 info 对象来处理
     * @param res
     * @param status   当前要处理的事件
     * @return
     * @throws Exception
     */
    @Override
    public boolean asyncDispatch(org.apache.coyote.Request req, org.apache.coyote.Response res,
            SocketEvent status) throws Exception {

        // 通过 getNode 获取到的是另一套 req res 这个note 是什么时候设置的呢???
        // 这个应该也是为了适配吧
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);

        if (request == null) {
            throw new IllegalStateException(sm.getString("coyoteAdapter.nullRequest"));
        }

        boolean success = true;
        // 获取异步上下文对象
        AsyncContextImpl asyncConImpl = request.getAsyncContextInternal();

        // requestProcessor 实际上是一个 requestInfo对象  该对象会统计所有处理过的req处理时长  之类  同时类似于一个门面类 来操作内部属性
        // 获取当前线程维护的变量名  为什么要这么做 线程模式是怎样 ???
        req.getRequestProcessor().setWorkerThreadName(THREAD_NAME.get());

        try {
            // 判断该请求对象是否支持异步  这里会通过 hook 对象来判断 实际上就是processor 对象
            if (!request.isAsync()) {
                // Error or timeout
                // Lift any suspension (e.g. if sendError() was used by an async
                // request) to allow the response to be written to the client
                // 如果不支持 异步 这里将suspend 设置成false 是为何 ???  悬置状态意味着什么???
                response.setSuspended(false);
            }

            // 如果当前发生超时
            if (status==SocketEvent.TIMEOUT) {
                // 如果 异步对象本身没有超时 则清除 error状态
                if (!asyncConImpl.timeout()) {
                    asyncConImpl.setErrorState(null, false);
                }
                // 如果要处理的是异常事件
            } else if (status==SocketEvent.ERROR) {
                // An I/O error occurred on a non-container thread which means
                // that the socket needs to be closed so set success to false to
                // trigger a close   代表在 非container 线程 发生了一个异常
                success = false;
                // 获取异常对象
                Throwable t = (Throwable)req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                // 同时将异常属性 从 attr中移除
                req.getAttributes().remove(RequestDispatcher.ERROR_EXCEPTION);
                ClassLoader oldCL = null;
                try {
                    // 这里 先获取 当前context 关联的类加载器  context 是在 engine -> host 之后的 最下级是 wrapper
                    oldCL = request.getContext().bind(false, null);
                    // 触发异常钩子
                    if (req.getReadListener() != null) {
                        req.getReadListener().onError(t);
                    }
                    if (res.getWriteListener() != null) {
                        res.getWriteListener().onError(t);
                    }
                    // 触发完钩子后 又还原了 classLoader
                } finally {
                    request.getContext().unbind(false, oldCL);
                }
                if (t != null) {
                    asyncConImpl.setErrorState(t, true);
                }
            }

            // Check to see if non-blocking writes or reads are being used
            // isAsyncDispatching同样是通过 一个hook 处理  这里是啥意思 ???
            if (!request.isAsyncDispatching() && request.isAsync()) {
                // 获取读写监听器
                WriteListener writeListener = res.getWriteListener();
                ReadListener readListener = req.getReadListener();
                // 如果是 一个 写事件
                if (writeListener != null && status == SocketEvent.OPEN_WRITE) {
                    ClassLoader oldCL = null;
                    try {
                        oldCL = request.getContext().bind(false, null);
                        // 触发相关的钩子函数
                        res.onWritePossible();
                        // 判断本次请求是否处理完了  处理完的话就触发 readListener
                        if (request.isFinished() && req.sendAllDataReadEvent() &&
                                readListener != null) {
                            readListener.onAllDataRead();
                        }
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        writeListener.onError(t);
                        success = false;
                    } finally {
                        request.getContext().unbind(false, oldCL);
                    }
                    // 如果开启一个读事件
                } else if (readListener != null && status == SocketEvent.OPEN_READ) {
                    ClassLoader oldCL = null;
                    try {
                        oldCL = request.getContext().bind(false, null);
                        // If data is being read on a non-container thread a
                        // dispatch with status OPEN_READ will be used to get
                        // execution back on a container thread for the
                        // onAllDataRead() event. Therefore, make sure
                        // onDataAvailable() is not called in this case.
                        // 在请求没有处理完的情况下 是触发 dataAvailable() 方法
                        if (!request.isFinished()) {
                            readListener.onDataAvailable();
                        }
                        // 处理完成的情况下 触发 onAllDataRead()
                        if (request.isFinished() && req.sendAllDataReadEvent()) {
                            readListener.onAllDataRead();
                        }
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        readListener.onError(t);
                        success = false;
                    } finally {
                        request.getContext().unbind(false, oldCL);
                    }
                }
            }

            // Has an error occurred during async processing that needs to be
            // processed by the application's error page mechanism (or Tomcat's
            // if the application doesn't define one)?
            // 这里将 异步请求 通过管道进行处理  会触发一系列的阀门 并且从service 级别 传播到 host  context  wrapper
            if (!request.isAsyncDispatching() && request.isAsync() &&
                    response.isErrorReportRequired()) {
                connector.getService().getContainer().getPipeline().getFirst().invoke(
                        request, response);
            }

            // 如果是异步分发的方式 通过pipeline 进行处理
            if (request.isAsyncDispatching()) {
                connector.getService().getContainer().getPipeline().getFirst().invoke(
                        request, response);
                Throwable t = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                // 如果请求中携带异常属性 那么设置到 asyncContext中
                if (t != null) {
                    asyncConImpl.setErrorState(t, true);
                }
            }

            // 如果请求不是 异步的 那么就触发 finish 方法
            if (!request.isAsync()) {
                request.finishRequest();
                response.finishResponse();
            }

            // Check to see if the processor is in an error state. If it is,
            // bail out now.
            // 通过 hook 对象来判断本次请求是否出现了异常
            AtomicBoolean error = new AtomicBoolean(false);
            res.action(ActionCode.IS_ERROR, error);
            if (error.get()) {
                // 如果出现了异常 通过hook 处理请求
                if (request.isAsyncCompleting()) {
                    // Connection will be forcibly closed which will prevent
                    // completion happening at the usual point. Need to trigger
                    // call to onComplete() here.
                    res.action(ActionCode.ASYNC_POST_PROCESS,  null);
                }
                success = false;
            }
        } catch (IOException e) {
            success = false;
            // Ignore
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            success = false;
            log.error(sm.getString("coyoteAdapter.asyncDispatch"), t);
        } finally {
            // 处理失败的情况下 设置错误码 500  实际上 错误相关的在 mvc 框架层就已经屏蔽了
            if (!success) {
                res.setStatus(500);
            }

            // Access logging
            // 如果本次请求不是异步的  开始打印日志  异步请求 就不打印日志了吗 还是说在某个地方进行打印
            if (!success || !request.isAsync()) {
                long time = 0;
                if (req.getStartTime() != -1) {
                    time = System.currentTimeMillis() - req.getStartTime();
                }
                Context context = request.getContext();
                if (context != null) {
                    context.logAccess(request, response, time, false);
                } else {
                    log(req, res, time);
                }
            }

            // 当请求处理完毕后 清除掉 req 绑定的处理线程名 这个名字应该是有在哪里用到
            req.getRequestProcessor().setWorkerThreadName(null);
            // Recycle the wrapper request and response
            if (!success || !request.isAsync()) {
                // 更新wrapper 级别的 异常计数 同时重置属性 以便回收对象
                updateWrapperErrorCount(request, response);
                request.recycle();
                response.recycle();
            }
        }
        return success;
    }


    /**
     * 处理req res  进入到该方法时 已经是processor 解析完socket 读取到的 req 对象 并进行基本的校验后了
     * @param req The request object
     * @param res The response object
     *
     * @throws Exception
     */
    @Override
    public void service(org.apache.coyote.Request req, org.apache.coyote.Response res)
            throws Exception {

        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);

        // 如果 coyoteRequest 上绑定的 req 为空 那么开始创建请求对象 也就是 service(req,res) 本身就是整个处理的入口 在这里做req  res 的适配
        if (request == null) {
            // Create objects
            request = connector.createRequest();
            request.setCoyoteRequest(req);
            response = connector.createResponse();
            response.setCoyoteResponse(res);

            // Link objects  将req res 关联起来
            request.setResponse(response);
            response.setRequest(request);

            // Set as notes   notes 就是在这里做设置的
            req.setNote(ADAPTER_NOTES, request);
            res.setNote(ADAPTER_NOTES, response);

            // Set query string encoding
            // 设置解析 查询语句的 字符集  默认是 UTF-8
            req.getParameters().setQueryStringCharset(connector.getURICharset());
        }

        // 根据 connector 的 设置来判断是否要设置 X-Powered-By 属性 该属性可能会 暴露一些不好的东西 给用户 黑客可以靠这个做一些攻击
        if (connector.getXpoweredBy()) {
            response.addHeader("X-Powered-By", POWERED_BY);
        }

        // 是否采用异步 以及是否处理成功
        boolean async = false;
        boolean postParseSuccess = false;

        // 这里开始将当前线程 对应的name 绑定到 prcoessor 上 如果真的为了标明当前触发service 的 是哪个工作线程 为什么不用threadId 呢
        req.getRequestProcessor().setWorkerThreadName(THREAD_NAME.get());

        try {
            // Parse and set Catalina and configuration specific
            // request parameters
            // 处理post请求  应该就是解析 body
            postParseSuccess = postParseRequest(req, request, res, response);
            // 当解析成功的时候
            if (postParseSuccess) {
                //check valves if we support async   根据 pipeline 中 valve 是否支持异步来设置是否支持异步标识
                //记得 各个级别的 valve standard实现 都是支持异步处理的
                request.setAsyncSupported(
                        connector.getService().getContainer().getPipeline().isAsyncSupported());
                // Calling the container  使用 管道处理请求
                connector.getService().getContainer().getPipeline().getFirst().invoke(
                        request, response);
            }
            // 如果请求是异步的
            if (request.isAsync()) {
                async = true;
                // 同样的套路 切换classLoader 同时触发监听器 之后再还原classLoader
                ReadListener readListener = req.getReadListener();
                if (readListener != null && request.isFinished()) {
                    // Possible the all data may have been read during service()
                    // method so this needs to be checked here
                    ClassLoader oldCL = null;
                    try {
                        oldCL = request.getContext().bind(false, null);
                        if (req.sendAllDataReadEvent()) {
                            req.getReadListener().onAllDataRead();
                        }
                    } finally {
                        request.getContext().unbind(false, oldCL);
                    }
                }

                // 如果 req 对象上携带了 异常 那么就是在 pipeline 处理中产生了异常 这里就要根据是否是异步处理来设置 errorState
                Throwable throwable =
                        (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

                // If an async request was started, is not going to end once
                // this container thread finishes and an error occurred, trigger
                // the async error process
                if (!request.isAsyncCompleting() && throwable != null) {
                    request.getAsyncContextInternal().setErrorState(throwable, true);
                }
            } else {
                // 同步处理的情况 直接触发2个finish
                request.finishRequest();
                response.finishResponse();
            }

        } catch (IOException e) {
            // Ignore
        } finally {
            AtomicBoolean error = new AtomicBoolean(false);
            res.action(ActionCode.IS_ERROR, error);

            // 通过hook 处理结果
            if (request.isAsyncCompleting() && error.get()) {
                // Connection will be forcibly closed which will prevent
                // completion happening at the usual point. Need to trigger
                // call to onComplete() here.
                res.action(ActionCode.ASYNC_POST_PROCESS,  null);
                async = false;
            }

            // Access log
            // 如果是 非异步 且 解析数据成功
            if (!async && postParseSuccess) {
                // Log only if processing was invoked.
                // If postParseRequest() failed, it has already logged it.
                Context context = request.getContext();
                Host host = request.getHost();
                // If the context is null, it is likely that the endpoint was
                // shutdown, this connection closed and the request recycled in
                // a different thread. That thread will have updated the access
                // log so it is OK not to update the access log here in that
                // case.
                // The other possibility is that an error occurred early in
                // processing and the request could not be mapped to a Context.
                // Log via the host or engine in that case.
                long time = System.currentTimeMillis() - req.getStartTime();
                if (context != null) {
                    context.logAccess(request, response, time, false);
                } else if (response.isError()) {
                    if (host != null) {
                        host.logAccess(request, response, time, false);
                    } else {
                        connector.getService().getContainer().logAccess(
                                request, response, time, false);
                    }
                }
            }

            // 当处理完 且 触发了 log 相关方法后 将绑定的线程名字还原
            req.getRequestProcessor().setWorkerThreadName(null);

            // Recycle the wrapper request and response  记录错误次数 回收对象
            if (!async) {
                updateWrapperErrorCount(request, response);
                request.recycle();
                response.recycle();
            }
        }
    }


    /**
     * 更新该 wrapper 下的错误数量
     * @param request
     * @param response
     */
    private void updateWrapperErrorCount(Request request, Response response) {
        if (response.isError()) {
            Wrapper wrapper = request.getWrapper();
            if (wrapper != null) {
                wrapper.incrementErrorCount();
            }
        }
    }


    /**
     * 准备阶段  啥意思
     * @param req The request object
     * @param res The response object
     *
     * @return
     * @throws IOException
     * @throws ServletException
     */
    @Override
    public boolean prepare(org.apache.coyote.Request req, org.apache.coyote.Response res)
            throws IOException, ServletException {
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);

        // 准备阶段就是 解析 post 携带的  body
        return postParseRequest(req, request, res, response);
    }


    /**
     * 保留日志
     * @param req
     * @param res
     * @param time
     */
    @Override
    public void log(org.apache.coyote.Request req,
            org.apache.coyote.Response res, long time) {

        // 在最开始的 service() 方法中已经生成了 req res 对象 并绑定到了 coyoteRequest  coyoteResponse 上
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);

        // 这里如果没有获取到绑定对象 又创建并 绑定了一次
        if (request == null) {
            // Create objects
            request = connector.createRequest();
            request.setCoyoteRequest(req);
            response = connector.createResponse();
            response.setCoyoteResponse(res);

            // Link objects
            request.setResponse(response);
            response.setRequest(request);

            // Set as notes
            req.setNote(ADAPTER_NOTES, request);
            res.setNote(ADAPTER_NOTES, response);

            // Set query string encoding
            req.getParameters().setQueryStringCharset(connector.getURICharset());
        }

        try {
            // Log at the lowest level available. logAccess() will be
            // automatically called on parent containers.
            // 这里就是调用 logAccess 方法
            boolean logged = false;
            Context context = request.mappingData.context;
            Host host = request.mappingData.host;
            if (context != null) {
                logged = true;
                context.logAccess(request, response, time, true);
            } else if (host != null) {
                logged = true;
                host.logAccess(request, response, time, true);
            }
            if (!logged) {
                connector.getService().getContainer().logAccess(request, response, time, true);
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.warn(sm.getString("coyoteAdapter.accesslogFail"), t);
        } finally {
            updateWrapperErrorCount(request, response);
            request.recycle();
            response.recycle();
        }
    }


    /**
     * 循环请求异常
     */
    private static class RecycleRequiredException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 检查是否发生了循环请求
     * @param req
     *            Request
     * @param res
     */
    @Override
    public void checkRecycled(org.apache.coyote.Request req,
            org.apache.coyote.Response res) {
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);
        String messageKey = null;
        // 代表请求对象已经被设置
        if (request != null && request.getHost() != null) {
            messageKey = "coyoteAdapter.checkRecycled.request";
        } else if (response != null && response.getContentWritten() != 0) {
            messageKey = "coyoteAdapter.checkRecycled.response";
        }
        // 只要进入到这里 最后都会触发 循环异常
        if (messageKey != null) {
            // Log this request, as it has probably skipped the access log.
            // The log() method will take care of recycling.
            log(req, res, 0L);

            // 如果conector 当前是可用的 就抛出 循环异常 该方法是什么时候调用的???
            if (connector.getState().isAvailable()) {
                if (log.isInfoEnabled()) {
                    log.info(sm.getString(messageKey),
                            new RecycleRequiredException());
                }
            } else {
                // There may be some aborted requests.
                // When connector shuts down, the request and response will not
                // be reused, so there is no issue to warn about here.
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString(messageKey),
                            new RecycleRequiredException());
                }
            }
        }
    }


    @Override
    public String getDomain() {
        return connector.getDomain();
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Perform the necessary processing after the HTTP headers have been parsed
     * to enable the request/response pair to be passed to the start of the
     * container pipeline for processing.
     *
     * @param req      The coyote request object
     * @param request  The catalina request object
     * @param res      The coyote response object
     * @param response The catalina response object
     *
     * @return <code>true</code> if the request should be passed on to the start
     *         of the container pipeline, otherwise <code>false</code>
     *
     * @throws IOException If there is insufficient space in a buffer while
     *                     processing headers
     * @throws ServletException If the supported methods of the target servlet
     *                          cannot be determined
     *                          从参数中解析 post 相关的body (数据体)   请求头 和 请求行 在 Http11InputBuffer 中已经完成处理了
     */
    protected boolean postParseRequest(org.apache.coyote.Request req, Request request,
            org.apache.coyote.Response res, Response response) throws IOException, ServletException {

        // If the processor has set the scheme (AJP does this, HTTP does this if
        // SSL is enabled) use this to set the secure flag as well. If the
        // processor hasn't set it, use the settings from the connector
        // 将协议信息设置到 req 中 默认就是 "http"
        if (req.scheme().isNull()) {
            // Use connector scheme and secure configuration, (defaults to
            // "http" and false respectively)
            req.scheme().setString(connector.getScheme());
            request.setSecure(connector.getSecure());
        } else {
            // Use processor specified scheme to determine secure state
            // 如果已经设置了 协议 只要看是否是 https 就能知道是否加密
            request.setSecure(req.scheme().equals("https"));
        }

        // At this point the Host header has been processed.
        // Override if the proxyPort/proxyHost are set
        String proxyName = connector.getProxyName();
        int proxyPort = connector.getProxyPort();
        // 如果有代理端口 设置代理端口到 req 中
        if (proxyPort != 0) {
            req.setServerPort(proxyPort);
        } else if (req.getServerPort() == -1) {
            // Not explicitly set. Use default ports based on the scheme
            // 这里规定了默认的端口号  一般情况下 tomcat Http11Processor 是使用 socket.localPort 作为 端口号的  ajp协议可能不会设置端口号
            if (req.scheme().equals("https")) {
                req.setServerPort(443);
            } else {
                req.setServerPort(80);
            }
        }
        if (proxyName != null) {
            req.serverName().setString(proxyName);
        }

        MessageBytes undecodedURI = req.requestURI();

        // Check for ping OPTIONS * request
        // 获取本次请求的 url 如果是 *
        if (undecodedURI.equals("*")) {
            if (req.method().equalsIgnoreCase("OPTIONS")) {
                StringBuilder allow = new StringBuilder();
                allow.append("GET, HEAD, POST, PUT, DELETE, OPTIONS");
                // Trace if allowed
                if (connector.getAllowTrace()) {
                    allow.append(", TRACE");
                }
                // 这里设置的 allow 属性是什么意思
                res.setHeader("Allow", allow.toString());
                // Access log entry as processing won't reach AccessLogValve
                connector.getService().getContainer().logAccess(request, response, 0, true);
                return false;
            } else {
                // 如果本次请求方式 不是 OPTIONS 那么直接抛出异常 提示非法 url
                response.sendError(400, "Invalid URI");
            }
        }

        // 获取已经解码后的url  req 在被 adapter处理前就解码了 那么就是在 protocolHandler 做的处理
        MessageBytes decodedURI = req.decodedURI();

        // 代表解析成了 byte[]
        if (undecodedURI.getType() == MessageBytes.T_BYTES) {
            // Copy the raw URI to the decodedURI
            // 将 解析后的 url 设置到 req.requestURI() 中
            decodedURI.duplicate(undecodedURI);

            // Parse the path parameters. This will:
            //   - strip out the path parameters
            //   - convert the decodedURI to bytes
            // 准备解析参数
            parsePathParameters(req, request);

            // URI decoding
            // %xx decoding of the URL
            try {
                // 将 decodedURL 转换 这里是在做什么 ???
                req.getURLDecoder().convert(decodedURI, false);
            } catch (IOException ioe) {
                // 解析失败时 以异常方式返回给 client
                response.sendError(400, "Invalid URI: " + ioe.getMessage());
            }
            // Normalization  将 decodedURL 做一般化处理  实际上就是 将 反斜杠 双斜杠之类的还原
            if (normalize(req.decodedURI())) {
                // Character decoding   转换decodedURL
                convertURI(decodedURI, request);
                // Check that the URI is still normalized  如果校验失败则抛出异常
                if (!checkNormalize(req.decodedURI())) {
                    response.sendError(400, "Invalid URI");
                }
            } else {
                response.sendError(400, "Invalid URI");
            }
            // 代表 url 不是被解析成 byte[]
        } else {
            /* The URI is chars or String, and has been sent using an in-memory
             * protocol handler. The following assumptions are made:
             * - req.requestURI() has been set to the 'original' non-decoded,
             *   non-normalized URI
             * - req.decodedURI() has been set to the decoded, normalized form
             *   of req.requestURI()
             *   尝试转换成 char  能进入到这里代表解析成 char[] 或者 str  这2者都是可以转换成 char[] 的
             */
            decodedURI.toChars();
            // Remove all path parameters; any needed path parameter should be set
            // using the request object rather than passing it in the URL
            // 因为在 toChars() 中已经将数据以 char[] 的形式填充到 charChunk 了 这里尝试将数据取出来
            CharChunk uriCC = decodedURI.getCharChunk();
            // 如果 数据中存在 ;
            int semicolon = uriCC.indexOf(';');
            if (semicolon > 0) {
                // 截取掉 后面的部分   分号后面是什么东西 为什么可以被截取
                decodedURI.setChars(uriCC.getBuffer(), uriCC.getStart(), semicolon);
            }
        }

        // Request mapping.
        MessageBytes serverName;
        // 判断是否使用虚拟host
        if (connector.getUseIPVHosts()) {
            serverName = req.localName();
            if (serverName.isNull()) {
                // well, they did ask for it
                // 触发钩子
                res.action(ActionCode.REQ_LOCAL_NAME_ATTRIBUTE, null);
            }
        } else {
            // 否则从 req对象中获取serverName
            serverName = req.serverName();
        }

        // Version for the second mapping loop and
        // Context that we expect to get for that version
        String version = null;
        Context versionContext = null;
        boolean mapRequired = true;

        // 如果res 已经出现了异常那么 就不需要继续处理下去了
        if (response.isError()) {
            // An error this early means the URI is invalid. Ensure invalid data
            // is not passed to the mapper. Note we still want the mapper to
            // find the correct host.
            decodedURI.recycle();
        }

        while (mapRequired) {
            // This will map the the latest version by default
            // 通过映射器来找到 本次url 应该由哪个 对象来处理  此时 req.getMappingData 应该还是一个空对象
            // 这里传入的 serverName 会作为 map 方法的 host   该方法内部会填充 mappingData 属性
            connector.getService().getMapper().map(serverName, decodedURI,
                    version, request.getMappingData());

            // If there is no context at this point, either this is a 404
            // because no ROOT context has been deployed or the URI was invalid
            // so no context could be mapped.
            // 代表没有匹配到合适的context
            if (request.getContext() == null) {
                // Don't overwrite an existing error
                // 返回notFoound
                if (!response.isError()) {
                    response.sendError(404, "Not found");
                }
                // Allow processing to continue.
                // If present, the error reporting valve will provide a response
                // body.
                return true;
            }

            // Now we have the context, we can parse the session ID from the URL
            // (if any). Need to do this before we redirect in case we need to
            // include the session id in the redirect
            // 此时已经通过mapper 对象解析出了 url 对应的context 对象 这里就根据url 获取sessionId  前提是 在servletContext 中指定了 本次的 sessionId
            // 是携带在url 上的
            String sessionID;
            if (request.getServletContext().getEffectiveSessionTrackingModes()
                    .contains(SessionTrackingMode.URL)) {

                // Get the session ID if there was one
                sessionID = request.getPathParameter(
                        SessionConfig.getSessionUriParamName(
                                request.getContext()));
                // 设置sessionId 相关属性
                if (sessionID != null) {
                    request.setRequestedSessionId(sessionID);
                    request.setRequestedSessionURL(true);
                }
            }

            // Look for session ID in cookies and SSL session
            try {
                // 从cookie中解析 sessionId
                parseSessionCookiesId(request);
            } catch (IllegalArgumentException e) {
                // Too many cookies
                if (!response.isError()) {
                    response.setError();
                    response.sendError(400);
                }
                return true;
            }
            parseSessionSslId(request);

            sessionID = request.getRequestedSessionId();

            mapRequired = false;
            if (version != null && request.getContext() == versionContext) {
                // We got the version that we asked for. That is it.
            } else {
                version = null;
                versionContext = null;

                Context[] contexts = request.getMappingData().contexts;
                // Single contextVersion means no need to remap
                // No session ID means no possibility of remap
                if (contexts != null && sessionID != null) {
                    // Find the context associated with the session
                    for (int i = contexts.length; i > 0; i--) {
                        Context ctxt = contexts[i - 1];
                        if (ctxt.getManager().findSession(sessionID) != null) {
                            // We found a context. Is it the one that has
                            // already been mapped?
                            if (!ctxt.equals(request.getMappingData().context)) {
                                // Set version so second time through mapping
                                // the correct context is found
                                version = ctxt.getWebappVersion();
                                versionContext = ctxt;
                                // Reset mapping
                                request.getMappingData().recycle();
                                mapRequired = true;
                                // Recycle cookies and session info in case the
                                // correct context is configured with different
                                // settings
                                request.recycleSessionInfo();
                                request.recycleCookieInfo(true);
                            }
                            break;
                        }
                    }
                }
            }

            if (!mapRequired && request.getContext().getPaused()) {
                // Found a matching context but it is paused. Mapping data will
                // be wrong since some Wrappers may not be registered at this
                // point.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Should never happen
                }
                // Reset mapping
                request.getMappingData().recycle();
                mapRequired = true;
            }
        }

        // Possible redirect
        MessageBytes redirectPathMB = request.getMappingData().redirectPath;
        if (!redirectPathMB.isNull()) {
            String redirectPath = URLEncoder.DEFAULT.encode(
                    redirectPathMB.toString(), StandardCharsets.UTF_8);
            String query = request.getQueryString();
            if (request.isRequestedSessionIdFromURL()) {
                // This is not optimal, but as this is not very common, it
                // shouldn't matter
                redirectPath = redirectPath + ";" +
                        SessionConfig.getSessionUriParamName(
                            request.getContext()) +
                    "=" + request.getRequestedSessionId();
            }
            if (query != null) {
                // This is not optimal, but as this is not very common, it
                // shouldn't matter
                redirectPath = redirectPath + "?" + query;
            }
            response.sendRedirect(redirectPath);
            request.getContext().logAccess(request, response, 0, true);
            return false;
        }

        // Filter trace method
        if (!connector.getAllowTrace()
                && req.method().equalsIgnoreCase("TRACE")) {
            Wrapper wrapper = request.getWrapper();
            String header = null;
            if (wrapper != null) {
                String[] methods = wrapper.getServletMethods();
                if (methods != null) {
                    for (int i=0; i < methods.length; i++) {
                        if ("TRACE".equals(methods[i])) {
                            continue;
                        }
                        if (header == null) {
                            header = methods[i];
                        } else {
                            header += ", " + methods[i];
                        }
                    }
                }
            }
            res.setStatus(405);
            if (header != null) {
                res.addHeader("Allow", header);
            }
            res.setMessage("TRACE method is not allowed");
            request.getContext().logAccess(request, response, 0, true);
            return false;
        }

        doConnectorAuthenticationAuthorization(req, request);

        return true;
    }


    private void doConnectorAuthenticationAuthorization(org.apache.coyote.Request req, Request request) {
        // Set the remote principal
        String username = req.getRemoteUser().toString();
        if (username != null) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("coyoteAdapter.authenticate", username));
            }
            if (req.getRemoteUserNeedsAuthorization()) {
                Authenticator authenticator = request.getContext().getAuthenticator();
                if (!(authenticator instanceof AuthenticatorBase)) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("coyoteAdapter.authorize", username));
                    }
                    // Custom authenticator that may not trigger authorization.
                    // Do the authorization here to make sure it is done.
                    request.setUserPrincipal(
                            request.getContext().getRealm().authenticate(username));
                }
                // If the Authenticator is an instance of AuthenticatorBase then
                // it will check req.getRemoteUserNeedsAuthorization() and
                // trigger authorization as necessary. It will also cache the
                // result preventing excessive calls to the Realm.
            } else {
                // The connector isn't configured for authorization. Create a
                // user without any roles using the supplied user name.
                request.setUserPrincipal(new CoyotePrincipal(username));
            }
        }

        // Set the authorization type
        String authType = req.getAuthType().toString();
        if (authType != null) {
            request.setAuthType(authType);
        }
    }


    /**
     * Extract the path parameters from the request. This assumes parameters are
     * of the form /path;name=value;name2=value2/ etc. Currently only really
     * interested in the session ID that will be in this form. Other parameters
     * can safely be ignored.
     *
     * @param req The Coyote request object
     * @param request The Servlet request object
     */
    protected void parsePathParameters(org.apache.coyote.Request req,
            Request request) {

        // Process in bytes (this is default format so this is normally a NO-OP
        req.decodedURI().toBytes();

        ByteChunk uriBC = req.decodedURI().getByteChunk();
        int semicolon = uriBC.indexOf(';', 0);
        // Performance optimisation. Return as soon as it is known there are no
        // path parameters;
        if (semicolon == -1) {
            return;
        }

        // What encoding to use? Some platforms, eg z/os, use a default
        // encoding that doesn't give the expected result so be explicit
        Charset charset = connector.getURICharset();

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("coyoteAdapter.debug", "uriBC",
                    uriBC.toString()));
            log.debug(sm.getString("coyoteAdapter.debug", "semicolon",
                    String.valueOf(semicolon)));
            log.debug(sm.getString("coyoteAdapter.debug", "enc", charset.name()));
        }

        while (semicolon > -1) {
            // Parse path param, and extract it from the decoded request URI
            int start = uriBC.getStart();
            int end = uriBC.getEnd();

            int pathParamStart = semicolon + 1;
            int pathParamEnd = ByteChunk.findBytes(uriBC.getBuffer(),
                    start + pathParamStart, end,
                    new byte[] {';', '/'});

            String pv = null;

            if (pathParamEnd >= 0) {
                if (charset != null) {
                    pv = new String(uriBC.getBuffer(), start + pathParamStart,
                                pathParamEnd - pathParamStart, charset);
                }
                // Extract path param from decoded request URI
                byte[] buf = uriBC.getBuffer();
                for (int i = 0; i < end - start - pathParamEnd; i++) {
                    buf[start + semicolon + i]
                        = buf[start + i + pathParamEnd];
                }
                uriBC.setBytes(buf, start,
                        end - start - pathParamEnd + semicolon);
            } else {
                if (charset != null) {
                    pv = new String(uriBC.getBuffer(), start + pathParamStart,
                                (end - start) - pathParamStart, charset);
                }
                uriBC.setEnd(start + semicolon);
            }

            if (log.isDebugEnabled()) {
                log.debug(sm.getString("coyoteAdapter.debug", "pathParamStart",
                        String.valueOf(pathParamStart)));
                log.debug(sm.getString("coyoteAdapter.debug", "pathParamEnd",
                        String.valueOf(pathParamEnd)));
                log.debug(sm.getString("coyoteAdapter.debug", "pv", pv));
            }

            if (pv != null) {
                int equals = pv.indexOf('=');
                if (equals > -1) {
                    String name = pv.substring(0, equals);
                    String value = pv.substring(equals + 1);
                    request.addPathParameter(name, value);
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("coyoteAdapter.debug", "equals",
                                String.valueOf(equals)));
                        log.debug(sm.getString("coyoteAdapter.debug", "name",
                                name));
                        log.debug(sm.getString("coyoteAdapter.debug", "value",
                                value));
                    }
                }
            }

            semicolon = uriBC.indexOf(';', semicolon);
        }
    }


    /**
     * Look for SSL session ID if required. Only look for SSL Session ID if it
     * is the only tracking method enabled.
     *
     * @param request The Servlet request object
     */
    protected void parseSessionSslId(Request request) {
        if (request.getRequestedSessionId() == null &&
                SSL_ONLY.equals(request.getServletContext()
                        .getEffectiveSessionTrackingModes()) &&
                        request.connector.secure) {
            String sessionId = (String) request.getAttribute(SSLSupport.SESSION_ID_KEY);
            if (sessionId != null) {
                request.setRequestedSessionId(sessionId);
                request.setRequestedSessionSSL(true);
            }
        }
    }


    /**
     * Parse session id in Cookie.
     *
     * @param request The Servlet request object
     *                从 cookie中解析 sessionId
     */
    protected void parseSessionCookiesId(Request request) {

        // If session tracking via cookies has been disabled for the current
        // context, don't go looking for a session ID in a cookie as a cookie
        // from a parent context with a session ID may be present which would
        // overwrite the valid session ID encoded in the URL
        Context context = request.getMappingData().context;
        // 如果 sessionId 不是携带在cookie 中的 那么直接返回
        if (context != null && !context.getServletContext()
                .getEffectiveSessionTrackingModes().contains(
                        SessionTrackingMode.COOKIE)) {
            return;
        }

        // Parse session id from cookies   首先查看req 是否携带了 cookie 对象
        ServerCookies serverCookies = request.getServerCookies();
        // 如果没有携带cookie 对象 那么直接返回
        int count = serverCookies.getCookieCount();
        if (count <= 0) {
            return;
        }

        // 从context 获取 sessionId在 cookie 中的名字  默认情况下是 JSESSIONID
        String sessionCookieName = SessionConfig.getSessionCookieName(context);

        // 遍历获取所有的 cookie
        for (int i = 0; i < count; i++) {
            ServerCookie scookie = serverCookies.getCookie(i);
            // 名字匹配上了 代表该sessionId的值
            if (scookie.getName().equals(sessionCookieName)) {
                // Override anything requested in the URL
                // 如果此时 设置了 sessionId 从 url 中获取
                if (!request.isRequestedSessionIdFromCookie()) {
                    // Accept only the first session id cookie
                    // 这里代表如果 cookie[] 中有多个 sessionId 这里只取第一个  将内部数据转换成 char[]
                    convertMB(scookie.getValue());
                    // 更新sessionId  也就是 从cookie中获取sessionId 的优先级是要比 从url中获取的优先级高的
                    request.setRequestedSessionId
                        (scookie.getValue().toString());
                    request.setRequestedSessionCookie(true);
                    request.setRequestedSessionURL(false);
                    if (log.isDebugEnabled()) {
                        log.debug(" Requested cookie session id is " +
                            request.getRequestedSessionId());
                    }
                } else {
                    // 代表本身就是设置成 从cookie 中获取sessionId
                    if (!request.isRequestedSessionIdValid()) {
                        // Replace the session id until one is valid
                        convertMB(scookie.getValue());
                        request.setRequestedSessionId
                            (scookie.getValue().toString());
                    }
                }
            }
        }

    }


    /**
     * Character conversion of the URI.
     *
     * @param uri MessageBytes object containing the URI
     * @param request The Servlet request object
     * @throws IOException if a IO exception occurs sending an error to the client
     * 通过字符集 转换url
     */
    protected void convertURI(MessageBytes uri, Request request) throws IOException {

        // 获取 byte[] 形式的 url
        ByteChunk bc = uri.getByteChunk();
        int length = bc.getLength();
        // 分配等大的 char[]
        CharChunk cc = uri.getCharChunk();
        cc.allocate(length, -1);

        // 获取字符集对象
        Charset charset = connector.getURICharset();

        // 获取转换器
        B2CConverter conv = request.getURIConverter();
        if (conv == null) {
            conv = new B2CConverter(charset, true);
            request.setURIConverter(conv);
        } else {
            // 如果转换器还存在 那么先重置属性
            conv.recycle();
        }

        try {
            // 开始 将 byte[] 的数据转换到 char[] 中
            conv.convert(bc, cc, true);
            // 设置 char[] 相关的信息
            uri.setChars(cc.getBuffer(), cc.getStart(), cc.getLength());
        } catch (IOException ioe) {
            // Should never happen as B2CConverter should replace
            // problematic characters
            request.getResponse().sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }


    /**
     * Character conversion of the a US-ASCII MessageBytes.
     *
     * @param mb The MessageBytes instance containing the bytes that should be converted to chars
     */
    protected void convertMB(MessageBytes mb) {

        // This is of course only meaningful for bytes
        if (mb.getType() != MessageBytes.T_BYTES) {
            return;
        }

        ByteChunk bc = mb.getByteChunk();
        CharChunk cc = mb.getCharChunk();
        int length = bc.getLength();
        cc.allocate(length, -1);

        // Default encoding: fast conversion
        byte[] bbuf = bc.getBuffer();
        char[] cbuf = cc.getBuffer();
        int start = bc.getStart();
        for (int i = 0; i < length; i++) {
            cbuf[i] = (char) (bbuf[i + start] & 0xff);
        }
        mb.setChars(cbuf, 0, length);

    }


    /**
     * This method normalizes "\", "//", "/./" and "/../".
     *
     * @param uriMB URI to be normalized
     *
     * @return <code>false</code> if normalizing this URI would require going
     *         above the root, or if the URI contains a null byte, otherwise
     *         <code>true</code>
     *         处理 转义符  双斜杠等
     */
    public static boolean normalize(MessageBytes uriMB) {

        ByteChunk uriBC = uriMB.getByteChunk();
        final byte[] b = uriBC.getBytes();
        final int start = uriBC.getStart();
        int end = uriBC.getEnd();

        // An empty URL is not acceptable
        if (start == end) {
            return false;
        }

        int pos = 0;
        int index = 0;

        // Replace '\' with '/'
        // Check for null byte
        for (pos = start; pos < end; pos++) {
            if (b[pos] == (byte) '\\') {
                if (ALLOW_BACKSLASH) {
                    b[pos] = (byte) '/';
                } else {
                    return false;
                }
            }
            if (b[pos] == (byte) 0) {
                return false;
            }
        }

        // The URL must start with '/'
        if (b[start] != (byte) '/') {
            return false;
        }

        // Replace "//" with "/"
        for (pos = start; pos < (end - 1); pos++) {
            if (b[pos] == (byte) '/') {
                while ((pos + 1 < end) && (b[pos + 1] == (byte) '/')) {
                    copyBytes(b, pos, pos + 1, end - pos - 1);
                    end--;
                }
            }
        }

        // If the URI ends with "/." or "/..", then we append an extra "/"
        // Note: It is possible to extend the URI by 1 without any side effect
        // as the next character is a non-significant WS.
        if (((end - start) >= 2) && (b[end - 1] == (byte) '.')) {
            if ((b[end - 2] == (byte) '/')
                || ((b[end - 2] == (byte) '.')
                    && (b[end - 3] == (byte) '/'))) {
                b[end] = (byte) '/';
                end++;
            }
        }

        uriBC.setEnd(end);

        index = 0;

        // Resolve occurrences of "/./" in the normalized path
        while (true) {
            index = uriBC.indexOf("/./", 0, 3, index);
            if (index < 0) {
                break;
            }
            copyBytes(b, start + index, start + index + 2,
                      end - start - index - 2);
            end = end - 2;
            uriBC.setEnd(end);
        }

        index = 0;

        // Resolve occurrences of "/../" in the normalized path
        while (true) {
            index = uriBC.indexOf("/../", 0, 4, index);
            if (index < 0) {
                break;
            }
            // Prevent from going outside our context
            if (index == 0) {
                return false;
            }
            int index2 = -1;
            for (pos = start + index - 1; (pos >= 0) && (index2 < 0); pos --) {
                if (b[pos] == (byte) '/') {
                    index2 = pos;
                }
            }
            copyBytes(b, start + index2, start + index + 3,
                      end - start - index - 3);
            end = end + index2 - index - 3;
            uriBC.setEnd(end);
            index = index2;
        }

        return true;

    }


    /**
     * Check that the URI is normalized following character decoding. This
     * method checks for "\", 0, "//", "/./" and "/../".
     *
     * @param uriMB URI to be checked (should be chars)
     *
     * @return <code>false</code> if sequences that are supposed to be
     *         normalized are still present in the URI, otherwise
     *         <code>true</code>
     */
    public static boolean checkNormalize(MessageBytes uriMB) {

        CharChunk uriCC = uriMB.getCharChunk();
        char[] c = uriCC.getChars();
        int start = uriCC.getStart();
        int end = uriCC.getEnd();

        int pos = 0;

        // Check for '\' and 0
        for (pos = start; pos < end; pos++) {
            if (c[pos] == '\\') {
                return false;
            }
            if (c[pos] == 0) {
                return false;
            }
        }

        // Check for "//"
        for (pos = start; pos < (end - 1); pos++) {
            if (c[pos] == '/') {
                if (c[pos + 1] == '/') {
                    return false;
                }
            }
        }

        // Check for ending with "/." or "/.."
        if (((end - start) >= 2) && (c[end - 1] == '.')) {
            if ((c[end - 2] == '/')
                    || ((c[end - 2] == '.')
                    && (c[end - 3] == '/'))) {
                return false;
            }
        }

        // Check for "/./"
        if (uriCC.indexOf("/./", 0, 3, 0) >= 0) {
            return false;
        }

        // Check for "/../"
        if (uriCC.indexOf("/../", 0, 4, 0) >= 0) {
            return false;
        }

        return true;

    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Copy an array of bytes to a different position. Used during
     * normalization.
     *
     * @param b The bytes that should be copied
     * @param dest Destination offset
     * @param src Source offset
     * @param len Length
     */
    protected static void copyBytes(byte[] b, int dest, int src, int len) {
        System.arraycopy(b, src, b, dest, len);
    }
}
