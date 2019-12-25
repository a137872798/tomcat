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
package org.apache.coyote.http11;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ActionCode;
import org.apache.coyote.ErrorState;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.coyote.http11.filters.ChunkedInputFilter;
import org.apache.coyote.http11.filters.ChunkedOutputFilter;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.coyote.http11.filters.IdentityInputFilter;
import org.apache.coyote.http11.filters.IdentityOutputFilter;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.coyote.http11.filters.VoidInputFilter;
import org.apache.coyote.http11.filters.VoidOutputFilter;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SendfileDataBase;
import org.apache.tomcat.util.net.SendfileKeepAliveState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * http11 processor req 中很多方法 最后都会触发一个hook 对象 而该类就是 hook 的实现类
 * http2 和 ajp 相关的都先不看
 */
public class Http11Processor extends AbstractProcessor {

    private static final Log log = LogFactory.getLog(Http11Processor.class);

    /**
     * The string manager for this package.
     */
    private static final StringManager sm = StringManager.getManager(Http11Processor.class);


    /**
     * 内部关联一个 协议对象
     */
    private final AbstractHttp11Protocol<?> protocol;


    /**
     * Input.
     * 该对象负责与 socketWrapper 交互 读取数据流并抽象成  Request 对象
     */
    protected final Http11InputBuffer inputBuffer;


    /**
     * Output.
     * 该对象负责将res 转换成 数据流写入 到网络中
     */
    protected final Http11OutputBuffer outputBuffer;


    /**
     * 该对象负责检测 字符流中数据 的类型  算是解析的辅助类
     */
    private final HttpParser httpParser;


    /**
     * Tracks how many internal filters are in the filter library so they
     * are skipped when looking for pluggable filters.
     */
    private int pluggableFilterIndex = Integer.MAX_VALUE;


    /**
     * Keep-alive.
     * 是否开始 失活检测  这个属性使用 volatile 来修饰  代表会被并发访问
     */
    protected volatile boolean keepAlive = true;


    /**
     * Flag used to indicate that the socket should be kept open (e.g. for keep
     * alive or send file.
     * 是否应当保持 socket 的开启状态
     */
    protected boolean openSocket = false;


    /**
     * Flag that indicates if the request headers have been completely read.
     * 是否请求头中的数据都已经被读取完毕
     */
    protected boolean readComplete = true;

    /**
     * HTTP/1.1 flag.
     * 代表使用的协议类型
     */
    protected boolean http11 = true;


    /**
     * HTTP/0.9 flag.
     */
    protected boolean http09 = false;


    /**
     * Content delimiter for the request (if false, the connection will
     * be closed at the end of the request).
     * 是否开启内容分隔符
     */
    protected boolean contentDelimitation = true;


    /**
     * Regular expression that defines the restricted user agents.
     * 用于严格定义 用户代理 先不管
     */
    protected Pattern restrictedUserAgents = null;


    /**
     * Maximum number of Keep-Alive requests to honor.
     * 一个长连接允许处理的最大请求数 默认是不做限制的
     */
    protected int maxKeepAliveRequests = -1;


    /**
     * Maximum timeout on uploads. 5 minutes as in Apache HTTPD server.
     */
    protected int connectionUploadTimeout = 300000;


    /**
     * Flag to disable setting a different time-out on uploads.
     */
    protected boolean disableUploadTimeout = false;


    /**
     * Max saved post size.
     * 关于post 请求体中允许携带的最大大小
     */
    protected int maxSavePostSize = 4 * 1024;


    /**
     * Instance of the new protocol to use after the HTTP connection has been
     * upgraded.
     * 升级使用的token
     */
    protected UpgradeToken upgradeToken = null;


    /**
     * Sendfile data.
     */
    protected SendfileDataBase sendfileData = null;


    /**
     * 初始化 http11 的处理器
     * @param protocol
     * @param endpoint  该对象封装了 套接字(与底层网络通信) 的细节
     */
    @SuppressWarnings("deprecation")
    public Http11Processor(AbstractHttp11Protocol<?> protocol, AbstractEndpoint<?> endpoint) {
        super(endpoint);
        this.protocol = protocol;

        // 通过 设置宽松策略 来 放宽对 request 请求头/行的校验
        httpParser = new HttpParser(protocol.getRelaxedPathChars(),
                protocol.getRelaxedQueryChars());

        // 通过合适的参数来初始化与socket 交互的2个buffer 对象

        inputBuffer = new Http11InputBuffer(request, protocol.getMaxHttpHeaderSize(),
                protocol.getRejectIllegalHeaderName(), httpParser);
        request.setInputBuffer(inputBuffer);

        outputBuffer = new Http11OutputBuffer(response, protocol.getMaxHttpHeaderSize(),
                protocol.getSendReasonPhrase());
        response.setOutputBuffer(outputBuffer);

        // Create and add the identity filters.  这里将过滤器添加到 filterLibrary 中  过滤器要起作用还必须要 设置到 activeFilter 中
        inputBuffer.addFilter(new IdentityInputFilter(protocol.getMaxSwallowSize()));
        outputBuffer.addFilter(new IdentityOutputFilter());

        // Create and add the chunked filters.
        inputBuffer.addFilter(new ChunkedInputFilter(protocol.getMaxTrailerSize(),
                protocol.getAllowedTrailerHeadersInternal(), protocol.getMaxExtensionSize(),
                protocol.getMaxSwallowSize()));
        outputBuffer.addFilter(new ChunkedOutputFilter());

        // Create and add the void filters.
        inputBuffer.addFilter(new VoidInputFilter());
        outputBuffer.addFilter(new VoidOutputFilter());

        // Create and add buffered input filter
        inputBuffer.addFilter(new BufferedInputFilter());

        // Create and add the gzip filters.
        //inputBuffer.addFilter(new GzipInputFilter());
        outputBuffer.addFilter(new GzipOutputFilter());

        pluggableFilterIndex = inputBuffer.getFilters().length;
    }


    /**
     * Set compression level.
     *
     * @param compression One of <code>on</code>, <code>force</code>,
     *                    <code>off</code> or the minimum compression size in
     *                    bytes which implies <code>on</code>
     *
     * @deprecated Use {@link Http11Protocol#setCompression(String)}
     */
    @Deprecated
    public void setCompression(String compression) {
        protocol.setCompression(compression);
    }

    /**
     * Set Minimum size to trigger compression.
     *
     * @param compressionMinSize The minimum content length required for
     *                           compression in bytes
     *
     * @deprecated Use {@link Http11Protocol#setCompressionMinSize(int)}
     */
    @Deprecated
    public void setCompressionMinSize(int compressionMinSize) {
        protocol.setCompressionMinSize(compressionMinSize);
    }


    /**
     * Set no compression user agent pattern. Regular expression as supported
     * by {@link Pattern}. e.g.: <code>gorilla|desesplorer|tigrus</code>.
     *
     * @param noCompressionUserAgents The regular expression for user agent
     *                                strings for which compression should not
     *                                be applied
     *
     * @deprecated Use {@link Http11Protocol#setNoCompressionUserAgents(String)}
     */
    @Deprecated
    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        protocol.setNoCompressionUserAgents(noCompressionUserAgents);
    }


    /**
     * @param compressibleMimeTypes See
     *        {@link Http11Processor#setCompressibleMimeTypes(String[])}
     *
     * @deprecated Use {@link Http11Protocol#setCompressibleMimeType(String)}
     */
    @Deprecated
    public void setCompressableMimeTypes(String[] compressibleMimeTypes) {
        setCompressibleMimeTypes(compressibleMimeTypes);
    }


    /**
     * Set compressible mime-type list.
     *
     * @param compressibleMimeTypes MIME types for which compression should be
     *                              enabled
     *
     * @deprecated Use {@link Http11Protocol#setCompressibleMimeType(String)}
     */
    @Deprecated
    public void setCompressibleMimeTypes(String[] compressibleMimeTypes) {
        // Conversion from array to comma separated string and back to array in
        // CompressionConfig isn't ideal but it is better than adding a direct
        // setter only to immediately deprecate it as it doesn't exist in 9.0.x
        // given that Tomcat doesn't call this method in normal usage.
        protocol.setCompressibleMimeType(StringUtils.join(compressibleMimeTypes));
    }


    /**
     * Return compression level.
     *
     * @return The current compression level in string form (off/on/force)
     *
     * @deprecated Use {@link Http11Protocol#getCompression()}
     */
    @Deprecated
    public String getCompression() {
        return protocol.getCompression();
    }


    /**
     * Set restricted user agent list (which will downgrade the connector
     * to HTTP/1.0 mode). Regular expression as supported by {@link Pattern}.
     *
     * @param restrictedUserAgents The regular expression as supported by
     *                             {@link Pattern} for the user agents e.g.
     *                             "gorilla|desesplorer|tigrus"
     */
    public void setRestrictedUserAgents(String restrictedUserAgents) {
        if (restrictedUserAgents == null ||
                restrictedUserAgents.length() == 0) {
            this.restrictedUserAgents = null;
        } else {
            this.restrictedUserAgents = Pattern.compile(restrictedUserAgents);
        }
    }


    /**
     * Set the maximum number of Keep-Alive requests to allow.
     * This is to safeguard from DoS attacks. Setting to a negative
     * value disables the limit.
     *
     * @param mkar The new maximum number of Keep-Alive requests allowed
     */
    public void setMaxKeepAliveRequests(int mkar) {
        maxKeepAliveRequests = mkar;
    }


    /**
     * Get the maximum number of Keep-Alive requests allowed. A negative value
     * means there is no limit.
     *
     * @return the number of Keep-Alive requests that we will allow.
     */
    public int getMaxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }


    /**
     * Set the maximum size of a POST which will be buffered in SSL mode.
     * When a POST is received where the security constraints require a client
     * certificate, the POST body needs to be buffered while an SSL handshake
     * takes place to obtain the certificate.
     *
     * @param msps The maximum size POST body to buffer in bytes
     */
    public void setMaxSavePostSize(int msps) {
        maxSavePostSize = msps;
    }


    /**
     * Return the maximum size of a POST which will be buffered in SSL mode.
     *
     * @return The size in bytes
     */
    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }


    /**
     * Set the flag to control whether a separate connection timeout is used
     * during upload of a request body.
     *
     * @param isDisabled {@code true} if the separate upload timeout should be
     *                   disabled
     */
    public void setDisableUploadTimeout(boolean isDisabled) {
        disableUploadTimeout = isDisabled;
    }

    /**
     * Get the flag that controls upload time-outs.
     *
     * @return {@code true} if the separate upload timeout is disabled
     */
    public boolean getDisableUploadTimeout() {
        return disableUploadTimeout;
    }

    /**
     * Set the upload timeout.
     *
     * @param timeout Upload timeout in milliseconds
     */
    public void setConnectionUploadTimeout(int timeout) {
        connectionUploadTimeout = timeout ;
    }

    /**
     * Get the upload timeout.
     *
     * @return Upload timeout in milliseconds
     */
    public int getConnectionUploadTimeout() {
        return connectionUploadTimeout;
    }


    /**
     * Set the server header name.
     *
     * @param server The new value to use for the server header
     *
     * @deprecated Use {@link Http11Protocol#setServer(String)}
     */
    @Deprecated
    public void setServer(String server) {
        protocol.setServer(server);
    }


    @Deprecated
    public void setServerRemoveAppProvidedValues(boolean serverRemoveAppProvidedValues) {
        protocol.setServerRemoveAppProvidedValues(serverRemoveAppProvidedValues);
    }


    /**
     * Determine if we must drop the connection because of the HTTP status
     * code.  Use the same list of codes as Apache/httpd.
     * 如果遇到这些错误码 代表需要抛弃连接
     */
    private static boolean statusDropsConnection(int status) {
        return status == 400 /* SC_BAD_REQUEST */ ||
               status == 408 /* SC_REQUEST_TIMEOUT */ ||
               status == 411 /* SC_LENGTH_REQUIRED */ ||
               status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
               status == 414 /* SC_REQUEST_URI_TOO_LONG */ ||
               status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
               status == 503 /* SC_SERVICE_UNAVAILABLE */ ||
               status == 501 /* SC_NOT_IMPLEMENTED */;
    }


    /**
     * Add an input filter to the current request. If the encoding is not
     * supported, a 501 response will be returned to the client.
     * @param encodingName 从library 中抽取出想要的过滤器 并添加到 activeFilter 中
     */
    private void addInputFilter(InputFilter[] inputFilters, String encodingName) {

        // Parsing trims and converts to lower case.

        if (encodingName.equals("identity")) {
            // Skip  将chunkedFilter 添加到待处理的filter 中
        } else if (encodingName.equals("chunked")) {
            inputBuffer.addActiveFilter
                (inputFilters[Constants.CHUNKED_FILTER]);
            contentDelimitation = true;
        } else {
            // 找到对应的过滤器并添加到 activeFilter 中
            for (int i = pluggableFilterIndex; i < inputFilters.length; i++) {
                if (inputFilters[i].getEncodingName().toString().equals(encodingName)) {
                    inputBuffer.addActiveFilter(inputFilters[i]);
                    return;
                }
            }
            // Unsupported transfer encoding
            // 501 - Unimplemented  这里代表尝试添加错误的 过滤器 本次请求需要被 drop
            response.setStatus(501);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.prepare") +
                          " Unsupported transfer encoding [" + encodingName + "]");
            }
        }
    }


    /**
     * 应该是将套接字内部的数据流处理成 req res 并交由container 处理
     * @param socketWrapper The connection to process
     *
     * @return
     * @throws IOException
     */
    @Override
    public SocketState service(SocketWrapperBase<?> socketWrapper)
        throws IOException {
        // 获取该请求对应的统计对象 此时该对象应该还没有关联到 RequestGroupInfo 上
        RequestInfo rp = request.getRequestProcessor();
        // 代表开始准备处理req
        rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

        // Setting up the I/O  将socket 设置到 buffer 中
        setSocketWrapper(socketWrapper);

        // Flags
        keepAlive = true;
        openSocket = false;   // 此时套接字还处在关闭状态
        readComplete = true;
        boolean keptAlive = false;
        SendfileState sendfileState = SendfileState.DONE;

        while (!getErrorState().isError() && keepAlive && !isAsync() && upgradeToken == null &&
                sendfileState == SendfileState.DONE && !endpoint.isPaused()) {

            // Parsing the request header
            try {
                // 尝试从socket 中开始解析请求行
                if (!inputBuffer.parseRequestLine(keptAlive)) {
                    // 这里返回 -1 TODO 代表解析到了 HTTP/2.0 的东西 这里先跳过
                    if (inputBuffer.getParsingRequestLinePhase() == -1) {
                        return SocketState.UPGRADING;
                    // 代表还需要读取更多的数据 则退出循环
                    } else if (handleIncompleteRequestLineRead()) {
                        break;
                    }
                }

                // 这里代表成功解析了请求行 之后尝试解析 请求头  如果发现 endpoint 停止工作了 拒绝处理本次请求
                if (endpoint.isPaused()) {
                    // 503 - Service unavailable
                    response.setStatus(503);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                } else {
                    keptAlive = true;
                    // Set this every time in case limit has been changed via JMX
                    request.getMimeHeaders().setLimit(endpoint.getMaxHeaderCount());
                    // 尝试解析请求头  同样解析失败时 代表还需要更多的数据 这时就需要从 socket 中继续读取数据
                    if (!inputBuffer.parseHeaders()) {
                        // We've read part of the request, don't recycle it
                        // instead associate it with the socket
                        openSocket = true;
                        readComplete = false;
                        break;
                    }
                    if (!disableUploadTimeout) {
                        socketWrapper.setReadTimeout(connectionUploadTimeout);
                    }
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.header.parse"), e);
                }
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                break;
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                UserDataHelper.Mode logMode = userDataHelper.getNextMode();
                if (logMode != null) {
                    String message = sm.getString("http11processor.header.parse");
                    switch (logMode) {
                        case INFO_THEN_DEBUG:
                            message += sm.getString("http11processor.fallToDebug");
                            //$FALL-THROUGH$
                        case INFO:
                            log.info(message, t);
                            break;
                        case DEBUG:
                            log.debug(message, t);
                    }
                }
                // 400 - Bad Request  出现其他预期外的异常 返回400 错误码
                response.setStatus(400);
                setErrorState(ErrorState.CLOSE_CLEAN, t);
            }

            // Has an upgrade been requested?  TODO 升级相关先不看
            if (isConnectionToken(request.getMimeHeaders(), "upgrade")) {
                // Check the protocol
                String requestedProtocol = request.getHeader("Upgrade");

                UpgradeProtocol upgradeProtocol = protocol.getUpgradeProtocol(requestedProtocol);
                if (upgradeProtocol != null) {
                    if (upgradeProtocol.accept(request)) {
                        // TODO Figure out how to handle request bodies at this
                        // point.
                        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
                        response.setHeader("Connection", "Upgrade");
                        response.setHeader("Upgrade", requestedProtocol);
                        action(ActionCode.CLOSE,  null);
                        getAdapter().log(request, response, 0);

                        InternalHttpUpgradeHandler upgradeHandler =
                                upgradeProtocol.getInternalUpgradeHandler(
                                        getAdapter(), cloneRequest(request));
                        UpgradeToken upgradeToken = new UpgradeToken(upgradeHandler, null, null);
                        action(ActionCode.UPGRADE, upgradeToken);
                        return SocketState.UPGRADING;
                    }
                }
            }

            // 如果此时还是允许读取数据的 那么使用数据流构建 request 对象
            if (getErrorState().isIoAllowed()) {
                // Setting up filters, and parse some request headers
                // 将当前标记为准备阶段  主要是读取请求头的某些字段， 做一些校验  以及添加对应的 filter
                rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
                try {
                    prepareRequest();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("http11processor.request.prepare"), t);
                    }
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    setErrorState(ErrorState.CLOSE_CLEAN, t);
                }
            }

            // 如果每个长连接只允许处理一个请求 那么标记成 短连接状态
            if (maxKeepAliveRequests == 1) {
                keepAlive = false;
            } else if (maxKeepAliveRequests > 0 &&
                    // TODO 暂时不知道干嘛的
                    socketWrapper.decrementKeepAlive() <= 0) {
                keepAlive = false;
            }

            // Process the request in the adapter
            // 判断当前是否已经出现了 禁止io 的异常 比如 CLOSE_NOW / CLOSE_CONNECTION_NOW
            if (getErrorState().isIoAllowed()) {
                try {
                    // 进入 container 的处理阶段了
                    rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                    // 这里将请求转发给 adapter  也就是适配器的作用是 连接 processor 和 connector 的桥梁
                    getAdapter().service(request, response);
                    // Handle when the response was committed before a serious
                    // error occurred.  Throwing a ServletException should both
                    // set the status to 500 and set the errorException.
                    // If we fail here, then the response is likely already
                    // committed, so we can't try and set headers.
                    if(keepAlive && !getErrorState().isError() && !isAsync() &&
                            statusDropsConnection(response.getStatus())) {
                        setErrorState(ErrorState.CLOSE_CLEAN, null);
                    }
                } catch (InterruptedIOException e) {
                    setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                } catch (HeadersTooLargeException e) {
                    log.error(sm.getString("http11processor.request.process"), e);
                    // The response should not have been committed but check it
                    // anyway to be safe
                    if (response.isCommitted()) {
                        setErrorState(ErrorState.CLOSE_NOW, e);
                    } else {
                        response.reset();
                        response.setStatus(500);
                        setErrorState(ErrorState.CLOSE_CLEAN, e);
                        response.setHeader("Connection", "close"); // TODO: Remove
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("http11processor.request.process"), t);
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    setErrorState(ErrorState.CLOSE_CLEAN, t);
                    getAdapter().log(request, response, 0);
                }
            }

            // Finish the handling of the request
            rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
            if (!isAsync()) {
                // If this is an async request then the request ends when it has
                // been completed. The AsyncContext is responsible for calling
                // endRequest() in that case.
                endRequest();
            }
            rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);

            // If there was an error, make sure the request is counted as
            // and error, and update the statistics counter
            if (getErrorState().isError()) {
                response.setStatus(500);
            }

            if (!isAsync() || getErrorState().isError()) {
                request.updateCounters();
                if (getErrorState().isIoAllowed()) {
                    inputBuffer.nextRequest();
                    outputBuffer.nextRequest();
                }
            }

            if (!disableUploadTimeout) {
                int soTimeout = endpoint.getConnectionTimeout();
                if(soTimeout > 0) {
                    socketWrapper.setReadTimeout(soTimeout);
                } else {
                    socketWrapper.setReadTimeout(0);
                }
            }

            rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

            sendfileState = processSendfile(socketWrapper);
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (getErrorState().isError() || endpoint.isPaused()) {
            return SocketState.CLOSED;
        } else if (isAsync()) {
            return SocketState.LONG;
        } else if (isUpgrade()) {
            return SocketState.UPGRADING;
        } else {
            if (sendfileState == SendfileState.PENDING) {
                return SocketState.SENDFILE;
            } else {
                if (openSocket) {
                    if (readComplete) {
                        return SocketState.OPEN;
                    } else {
                        return SocketState.LONG;
                    }
                } else {
                    return SocketState.CLOSED;
                }
            }
        }
    }


    /**
     * 这里将 套接字对象设置到 2个 跟网络缓冲区直接交互的 buffer 中 用于读取数据流并解析请求头/请求行
     * @param socketWrapper The socket wrapper
     */
    @Override
    protected final void setSocketWrapper(SocketWrapperBase<?> socketWrapper) {
        super.setSocketWrapper(socketWrapper);
        inputBuffer.init(socketWrapper);
        outputBuffer.init(socketWrapper);
    }


    private Request cloneRequest(Request source) throws IOException {
        Request dest = new Request();

        // Transfer the minimal information required for the copy of the Request
        // that is passed to the HTTP upgrade process

        dest.decodedURI().duplicate(source.decodedURI());
        dest.method().duplicate(source.method());
        dest.getMimeHeaders().duplicate(source.getMimeHeaders());
        dest.requestURI().duplicate(source.requestURI());
        dest.queryString().duplicate(source.queryString());

        return dest;

    }

    /**
     * 当尝试将数据流解析到 inputBuffer 中时 发现数据不够 这里还需要继续往 网络层套接字 中读取数据
     * @return
     */
    private boolean handleIncompleteRequestLineRead() {
        // Haven't finished reading the request so keep the socket
        // open   此时需要打开套接字
        openSocket = true;
        // Check to see if we have read any of the request line yet 代表已经开始解析数据了 如果刚好开始读取一个新的请求 那么 parsingRequestLinePhase 为 1
        if (inputBuffer.getParsingRequestLinePhase() > 1) {
            // Started to read request line.  如果当前端点已经停止工作了 那么要抛出异常 提示本次请求终止处理
            if (endpoint.isPaused()) {
                // Partially processed the request so need to respond
                response.setStatus(503);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
                return false;
            } else {
                // Need to keep processor associated with socket  代表还需要继续处理数据
                readComplete = false;
            }
        }
        return true;
    }


    private void checkExpectationAndResponseStatus() {
        if (request.hasExpectation() &&
                (response.getStatus() < 200 || response.getStatus() > 299)) {
            // Client sent Expect: 100-continue but received a
            // non-2xx final response. Disable keep-alive (if enabled)
            // to ensure that the connection is closed. Some clients may
            // still send the body, some may send the next request.
            // No way to differentiate, so close the connection to
            // force the client to send the next request.
            inputBuffer.setSwallowInput(false);
            keepAlive = false;
        }
    }


    /**
     * After reading the request headers, we have to setup the request filters.
     * 开始构建req 对象
     */
    private void prepareRequest() throws IOException {

        http11 = true;
        http09 = false;
        contentDelimitation = false;

        if (endpoint.isSSLEnabled()) {
            request.scheme().setString("https");
        }
        // 根据 从 inputBuffer 中读取出来的数据 设置 协议属性
        MessageBytes protocolMB = request.protocol();
        if (protocolMB.equals(Constants.HTTP_11)) {
            http11 = true;
            protocolMB.setString(Constants.HTTP_11);
        } else if (protocolMB.equals(Constants.HTTP_10)) {
            http11 = false;
            keepAlive = false;
            protocolMB.setString(Constants.HTTP_10);
        } else if (protocolMB.equals("")) {
            // HTTP/0.9
            http09 = true;
            http11 = false;
            keepAlive = false;
        } else {
            // Unsupported protocol  不支持 除了 1.1 和 1.0 之外的其他协议
            http11 = false;
            // Send 505; Unsupported HTTP version
            response.setStatus(505);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.prepare")+
                          " Unsupported HTTP version \""+protocolMB+"\"");
            }
        }

        MimeHeaders headers = request.getMimeHeaders();

        // Check connection header
        // 尝试获取 Connection 对应的请求头信息   比如 Connection: keep-alive
        MessageBytes connectionValueMB = headers.getValue(Constants.CONNECTION);
        if (connectionValueMB != null && !connectionValueMB.isNull()) {
            Set<String> tokens = new HashSet<>();
            // 将数据解析后设置到 tokens 中
            TokenList.parseTokenList(headers.values(Constants.CONNECTION), tokens);
            // 如果包含了 close 代表本次 http请求是短连接
            if (tokens.contains(Constants.CLOSE)) {
                keepAlive = false;
            } else if (tokens.contains(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN)) {
                keepAlive = true;
            }
        }

        // 如果是 HTTP 1.1
        if (http11) {
            // expect 属性先忽略
            MessageBytes expectMB = headers.getValue("expect");
            if (expectMB != null && !expectMB.isNull()) {
                if (expectMB.toString().trim().equalsIgnoreCase("100-continue")) {
                    inputBuffer.setSwallowInput(false);
                    request.setExpectation(true);
                } else {
                    response.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                }
            }
        }

        // Check user-agent header
        // User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.88 Safari/537.36
        // restrictedUserAgents一般不设置 先不看
        if (restrictedUserAgents != null && (http11 || keepAlive)) {
            MessageBytes userAgentValueMB = headers.getValue("user-agent");
            // Check in the restricted list, and adjust the http11
            // and keepAlive flags accordingly
            if(userAgentValueMB != null && !userAgentValueMB.isNull()) {
                String userAgentValue = userAgentValueMB.toString();
                if (restrictedUserAgents != null &&
                        restrictedUserAgents.matcher(userAgentValue).matches()) {
                    http11 = false;
                    keepAlive = false;
                }
            }
        }


        // Check host header
        // 比如 Host: news.baidu.com
        MessageBytes hostValueMB = null;
        try {
            hostValueMB = headers.getUniqueValue("host");
            // 如果包含多个 host 则抛出异常
        } catch (IllegalArgumentException iae) {
            // Multiple Host headers are not permitted
            badRequest("http11processor.request.multipleHosts");
        }
        // 如果指定是 http1.1 且没有设置 host 属性 则抛出异常
        if (http11 && hostValueMB == null) {
            badRequest("http11processor.request.noHostHeader");
        }

        // Check for an absolute-URI less the query string which has already
        // been removed during the parsing of the request line
        // 在 InputBuffer 中 解析顺序依次是   RequestMethod URL PROTOCOL
        ByteChunk uriBC = request.requestURI().getByteChunk();
        byte[] uriB = uriBC.getBytes();
        // 这里相当于是在校验 url 的正确性 在 Http11InputBuffer 中 只是 将 url 按照 ? 前后进行了拆分
        if (uriBC.startsWithIgnoreCase("http", 0)) {
            int pos = 4;
            // Check for https

            if (uriBC.startsWithIgnoreCase("s", pos)) {
                pos++;
            }
            // Next 3 characters must be "://"  这里必须为 ://
            if (uriBC.startsWith("://", pos)) {
                pos += 3;
                int uriBCStart = uriBC.getStart();

                // TODO 冷门规范 忽略
                // '/' does not appear in the authority so use the first
                // instance to split the authority and the path segments
                // @ 必须要在 / 前 才代表 携带用户信息
                int slashPos = uriBC.indexOf('/', pos);
                // '@' in the authority delimits the userinfo
                int atPos = uriBC.indexOf('@', pos);
                if (slashPos > -1 && atPos > slashPos) {
                    // First '@' is in the path segments so no userinfo
                    atPos = -1;
                }

                if (slashPos == -1) {
                    slashPos = uriBC.getLength();
                    // Set URI as "/". Use 6 as it will always be a '/'.
                    // 01234567
                    // http://
                    // https://
                    request.requestURI().setBytes(uriB, uriBCStart + 6, 1);
                } else {
                    request.requestURI().setBytes(uriB, uriBCStart + slashPos, uriBC.getLength() - slashPos);
                }

                // Skip any user info
                if (atPos != -1) {
                    // Validate the userinfo
                    for (; pos < atPos; pos++) {
                        byte c = uriB[uriBCStart + pos];
                        if (!HttpParser.isUserInfo(c)) {
                            // Strictly there needs to be a check for valid %nn
                            // encoding here but skip it since it will never be
                            // decoded because the userinfo is ignored
                            badRequest("http11processor.request.invalidUserInfo");
                            break;
                        }
                    }
                    // Skip the '@'
                    pos = atPos + 1;
                }

                if (http11) {
                    // Missing host header is illegal but handled above
                    if (hostValueMB != null) {
                        // Any host in the request line must be consistent with
                        // the Host header
                        // 这里 请求头中的 HOST 与 url 中的部分 必须相同
                        if (!hostValueMB.getByteChunk().equals(
                                uriB, uriBCStart + pos, slashPos - pos)) {
                            // 判断是否允许 host 不满足规范
                            if (protocol.getAllowHostHeaderMismatch()) {
                                // The requirements of RFC 2616 are being
                                // applied. If the host header and the request
                                // line do not agree, the request line takes
                                // precedence
                                // 这里将 HOST 更改成 url 中的 而不是请求头携带的
                                hostValueMB = headers.setValue("host");
                                hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
                            } else {
                                // The requirements of RFC 7230 are being
                                // applied. If the host header and the request
                                // line do not agree, trigger a 400 response.
                                // 否则 抛出 400 异常
                                badRequest("http11processor.request.inconsistentHosts");
                            }
                        }
                    }
                } else {
                    // Not HTTP/1.1 - no Host header so generate one since
                    // Tomcat internals assume it is set
                    try {
                        hostValueMB = headers.setValue("host");
                        hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
                    } catch (IllegalStateException e) {
                        // Edge case
                        // If the request has too many headers it won't be
                        // possible to create the host header. Ignore this as
                        // processing won't reach the point where the Tomcat
                        // internals expect there to be a host header.
                    }
                }
            } else {
                badRequest("http11processor.request.invalidScheme");
            }
        }

        // Validate the characters in the URI. %nn decoding will be checked at
        // the point of decoding.
        for (int i = uriBC.getStart(); i < uriBC.getEnd(); i++) {
            // 必须满足路径规范
            if (!httpParser.isAbsolutePathRelaxed(uriB[i])) {
                badRequest("http11processor.request.invalidUri");
                break;
            }
        }

        // Input filter setup
        InputFilter[] inputFilters = inputBuffer.getFilters();

        // Parse transfer-encoding header
        // TODO 根据这个属性在处理 req 的过程中添加具备额外功能的过滤器
        if (http11) {
            MessageBytes transferEncodingValueMB = headers.getValue("transfer-encoding");
            if (transferEncodingValueMB != null) {
                List<String> encodingNames = new ArrayList<>();
                TokenList.parseTokenList(headers.values("transfer-encoding"), encodingNames);
                for (String encodingName : encodingNames) {
                    // "identity" codings are ignored
                    addInputFilter(inputFilters, encodingName);
                }
            }
        }

        // Parse content-length header  开始解析 请求头中的 content-length
        long contentLength = -1;
        try {
            contentLength = request.getContentLengthLong();
        } catch (NumberFormatException e) {
            badRequest("http11processor.request.nonNumericContentLength");
        } catch (IllegalArgumentException e) {
            badRequest("http11processor.request.multipleContentLength");
        }
        // 如果设置了 chunked 的话 这个属性会被设置成 ture 这里先忽略具体作用吧
        if (contentLength >= 0) {
            if (contentDelimitation) {
                // contentDelimitation being true at this point indicates that
                // chunked encoding is being used but chunked encoding should
                // not be used with a content length. RFC 2616, section 4.4,
                // bullet 3 states Content-Length must be ignored in this case -
                // so remove it.
                headers.removeHeader("content-length");
                request.setContentLength(-1);
            } else {
                inputBuffer.addActiveFilter(inputFilters[Constants.IDENTITY_FILTER]);
                contentDelimitation = true;
            }
        }

        // Validate host name and extract port if present
        // 如果host 中携带端口号 在 hostNameC 中保存 ip的部分 否则将整个url 保存到 hostNameC 中 应该就是通过 它来匹配 使用哪个Host （Tomcat的 container ）
        parseHost(hostValueMB);

        if (!contentDelimitation) {
            // If there's no content length
            // (broken HTTP/1.0 or HTTP/1.1), assume
            // the client is not broken and didn't send a body
            inputBuffer.addActiveFilter(inputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        if (!getErrorState().isIoAllowed()) {
            getAdapter().log(request, response, 0);
        }
    }


    private void badRequest(String errorKey) {
        response.setStatus(400);
        setErrorState(ErrorState.CLOSE_CLEAN, null);
        if (log.isDebugEnabled()) {
            log.debug(sm.getString(errorKey));
        }
    }


    /**
     * When committing the response, we have to validate the set of headers, as
     * well as setup the response filters.
     */
    @Override
    protected final void prepareResponse() throws IOException {

        boolean entityBody = true;
        contentDelimitation = false;

        OutputFilter[] outputFilters = outputBuffer.getFilters();

        if (http09 == true) {
            // HTTP/0.9
            outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            outputBuffer.commit();
            return;
        }

        int statusCode = response.getStatus();
        if (statusCode < 200 || statusCode == 204 || statusCode == 205 ||
                statusCode == 304) {
            // No entity body
            outputBuffer.addActiveFilter
                (outputFilters[Constants.VOID_FILTER]);
            entityBody = false;
            contentDelimitation = true;
            if (statusCode == 205) {
                // RFC 7231 requires the server to explicitly signal an empty
                // response in this case
                response.setContentLength(0);
            } else {
                response.setContentLength(-1);
            }
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals("HEAD")) {
            // No entity body
            outputBuffer.addActiveFilter
                (outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // Sendfile support
        if (endpoint.getUseSendfile()) {
            prepareSendfile(outputFilters);
        }

        // Check for compression
        boolean useCompression = false;
        if (entityBody && sendfileData == null) {
            useCompression = protocol.useCompression(request, response);
        }

        MimeHeaders headers = response.getMimeHeaders();
        // A SC_NO_CONTENT response may include entity headers
        if (entityBody || statusCode == HttpServletResponse.SC_NO_CONTENT) {
            String contentType = response.getContentType();
            if (contentType != null) {
                headers.setValue("Content-Type").setString(contentType);
            }
            String contentLanguage = response.getContentLanguage();
            if (contentLanguage != null) {
                headers.setValue("Content-Language")
                    .setString(contentLanguage);
            }
        }

        long contentLength = response.getContentLengthLong();
        boolean connectionClosePresent = isConnectionToken(headers, Constants.CLOSE);
        if (contentLength != -1) {
            headers.setValue("Content-Length").setLong(contentLength);
            outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            contentDelimitation = true;
        } else {
            // If the response code supports an entity body and we're on
            // HTTP 1.1 then we chunk unless we have a Connection: close header
            if (http11 && entityBody && !connectionClosePresent) {
                outputBuffer.addActiveFilter(outputFilters[Constants.CHUNKED_FILTER]);
                contentDelimitation = true;
                headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
            } else {
                outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            }
        }

        if (useCompression) {
            outputBuffer.addActiveFilter(outputFilters[Constants.GZIP_FILTER]);
        }

        // Add date header unless application has already set one (e.g. in a
        // Caching Filter)
        if (headers.getValue("Date") == null) {
            headers.addValue("Date").setString(
                    FastHttpDateFormat.getCurrentDate());
        }

        // FIXME: Add transfer encoding header

        if ((entityBody) && (!contentDelimitation)) {
            // Mark as close the connection after the request, and add the
            // connection: close header
            keepAlive = false;
        }

        // This may disabled keep-alive to check before working out the
        // Connection header.
        checkExpectationAndResponseStatus();

        // If we know that the request is bad this early, add the
        // Connection: close header.
        if (keepAlive && statusDropsConnection(statusCode)) {
            keepAlive = false;
        }
        if (!keepAlive) {
            // Avoid adding the close header twice
            if (!connectionClosePresent) {
                headers.addValue(Constants.CONNECTION).setString(
                        Constants.CLOSE);
            }
        } else if (!getErrorState().isError()) {
            if (!http11) {
                headers.addValue(Constants.CONNECTION).setString(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
            }

            if (protocol.getUseKeepAliveResponseHeader()) {
                boolean connectionKeepAlivePresent =
                    isConnectionToken(request.getMimeHeaders(), Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);

                if (connectionKeepAlivePresent) {
                    int keepAliveTimeout = protocol.getKeepAliveTimeout();

                    if (keepAliveTimeout > 0) {
                        String value = "timeout=" + keepAliveTimeout / 1000L;
                        headers.setValue(Constants.KEEP_ALIVE_HEADER_NAME).setString(value);

                        if (http11) {
                            // Append if there is already a Connection header,
                            // else create the header
                            MessageBytes connectionHeaderValue = headers.getValue(Constants.CONNECTION);
                            if (connectionHeaderValue == null) {
                                headers.addValue(Constants.CONNECTION).setString(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
                            } else {
                                connectionHeaderValue.setString(
                                        connectionHeaderValue.getString() + ", " + Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
                            }
                        }
                    }
                }
            }
        }

        // Add server header
        String server = protocol.getServer();
        if (server == null) {
            if (protocol.getServerRemoveAppProvidedValues()) {
                headers.removeHeader("server");
            }
        } else {
            // server always overrides anything the app might set
            headers.setValue("Server").setString(server);
        }

        // Build the response header
        try {
            outputBuffer.sendStatus();

            int size = headers.size();
            for (int i = 0; i < size; i++) {
                outputBuffer.sendHeader(headers.getName(i), headers.getValue(i));
            }
            outputBuffer.endHeaders();
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // If something goes wrong, reset the header buffer so the error
            // response can be written instead.
            outputBuffer.resetHeaderBuffer();
            throw t;
        }

        outputBuffer.commit();
    }

    private static boolean isConnectionToken(MimeHeaders headers, String token) throws IOException {
        MessageBytes connection = headers.getValue(Constants.CONNECTION);
        if (connection == null) {
            return false;
        }

        Set<String> tokens = new HashSet<>();
        TokenList.parseTokenList(headers.values(Constants.CONNECTION), tokens);
        return tokens.contains(token);
    }


    private void prepareSendfile(OutputFilter[] outputFilters) {
        String fileName = (String) request.getAttribute(
                org.apache.coyote.Constants.SENDFILE_FILENAME_ATTR);
        if (fileName == null) {
            sendfileData = null;
        } else {
            // No entity body sent here
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
            long pos = ((Long) request.getAttribute(
                    org.apache.coyote.Constants.SENDFILE_FILE_START_ATTR)).longValue();
            long end = ((Long) request.getAttribute(
                    org.apache.coyote.Constants.SENDFILE_FILE_END_ATTR)).longValue();
            sendfileData = socketWrapper.createSendfileData(fileName, pos, end - pos);
        }
    }


    /*
     * Note: populateHost() is not over-ridden.
     *       request.serverName() will be set to return the default host name by
     *       the Mapper.
     */


    /**
     * {@inheritDoc}
     * <p>
     * This implementation provides the server port from the local port.
     * 为 req 对象 填充服务器端口  作为tomcat 本身很容易知道自己的端口
     */
    @Override
    protected void populatePort() {
        // Ensure the local port field is populated before using it.
        request.action(ActionCode.REQ_LOCALPORT_ATTRIBUTE, request);
        request.setServerPort(request.getLocalPort());
    }


    @Override
    protected boolean flushBufferedWrite() throws IOException {
        if (outputBuffer.hasDataToWrite()) {
            if (outputBuffer.flushBuffer(false)) {
                // The buffer wasn't fully flushed so re-register the
                // socket for write. Note this does not go via the
                // Response since the write registration state at
                // that level should remain unchanged. Once the buffer
                // has been emptied then the code below will call
                // Adaptor.asyncDispatch() which will enable the
                // Response to respond to this event.
                outputBuffer.registerWriteInterest();
                return true;
            }
        }
        return false;
    }


    @Override
    protected SocketState dispatchEndRequest() {
        if (!keepAlive) {
            return SocketState.CLOSED;
        } else {
            endRequest();
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();
            if (socketWrapper.isReadPending()) {
                return SocketState.LONG;
            } else {
                return SocketState.OPEN;
            }
        }
    }


    @Override
    protected Log getLog() {
        return log;
    }


    /*
     * No more input will be passed to the application. Remaining input will be
     * swallowed or the connection dropped depending on the error and
     * expectation status.
     */
    private void endRequest() {
        if (getErrorState().isError()) {
            // If we know we are closing the connection, don't drain
            // input. This way uploading a 100GB file doesn't tie up the
            // thread if the servlet has rejected it.
            inputBuffer.setSwallowInput(false);
        } else {
            // Need to check this again here in case the response was
            // committed before the error that requires the connection
            // to be closed occurred.
            checkExpectationAndResponseStatus();
        }

        // Finish the handling of the request
        if (getErrorState().isIoAllowed()) {
            try {
                inputBuffer.endRequest();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                // 500 - Internal Server Error
                // Can't add a 500 to the access log since that has already been
                // written in the Adapter.service method.
                response.setStatus(500);
                setErrorState(ErrorState.CLOSE_NOW, t);
                log.error(sm.getString("http11processor.request.finish"), t);
            }
        }
        if (getErrorState().isIoAllowed()) {
            try {
                action(ActionCode.COMMIT, null);
                outputBuffer.end();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                setErrorState(ErrorState.CLOSE_NOW, t);
                log.error(sm.getString("http11processor.response.finish"), t);
            }
        }
    }


    @Override
    protected final void finishResponse() throws IOException {
        outputBuffer.end();
    }


    @Override
    protected final void ack() {
        // Acknowledge request
        // Send a 100 status back if it makes sense (response not committed
        // yet, and client specified an expectation for 100-continue)
        if (!response.isCommitted() && request.hasExpectation()) {
            inputBuffer.setSwallowInput(true);
            try {
                outputBuffer.sendAck();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            }
        }
    }


    @Override
    protected final void flush() throws IOException {
        outputBuffer.flush();
    }


    @Override
    protected final int available(boolean doRead) {
        return inputBuffer.available(doRead);
    }


    @Override
    protected final void setRequestBody(ByteChunk body) {
        InputFilter savedBody = new SavedRequestInputFilter(body);
        Http11InputBuffer internalBuffer = (Http11InputBuffer) request.getInputBuffer();
        internalBuffer.addActiveFilter(savedBody);
    }


    @Override
    protected final void setSwallowResponse() {
        outputBuffer.responseFinished = true;
    }


    @Override
    protected final void disableSwallowRequest() {
        inputBuffer.setSwallowInput(false);
    }


    @Override
    protected final void sslReHandShake() throws IOException {
        if (sslSupport != null) {
            // Consume and buffer the request body, so that it does not
            // interfere with the client's handshake messages
            InputFilter[] inputFilters = inputBuffer.getFilters();
            ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER]).setLimit(
                    maxSavePostSize);
            inputBuffer.addActiveFilter(inputFilters[Constants.BUFFERED_FILTER]);

            /*
             * Outside the try/catch because we want I/O errors during
             * renegotiation to be thrown for the caller to handle since they
             * will be fatal to the connection.
             */
            socketWrapper.doClientAuth(sslSupport);
            try {
                /*
                 * Errors processing the cert chain do not affect the client
                 * connection so they can be logged and swallowed here.
                 */
                Object sslO = sslSupport.getPeerCertificateChain();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
                }
            } catch (IOException ioe) {
                log.warn(sm.getString("http11processor.socket.ssl"), ioe);
            }
        }
    }


    @Override
    protected final boolean isRequestBodyFullyRead() {
        return inputBuffer.isFinished();
    }


    @Override
    protected final void registerReadInterest() {
        socketWrapper.registerReadInterest();
    }


    @Override
    protected final boolean isReadyForWrite() {
        return outputBuffer.isReady();
    }


    @Override
    public UpgradeToken getUpgradeToken() {
        return upgradeToken;
    }


    @Override
    protected final void doHttpUpgrade(UpgradeToken upgradeToken) {
        this.upgradeToken = upgradeToken;
        // Stop further HTTP output
        outputBuffer.responseFinished = true;
    }


    @Override
    public ByteBuffer getLeftoverInput() {
        return inputBuffer.getLeftover();
    }


    @Override
    public boolean isUpgrade() {
        return upgradeToken != null;
    }


    /**
     * Trigger sendfile processing if required.
     *
     * @return The state of send file processing
     */
    private SendfileState processSendfile(SocketWrapperBase<?> socketWrapper) {
        openSocket = keepAlive;
        // Done is equivalent to sendfile not being used
        SendfileState result = SendfileState.DONE;
        // Do sendfile as needed: add socket to sendfile and end
        if (sendfileData != null && !getErrorState().isError()) {
            if (keepAlive) {
                if (available(false) == 0) {
                    sendfileData.keepAliveState = SendfileKeepAliveState.OPEN;
                } else {
                    sendfileData.keepAliveState = SendfileKeepAliveState.PIPELINED;
                }
            } else {
                sendfileData.keepAliveState = SendfileKeepAliveState.NONE;
            }
            result = socketWrapper.processSendfile(sendfileData);
            switch (result) {
            case ERROR:
                // Write failed
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.sendfile.error"));
                }
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, null);
                //$FALL-THROUGH$
            default:
                sendfileData = null;
            }
        }
        return result;
    }


    @Override
    public final void recycle() {
        getAdapter().checkRecycled(request, response);
        super.recycle();
        inputBuffer.recycle();
        outputBuffer.recycle();
        upgradeToken = null;
        socketWrapper = null;
        sendfileData = null;
    }


    @Override
    public void pause() {
        // NOOP for HTTP
    }
}
