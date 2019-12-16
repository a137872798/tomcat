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
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ReadListener;

import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.res.StringManager;

/**
 * This is a low-level, efficient representation of a server request. Most
 * fields are GC-free, expensive operations are delayed until the  user code
 * needs the information.
 *
 * Processing is delegated to modules, using a hook mechanism.
 *
 * This class is not intended for user code - it is used internally by tomcat
 * for processing the request in the most efficient way. Users ( servlets ) can
 * access the information using a facade, which provides the high-level view
 * of the request.
 *
 * Tomcat defines a number of attributes:
 * <ul>
 *   <li>"org.apache.tomcat.request" - allows access to the low-level
 *       request object in trusted applications
 * </ul>
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 * @author Harish Prabandham
 * @author Alex Cruikshank [alex@epitonic.com]
 * @author Hans Bergsten [hans@gefionsoftware.com]
 * @author Costin Manolache
 * @author Remy Maucherat
 * 抽象成 servlet 的请求对象
 */
public final class Request {

    private static final StringManager sm = StringManager.getManager(Request.class);

    // Expected maximum typical number of cookies per request.    每个请求 初始值允许携带 4k 的cookie
    private static final int INITIAL_COOKIE_SIZE = 4;

    // ----------------------------------------------------------- Constructors

    public Request() {
        parameters.setQuery(queryMB);
        parameters.setURLDecoder(urlDecoder);
    }


    // ----------------------------------------------------- Instance Variables

    // req 对象为什么要记录port字段???
    private int serverPort = -1;
    // 消息体对象
    private final MessageBytes serverNameMB = MessageBytes.newInstance();

    private int remotePort;
    private int localPort;

    // messageBytes 内部包含了 str byteChunk 和 charChunk 可以根据需要 对字符串做转化
    private final MessageBytes schemeMB = MessageBytes.newInstance();

    private final MessageBytes methodMB = MessageBytes.newInstance();
    private final MessageBytes uriMB = MessageBytes.newInstance();
    private final MessageBytes decodedUriMB = MessageBytes.newInstance();
    private final MessageBytes queryMB = MessageBytes.newInstance();
    private final MessageBytes protoMB = MessageBytes.newInstance();

    // remote address/host
    private final MessageBytes remoteAddrMB = MessageBytes.newInstance();
    private final MessageBytes localNameMB = MessageBytes.newInstance();
    private final MessageBytes remoteHostMB = MessageBytes.newInstance();
    private final MessageBytes localAddrMB = MessageBytes.newInstance();

    /**
     * 多媒体数据头  内部包含 多个name/value 键值对
     */
    private final MimeHeaders headers = new MimeHeaders();


    /**
     * Path parameters
     * 内部存放了 路径与参数的映射关系
     */
    private final Map<String,String> pathParameters = new HashMap<>();

    /**
     * Notes.   这是什么标记吗???
     */
    private final Object notes[] = new Object[Constants.MAX_NOTES];


    /**
     * Associated input buffer.   该接口意味着能从 buf 和 数组中读取数据
     */
    private InputBuffer inputBuffer = null;


    /**
     * URL decoder.   url 解析器对象
     */
    private final UDecoder urlDecoder = new UDecoder();


    /**
     * HTTP specific fields. (remove them ?)   http协议的 数据体大小  默认情况下没有规定大小
     */
    private long contentLength = -1;
    /**
     * contentType 内部数据使用 MessageBytes 来包装
     */
    private MessageBytes contentTypeMB = null;
    private Charset charset = null;
    // Retain the original, user specified character encoding so it can be
    // returned even if it is invalid  指定的字符集
    private String characterEncoding = null;

    /**
     * Is there an expectation ?   是否出现了异常
     */
    private boolean expectation = false;

    /**
     * 本次请求中携带的 cookie 对象
     */
    private final ServerCookies serverCookies = new ServerCookies(INITIAL_COOKIE_SIZE);
    /**
     * req 中携带的参数对象
     */
    private final Parameters parameters = new Parameters();

    private final MessageBytes remoteUser = MessageBytes.newInstance();
    private boolean remoteUserNeedsAuthorization = false;
    private final MessageBytes authType = MessageBytes.newInstance();
    /**
     * req 对象本身还携带了一组属性
     */
    private final HashMap<String,Object> attributes = new HashMap<>();

    /**
     * 该请求关联的 响应对象
     */
    private Response response;
    private volatile ActionHook hook;

    private long bytesRead=0;
    // Time of the request - useful to avoid repeated calls to System.currentTime
    private long startTime = -1;
    private int available = 0;

    /**
     * req 的详细描述信息
     */
    private final RequestInfo reqProcessorMX=new RequestInfo(this);

    /**
     * 是否发送文件是什么意思???
     */
    private boolean sendfile = true;

    /**
     * 该监听器本身是 servlet3.1 出现的东西   该接口在tomcat中没有实现类
     */
    volatile ReadListener listener;

    public ReadListener getReadListener() {
        return listener;
    }

    /**
     * 设置监听器对象
     * @param listener
     */
    public void setReadListener(ReadListener listener) {
        if (listener == null) {
            throw new NullPointerException(
                    sm.getString("request.nullReadListener"));
        }
        if (getReadListener() != null) {
            throw new IllegalStateException(
                    sm.getString("request.readListenerSet"));
        }
        // Note: This class is not used for HTTP upgrade so only need to test
        //       for async
        AtomicBoolean result = new AtomicBoolean(false);
        // 当设置监听器时 会触发一个动作   这里传入   async_is_async  和 false 是什么意思???  看来必须要在里面将 result修改成true否则会抛出异常
        action(ActionCode.ASYNC_IS_ASYNC, result);
        if (!result.get()) {
            throw new IllegalStateException(
                    sm.getString("request.notAsync"));
        }

        this.listener = listener;
    }

    /**
     * 是否发送了  已读取全部数据的事件
     */
    private final AtomicBoolean allDataReadEventSent = new AtomicBoolean(false);

    public boolean sendAllDataReadEvent() {
        return allDataReadEventSent.compareAndSet(false, true);
    }


    // ------------------------------------------------------------- Properties

    public MimeHeaders getMimeHeaders() {
        return headers;
    }


    public UDecoder getURLDecoder() {
        return urlDecoder;
    }


    // -------------------- Request data --------------------

    public MessageBytes scheme() {
        return schemeMB;
    }

    public MessageBytes method() {
        return methodMB;
    }

    public MessageBytes requestURI() {
        return uriMB;
    }

    public MessageBytes decodedURI() {
        return decodedUriMB;
    }

    public MessageBytes queryString() {
        return queryMB;
    }

    public MessageBytes protocol() {
        return protoMB;
    }

    /**
     * Get the "virtual host", derived from the Host: header associated with
     * this request.
     *
     * @return The buffer holding the server name, if any. Use isNull() to check
     *         if there is no value set.
     */
    public MessageBytes serverName() {
        return serverNameMB;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort ) {
        this.serverPort=serverPort;
    }

    public MessageBytes remoteAddr() {
        return remoteAddrMB;
    }

    public MessageBytes remoteHost() {
        return remoteHostMB;
    }

    public MessageBytes localName() {
        return localNameMB;
    }

    public MessageBytes localAddr() {
        return localAddrMB;
    }

    public int getRemotePort(){
        return remotePort;
    }

    public void setRemotePort(int port){
        this.remotePort = port;
    }

    public int getLocalPort(){
        return localPort;
    }

    public void setLocalPort(int port){
        this.localPort = port;
    }


    // -------------------- encoding/type --------------------

    /**
     * Get the character encoding used for this request.
     *
     * @return The value set via {@link #setCharacterEncoding(String)} or if no
     *         call has been made to that method try to obtain if from the
     *         content type.
     *         获取指定的字符集
     */
    public String getCharacterEncoding() {
        if (characterEncoding == null) {
            // 当req 自身的 字符集没有被指定时 尝试从 contentType 中确定
            characterEncoding = getCharsetFromContentType(getContentType());
        }

        return characterEncoding;
    }


    /**
     * Get the character encoding used for this request.
     *
     * @return The value set via {@link #setCharacterEncoding(String)} or if no
     *         call has been made to that method try to obtain if from the
     *         content type.
     *
     * @throws UnsupportedEncodingException If the user agent has specified an
     *         invalid character encoding
     *         获取字符集
     */
    public Charset getCharset() throws UnsupportedEncodingException {
        if (charset == null) {
            // 在没有指定字符集的情况下 尝试获取 characterEncoding
            getCharacterEncoding();
            if (characterEncoding != null) {
                // 将 字符串转换成 字符集对象   实际上 Charset 有一个对外的api forName 可以根据name来查找对应的字符集
                charset = B2CConverter.getCharset(characterEncoding);
            }
        }

        return charset;
    }


    /**
     * @param enc The new encoding
     *
     * @throws UnsupportedEncodingException If the encoding is invalid
     *
     * @deprecated This method will be removed in Tomcat 9.0.x
     */
    @Deprecated
    public void setCharacterEncoding(String enc) throws UnsupportedEncodingException {
        setCharset(B2CConverter.getCharset(enc));
    }


    /**
     * 设置字符集
     * @param charset
     */
    public void setCharset(Charset charset) {
        this.charset = charset;
        this.characterEncoding = charset.name();
    }


    public void setContentLength(long len) {
        this.contentLength = len;
    }


    public int getContentLength() {
        long length = getContentLengthLong();

        if (length < Integer.MAX_VALUE) {
            return (int) length;
        }
        return -1;
    }

    public long getContentLengthLong() {
        if( contentLength > -1 ) {
            return contentLength;
        }

        MessageBytes clB = headers.getUniqueValue("content-length");
        contentLength = (clB == null || clB.isNull()) ? -1 : clB.getLong();

        return contentLength;
    }

    /**
     * 获取 contentType的内容
     * @return
     */
    public String getContentType() {
        contentType();
        if ((contentTypeMB == null) || contentTypeMB.isNull()) {
            return null;
        }
        return contentTypeMB.toString();
    }


    public void setContentType(String type) {
        contentTypeMB.setString(type);
    }


    public MessageBytes contentType() {
        if (contentTypeMB == null) {
            // 从头部 获取 content-type 对应的值
            contentTypeMB = headers.getValue("content-type");
        }
        return contentTypeMB;
    }


    public void setContentType(MessageBytes mb) {
        contentTypeMB=mb;
    }


    public String getHeader(String name) {
        return headers.getHeader(name);
    }


    public void setExpectation(boolean expectation) {
        this.expectation = expectation;
    }


    public boolean hasExpectation() {
        return expectation;
    }


    // -------------------- Associated response --------------------

    public Response getResponse() {
        return response;
    }

    public void setResponse(Response response) {
        this.response = response;
        response.setRequest(this);
    }

    protected void setHook(ActionHook hook) {
        this.hook = hook;
    }

    /**
     * 按照code类型来触发钩子
     * @param actionCode
     * @param param
     */
    public void action(ActionCode actionCode, Object param) {
        if (hook != null) {
            // 默认情况下 param 为自身
            if (param == null) {
                hook.action(actionCode, this);
            } else {
                hook.action(actionCode, param);
            }
        }
    }


    // -------------------- Cookies --------------------

    /**
     * 获取 请求体内部的 serverCookie 对象  该对象内部包含了 name/value 以及重要的 domain属性 用于标记 该cookie 的作用范围
     * @return
     */
    public ServerCookies getCookies() {
        return serverCookies;
    }


    // -------------------- Parameters --------------------

    public Parameters getParameters() {
        return parameters;
    }


    /**
     * 将 路径参数设置到 pathParameters 中
     * @param name
     * @param value
     */
    public void addPathParameter(String name, String value) {
        pathParameters.put(name, value);
    }

    public String getPathParameter(String name) {
        return pathParameters.get(name);
    }


    // -------------------- Other attributes --------------------
    // We can use notes for most - need to discuss what is of general interest

    public void setAttribute( String name, Object o ) {
        attributes.put( name, o );
    }

    public HashMap<String,Object> getAttributes() {
        return attributes;
    }

    public Object getAttribute(String name ) {
        return attributes.get(name);
    }

    /**
     * 远端用户是什么 意思  不过推测是遵循servlet规范创建的吧
     * @return
     */
    public MessageBytes getRemoteUser() {
        return remoteUser;
    }

    public boolean getRemoteUserNeedsAuthorization() {
        return remoteUserNeedsAuthorization;
    }

    public void setRemoteUserNeedsAuthorization(boolean remoteUserNeedsAuthorization) {
        this.remoteUserNeedsAuthorization = remoteUserNeedsAuthorization;
    }

    public MessageBytes getAuthType() {
        return authType;
    }

    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public boolean getSendfile() {
        return sendfile;
    }

    public void setSendfile(boolean sendfile) {
        this.sendfile = sendfile;
    }

    public boolean isFinished() {
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.REQUEST_BODY_FULLY_READ, result);
        return result.get();
    }

    /**
     * 判断是否支持 相对路径重定向???
     * @return
     */
    public boolean getSupportsRelativeRedirects() {
        if (protocol().equals("") || protocol().equals("HTTP/1.0")) {
            return false;
        }
        return true;
    }


    // -------------------- Input Buffer --------------------

    public InputBuffer getInputBuffer() {
        return inputBuffer;
    }


    public void setInputBuffer(InputBuffer inputBuffer) {
        this.inputBuffer = inputBuffer;
    }


    /**
     * Read data from the input buffer and put it into a byte chunk.
     *
     * The buffer is owned by the protocol implementation - it will be reused on
     * the next read. The Adapter must either process the data in place or copy
     * it to a separate buffer if it needs to hold it. In most cases this is
     * done during byte-&gt;char conversions or via InputStream. Unlike
     * InputStream, this interface allows the app to process data in place,
     * without copy.
     *
     * @param chunk The destination to which to copy the data
     *
     * @return The number of bytes copied
     *
     * @throws IOException If an I/O error occurs during the copy
     *
     * @deprecated Unused. Will be removed in Tomcat 9. Use
     *             {@link #doRead(ApplicationBufferHandler)}
     *             使用 inputBuffer 读取chunk 中的数据
     */
    @Deprecated
    public int doRead(ByteChunk chunk) throws IOException {
        int n = inputBuffer.doRead(chunk);
        if (n > 0) {
            bytesRead+=n;
        }
        return n;
    }


    /**
     * Read data from the input buffer and put it into ApplicationBufferHandler.
     *
     * The buffer is owned by the protocol implementation - it will be reused on
     * the next read. The Adapter must either process the data in place or copy
     * it to a separate buffer if it needs to hold it. In most cases this is
     * done during byte-&gt;char conversions or via InputStream. Unlike
     * InputStream, this interface allows the app to process data in place,
     * without copy.
     *
     * @param handler The destination to which to copy the data
     *
     * @return The number of bytes copied
     *
     * @throws IOException If an I/O error occurs during the copy
     */
    public int doRead(ApplicationBufferHandler handler) throws IOException {
        int n = inputBuffer.doRead(handler);
        if (n > 0) {
            bytesRead+=n;
        }
        return n;
    }


    // -------------------- debug --------------------

    @Override
    public String toString() {
        return "R( " + requestURI().toString() + ")";
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    // -------------------- Per-Request "notes" --------------------


    /**
     * Used to store private data. Thread data could be used instead - but
     * if you have the req, getting/setting a note is just a array access, may
     * be faster than ThreadLocal for very frequent operations.
     *
     *  Example use:
     *   Catalina CoyoteAdapter:
     *      ADAPTER_NOTES = 1 - stores the HttpServletRequest object ( req/res)
     *
     *   To avoid conflicts, note in the range 0 - 8 are reserved for the
     *   servlet container ( catalina connector, etc ), and values in 9 - 16
     *   for connector use.
     *
     *   17-31 range is not allocated or used.
     *
     * @param pos Index to use to store the note
     * @param value The value to store at that index
     */
    public final void setNote(int pos, Object value) {
        notes[pos] = value;
    }


    public final Object getNote(int pos) {
        return notes[pos];
    }


    // -------------------- Recycling --------------------


    public void recycle() {
        bytesRead=0;

        contentLength = -1;
        contentTypeMB = null;
        charset = null;
        characterEncoding = null;
        expectation = false;
        headers.recycle();
        serverNameMB.recycle();
        serverPort=-1;
        localAddrMB.recycle();
        localNameMB.recycle();
        localPort = -1;
        remoteAddrMB.recycle();
        remoteHostMB.recycle();
        remotePort = -1;
        available = 0;
        sendfile = true;

        serverCookies.recycle();
        parameters.recycle();
        pathParameters.clear();

        uriMB.recycle();
        decodedUriMB.recycle();
        queryMB.recycle();
        methodMB.recycle();
        protoMB.recycle();

        schemeMB.recycle();

        remoteUser.recycle();
        remoteUserNeedsAuthorization = false;
        authType.recycle();
        attributes.clear();

        listener = null;
        allDataReadEventSent.set(false);

        startTime = -1;
    }

    // -------------------- Info  --------------------
    public void updateCounters() {
        reqProcessorMX.updateCounters();
    }

    public RequestInfo getRequestProcessor() {
        return reqProcessorMX;
    }

    public long getBytesRead() {
        return bytesRead;
    }

    public boolean isProcessing() {
        return reqProcessorMX.getStage()==org.apache.coyote.Constants.STAGE_SERVICE;
    }

    /**
     * Parse the character encoding from the specified content type header.
     * If the content type is null, or there is no explicit character encoding,
     * <code>null</code> is returned.
     *
     * @param contentType a content type header
     *                    通过指定 contentType 来获取对应的 字符集  一般都是  Application/json;charset=UTF-8
     */
    private static String getCharsetFromContentType(String contentType) {

        if (contentType == null) {
            return null;
        }
        // 如果 contentType 中携带了 charset 那么 就可以确定contentType 中携带了 字符集
        int start = contentType.indexOf("charset=");
        if (start < 0) {
            return null;
        }
        String encoding = contentType.substring(start + 8);
        // 如果 = 后面的内容 有; 分割 那么就获取前面的字符
        int end = encoding.indexOf(';');
        if (end >= 0) {
            encoding = encoding.substring(0, end);
        }
        encoding = encoding.trim();
        // 如果携带转义符 那么去除掉
        if ((encoding.length() > 2) && (encoding.startsWith("\""))
            && (encoding.endsWith("\""))) {
            encoding = encoding.substring(1, encoding.length() - 1);
        }

        return encoding.trim();
    }
}
