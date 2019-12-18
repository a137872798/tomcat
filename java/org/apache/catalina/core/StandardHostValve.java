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
package org.apache.catalina.core;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.res.StringManager;

/**
 * Valve that implements the default basic behavior for the
 * <code>StandardHost</code> container implementation.
 * <p>
 * <b>USAGE CONSTRAINT</b>:  This implementation is likely to be useful only
 * when processing HTTP requests.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * host 级别的阀门     engine 级别的阀门对象 只是直接将req/res 转发到下游
 */
final class StandardHostValve extends ValveBase {

    private static final Log log = LogFactory.getLog(StandardHostValve.class);

    // Saves a call to getClassLoader() on very request. Under high load these
    // calls took just long enough to appear as a hot spot (although a very
    // minor one) in a profiler.
    // 这里获取了 host 对应的类加载器 推测是 commonClassLoader
    private static final ClassLoader MY_CLASSLOADER =
            StandardHostValve.class.getClassLoader();

    /**
     * 是否严格遵循 servlet 规范
     */
    static final boolean STRICT_SERVLET_COMPLIANCE;

    /**
     * 如果严格遵守servlet 规范 那么 access_session 为 true
     */
    static final boolean ACCESS_SESSION;

    static {
        STRICT_SERVLET_COMPLIANCE = Globals.STRICT_SERVLET_COMPLIANCE;

        String accessSession = System.getProperty(
                "org.apache.catalina.core.StandardHostValve.ACCESS_SESSION");
        if (accessSession == null) {
            ACCESS_SESSION = STRICT_SERVLET_COMPLIANCE;
        } else {
            ACCESS_SESSION = Boolean.parseBoolean(accessSession);
        }
    }

    //------------------------------------------------------ Constructor
    public StandardHostValve() {
        super(true);
    }


    // ----------------------------------------------------- Instance Variables

    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
        StringManager.getManager(Constants.Package);


    // --------------------------------------------------------- Public Methods

    /**
     * Select the appropriate child Context to process this request,
     * based on the specified request URI.  If no matching Context can
     * be found, return an appropriate HTTP error.
     *
     * @param request Request to be processed
     * @param response Response to be produced
     *
     * @exception IOException if an input/output error occurred
     * @exception ServletException if a servlet error occurred
     * hostValve 处理  req/res
     */
    @Override
    public final void invoke(Request request, Response response)
        throws IOException, ServletException {

        // Select the Context to be used for this Request
        Context context = request.getContext();
        if (context == null) {
            return;
        }

        if (request.isAsyncSupported()) {
            request.setAsyncSupported(context.getPipeline().isAsyncSupported());
        }

        // 判断当前请求是否支持异步处理
        boolean asyncAtStart = request.isAsync();

        try {
            // 这里将 创建 HostValve 的类加载器绑定到 context 上
            context.bind(Globals.IS_SECURITY_ENABLED, MY_CLASSLOADER);

            // 如果不支持异步req  且 传播init 事件失败 拒绝处理本次请求
            if (!asyncAtStart && !context.fireRequestInitEvent(request.getRequest())) {
                // Don't fire listeners during async processing (the listener
                // fired for the request that called startAsync()).
                // If a request init listener throws an exception, the request
                // is aborted.
                return;
            }

            // Ask this Context to process this request. Requests that are
            // already in error must have been routed here to check for
            // application defined error pages so DO NOT forward them to the the
            // application for processing.
            try {
                // 如果当前res 还没有标记异常 传播到 context 级别 并继续处理 注意在 session 是绑定在context级别的
                if (!response.isErrorReportRequired()) {
                    context.getPipeline().getFirst().invoke(request, response);
                }
                // 在wrapper 处理层 (也就是servlet) mvc 框架有自己的异常处理逻辑 先不管这里
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                container.getLogger().error("Exception Processing " + request.getRequestURI(), t);
                // If a new error occurred while trying to report a previous
                // error allow the original error to be reported.
                // 当捕获到异常时 如果发现 res 还没有指定异常结果 则设置异常属性
                if (!response.isErrorReportRequired()) {
                    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, t);
                    // 处理异常对象
                    throwable(request, response, t);
                }
            }

            // Now that the request/response pair is back under container
            // control lift the suspension so that the error handling can
            // complete and/or the container can flush any remaining data
            // 当处理完成后 将悬置标识设置为false 代表已经处理完毕了
            response.setSuspended(false);

            // 尝试获取异常对象 如果在 wrapper层消化了异常 那么这里应该没有结果
            Throwable t = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

            // Protect against NPEs if the context was destroyed during a
            // long running request.   如果当前context 已经被关闭 直接返回
            if (!context.getState().isAvailable()) {
                return;
            }

            // Look for (and render if found) an application level error page
            if (response.isErrorReportRequired()) {
                // If an error has occurred that prevents further I/O, don't waste time
                // producing an error report that will never be read
                AtomicBoolean result = new AtomicBoolean(false);
                response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, result);
                if (result.get()) {
                    if (t != null) {
                        throwable(request, response, t);
                    } else {
                        status(request, response);
                    }
                }
            }

            // 当一个req 被处理完毕后 会将该req 进行销毁
            if (!request.isAsync() && !asyncAtStart) {
                context.fireRequestDestroyEvent(request.getRequest());
            }
        } finally {
            // Access a session (if present) to update last accessed time, based
            // on a strict interpretation of the specification
            // 如果 access-session 为 true 更新session 的 lastAccessedTime
            if (ACCESS_SESSION) {
                request.getSession(false);
            }

            // 恢复类加载器
            context.unbind(Globals.IS_SECURITY_ENABLED, MY_CLASSLOADER);
        }
    }


    // -------------------------------------------------------- Private Methods

    /**
     * Handle the HTTP status code (and corresponding message) generated
     * while processing the specified Request to produce the specified
     * Response.  Any exceptions that occur during generation of the error
     * report are logged and swallowed.
     *
     * @param request The request being processed
     * @param response The response being generated
     */
    private void status(Request request, Response response) {

        int statusCode = response.getStatus();

        // Handle a custom error page for this status code
        Context context = request.getContext();
        if (context == null) {
            return;
        }

        /* Only look for error pages when isError() is set.
         * isError() is set when response.sendError() is invoked. This
         * allows custom error pages without relying on default from
         * web.xml.
         */
        if (!response.isError()) {
            return;
        }

        ErrorPage errorPage = context.findErrorPage(statusCode);
        if (errorPage == null) {
            // Look for a default error page
            errorPage = context.findErrorPage(0);
        }
        if (errorPage != null && response.isErrorReportRequired()) {
            response.setAppCommitted(false);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,
                              Integer.valueOf(statusCode));

            String message = response.getMessage();
            if (message == null) {
                message = "";
            }
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
            request.setAttribute(Globals.DISPATCHER_REQUEST_PATH_ATTR,
                    errorPage.getLocation());
            request.setAttribute(Globals.DISPATCHER_TYPE_ATTR,
                    DispatcherType.ERROR);


            Wrapper wrapper = request.getWrapper();
            if (wrapper != null) {
                request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME,
                                  wrapper.getName());
            }
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,
                                 request.getRequestURI());
            if (custom(request, response, errorPage)) {
                response.setErrorReported();
                try {
                    response.finishResponse();
                } catch (ClientAbortException e) {
                    // Ignore
                } catch (IOException e) {
                    container.getLogger().warn("Exception Processing " + errorPage, e);
                }
            }
        }
    }


    /**
     * Handle the specified Throwable encountered while processing
     * the specified Request to produce the specified Response.  Any
     * exceptions that occur during generation of the exception report are
     * logged and swallowed.
     *
     * @param request The request being processed
     * @param response The response being generated
     * @param throwable The exception that occurred (which possibly wraps
     *  a root cause exception
     *                  当处理请求时 发生了特殊异常 需要通过该方法做处理  mvc框架应该是自己消化了异常并转换成 json 格式返回了所以这里先不看
     */
    protected void throwable(Request request, Response response,
                             Throwable throwable) {
        // 获取 req 绑定的 context 对象
        Context context = request.getContext();
        if (context == null) {
            return;
        }

        Throwable realError = throwable;

        // 如果是 servlet 规范定义的 异常 那么  获取 rootCause 属性 并将 realError 指向该属性
        if (realError instanceof ServletException) {
            realError = ((ServletException) realError).getRootCause();
            if (realError == null) {
                realError = throwable;
            }
        }

        // If this is an aborted request from a client just log it and return
        // 客户端禁止异常  可能是在 wrapper 处理过程中抛出的吧
        if (realError instanceof ClientAbortException ) {
            if (log.isDebugEnabled()) {
                log.debug
                    (sm.getString("standardHost.clientAbort",
                        realError.getCause().getMessage()));
            }
            return;
        }

        // 从 context 中根据 异常类型查询匹配的异常页面
        ErrorPage errorPage = context.findErrorPage(throwable);
        // 如果原始异常没有找到匹配的页面 则尝试使用 realError 寻找匹配的错误页面
        if ((errorPage == null) && (realError != throwable)) {
            errorPage = context.findErrorPage(realError);
        }

        // 如果找到了错误页面
        if (errorPage != null) {
            // 这里将异常状态 改为 reported  可能代表一种已经处理过的状态吧
            if (response.setErrorReported()) {
                // 设置成未提交
                response.setAppCommitted(false);
                // 将请求处理path 引导到 错误页面位置
                request.setAttribute(Globals.DISPATCHER_REQUEST_PATH_ATTR,
                        errorPage.getLocation());
                // 引导类型设置成 error
                request.setAttribute(Globals.DISPATCHER_TYPE_ATTR,
                        DispatcherType.ERROR);
                // 设置错误码 500
                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,
                        Integer.valueOf(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
                // 指定错误消息
                request.setAttribute(RequestDispatcher.ERROR_MESSAGE,
                                  throwable.getMessage());
                // 指定异常对象
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                                  realError);
                // 标记 该req 是被哪个servlet 处理的  也就是wrapper 对应的是servlet 级别
                Wrapper wrapper = request.getWrapper();
                if (wrapper != null) {
                    request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME,
                                      wrapper.getName());
                }
                // 设置 请求的错误url是什么
                request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,
                                     request.getRequestURI());
                // 设置异常class类型
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE,
                                  realError.getClass());
                // 进行自定义处理
                if (custom(request, response, errorPage)) {
                    try {
                        response.finishResponse();
                    } catch (IOException e) {
                        container.getLogger().warn("Exception Processing " + errorPage, e);
                    }
                }
            }
        } else {
            // A custom error-page has not been defined for the exception
            // that was thrown during request processing. Check if an
            // error-page for error code 500 was specified and if so,
            // send that page back as the response.
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            // The response is an error
            response.setError();

            status(request, response);
        }
    }


    /**
     * Handle an HTTP status code or Java exception by forwarding control
     * to the location included in the specified errorPage object.  It is
     * assumed that the caller has already recorded any request attributes
     * that are to be forwarded to this page.  Return <code>true</code> if
     * we successfully utilized the specified error page location, or
     * <code>false</code> if the default error report should be rendered.
     *
     * @param request The request being processed
     * @param response The response being generated
     * @param errorPage The errorPage directive we are obeying
     */
    private boolean custom(Request request, Response response,
                             ErrorPage errorPage) {

        if (container.getLogger().isDebugEnabled()) {
            container.getLogger().debug("Processing " + errorPage);
        }

        try {
            // Forward control to the specified location
            ServletContext servletContext =
                request.getContext().getServletContext();
            RequestDispatcher rd =
                servletContext.getRequestDispatcher(errorPage.getLocation());

            if (rd == null) {
                container.getLogger().error(
                    sm.getString("standardHostValue.customStatusFailed", errorPage.getLocation()));
                return false;
            }

            if (response.isCommitted()) {
                // Response is committed - including the error page is the
                // best we can do
                rd.include(request.getRequest(), response.getResponse());
            } else {
                // Reset the response (keeping the real error code and message)
                response.resetBuffer(true);
                response.setContentLength(-1);

                rd.forward(request.getRequest(), response.getResponse());

                // If we forward, the response is suspended again
                response.setSuspended(false);
            }

            // Indicate that we have successfully processed this custom page
            return true;

        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // Report our failure to process this custom page
            container.getLogger().error("Exception Processing " + errorPage, t);
            return false;
        }
    }
}
