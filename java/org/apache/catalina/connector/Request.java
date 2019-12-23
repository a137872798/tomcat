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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.naming.NamingException;
import javax.security.auth.Subject;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.FilterChain;
import javax.servlet.MultipartConfigElement;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletResponse;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.Manager;
import org.apache.catalina.Realm;
import org.apache.catalina.Session;
import org.apache.catalina.TomcatPrincipal;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.ApplicationFilterChain;
import org.apache.catalina.core.ApplicationMapping;
import org.apache.catalina.core.ApplicationPart;
import org.apache.catalina.core.ApplicationPushBuilder;
import org.apache.catalina.core.ApplicationSessionCookieConfig;
import org.apache.catalina.core.AsyncContextImpl;
import org.apache.catalina.mapper.MappingData;
import org.apache.catalina.servlet4preview.http.HttpServletMapping;
import org.apache.catalina.servlet4preview.http.PushBuilder;
import org.apache.catalina.util.ParameterMap;
import org.apache.catalina.util.TLSUtil;
import org.apache.catalina.util.URLEncoder;
import org.apache.coyote.ActionCode;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.CookieProcessor;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.Parameters.FailReason;
import org.apache.tomcat.util.http.ServerCookie;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.http.fileupload.FileItem;
import org.apache.tomcat.util.http.fileupload.FileUploadBase;
import org.apache.tomcat.util.http.fileupload.FileUploadBase.InvalidContentTypeException;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.disk.DiskFileItemFactory;
import org.apache.tomcat.util.http.fileupload.servlet.ServletFileUpload;
import org.apache.tomcat.util.http.fileupload.servlet.ServletRequestContext;
import org.apache.tomcat.util.http.parser.AcceptLanguage;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.res.StringManager;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;

/**
 * Wrapper object for the Coyote request.
 * 该对象内部包装了 coyote 的 req 同时实现了servlet的标准接口 (HttpServletRequest)
 * @author Remy Maucherat
 * @author Craig R. McClanahan
 *
 * 在2016 年 更新了该对象的 实现接口 用于兼容 servlet 4.0
 */
public class Request implements org.apache.catalina.servlet4preview.http.HttpServletRequest {

    private static final Log log = LogFactory.getLog(Request.class);

    // ----------------------------------------------------------- Constructors

    /**
     * 初始化 request 对象
     */
    public Request() {
        // 这里存储了常用的模板
        formats = new SimpleDateFormat[formatsTemplate.length];
        for(int i = 0; i < formats.length; i++) {
            // 可能 simpleDateFormat是非线程安全的吧  所以这里要单独维护一份数据
            formats[i] = (SimpleDateFormat) formatsTemplate[i].clone();
        }
    }


    // ------------------------------------------------------------- Properties


    /**
     * Coyote request.
     * 内部包含了 coyote.Request
     */
    protected org.apache.coyote.Request coyoteRequest;

    /**
     * Set the Coyote request.
     * 将 coyote 的 req 对象设置到 req 中
     * @param coyoteRequest The Coyote request
     */
    public void setCoyoteRequest(org.apache.coyote.Request coyoteRequest) {
        this.coyoteRequest = coyoteRequest;
        inputBuffer.setRequest(coyoteRequest);
    }

    /**
     * Get the Coyote request.
     *
     * @return the Coyote request object
     */
    public org.apache.coyote.Request getCoyoteRequest() {
        return this.coyoteRequest;
    }


    // ----------------------------------------------------- Variables

    /**
     * @deprecated Unused. This will be removed in Tomcat 10.
     */
    @Deprecated
    protected static final TimeZone GMT_ZONE = TimeZone.getTimeZone("GMT");


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(Request.class);


    /**
     * The set of cookies associated with this Request.
     * 对外暴露的 req 对象 不同与  coyote.Request  该 cookie 对象内部只有很简单的一些基本属性
     */
    protected Cookie[] cookies = null;


    /**
     * The set of SimpleDateFormat formats to use in getDateHeader().
     *
     * Notice that because SimpleDateFormat is not thread-safe, we can't
     * declare formats[] as a static variable.
     *
     * @deprecated Unused. This will be removed in Tomcat 10
     */
    @Deprecated
    protected final SimpleDateFormat formats[];

    /**
     * 存储了常用的 时间模板
     */
    @Deprecated
    private static final SimpleDateFormat formatsTemplate[] = {
        new SimpleDateFormat(FastHttpDateFormat.RFC1123_DATE, Locale.US),
        new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
        new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.US)
    };


    /**
     * The default Locale if none are specified.
     */
    protected static final Locale defaultLocale = Locale.getDefault();


    /**
     * The attributes associated with this Request, keyed by attribute name.
     * 每个req 对象上还可以携带一组 attr
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();


    /**
     * Flag that indicates if SSL attributes have been parsed to improve
     * performance for applications (usually frameworks) that make multiple
     * calls to {@link Request#getAttributeNames()}.
     */
    protected boolean sslAttributesParsed = false;


    /**
     * The preferred Locales associated with this Request.
     */
    protected final ArrayList<Locale> locales = new ArrayList<>();


    /**
     * Internal notes associated with this request by Catalina components
     * and event listeners.
     */
    private final transient HashMap<String, Object> notes = new HashMap<>();


    /**
     * Authentication type.
     */
    protected String authType = null;


    /**
     * The current dispatcher type.
     * 该请求的分发类型 比如 error会分发到 errorPage  而 request 会分发到servlet
     */
    protected DispatcherType internalDispatcherType = null;


    /**
     * The associated input buffer.
     * inputBuffer 对象内部 也会包含一个 coyote.Request 对象
     */
    protected final InputBuffer inputBuffer = new InputBuffer();


    /**
     * ServletInputStream.
     * 将 inputBuffer 包装成一个 输入流 读取数据 实际上就是从inputBuffer 中读取
     */
    protected CoyoteInputStream inputStream =
            new CoyoteInputStream(inputBuffer);


    /**
     * Reader.
     * 跟 coyoteInputStream 类似不过是字符流而不是字节流  包含一个 readLine 方法
     */
    protected CoyoteReader reader = new CoyoteReader(inputBuffer);


    /**
     * Using stream flag.
     * 代表当前使用的是 coyoteInputStream
     */
    protected boolean usingInputStream = false;


    /**
     * Using reader flag.
     * 代表当前使用的是 coyoteReader  这样就不需要使用字符集处理了
     */
    protected boolean usingReader = false;


    /**
     * User principal.
     */
    protected Principal userPrincipal = null;


    /**
     * Request parameters parsed flag.
     * 是否解析过参数
     */
    protected boolean parametersParsed = false;


    /**
     * Cookie headers parsed flag. Indicates that the cookie headers have been
     * parsed into ServerCookies.
     * 是否解析过cookie
     */
    protected boolean cookiesParsed = false;


    /**
     * Cookie parsed flag. Indicates that the ServerCookies have been converted
     * into user facing Cookie objects.
     * 是否将 ServerCookie 解析成了 门面类  Cookie 对象
     */
    protected boolean cookiesConverted = false;


    /**
     * Secure flag.
     * 是否使用了 SSL 通道
     */
    protected boolean secure = false;


    /**
     * The Subject associated with the current AccessControlContext
     */
    protected transient Subject subject = null;


    /**
     * Post data buffer.   POST 请求允许传入的最大数据大小为 1k
     */
    protected static final int CACHED_POST_LEN = 8192;
    /**
     * postData 应该是代表 POST 请求时 表单数据的内容
     */
    protected byte[] postData = null;


    /**
     * Hash map used in the getParametersMap method.
     * 简单的对一个 hashmap 的写操作上锁  同时尝试迭代内部元素时 如果发现当前对象上锁 那么 会返回一个视图对象 避免在并发操作中修改容器
     */
    protected ParameterMap<String, String[]> parameterMap = new ParameterMap<>();


    /**
     * The parts, if any, uploaded with this request.
     * 本次请求携带的文件片段
     */
    protected Collection<Part> parts = null;


    /**
     * The exception thrown, if any when parsing the parts.
     * 当解析part 时如果遇到异常 设置该属性
     */
    protected Exception partsParseException = null;


    /**
     * The currently active session for this request.
     * 当前请求关联的session 对象
     */
    protected Session session = null;


    /**
     * The current request dispatcher path.
     * 当前req 被分发的路径
     */
    protected Object requestDispatcherPath = null;


    /**
     * Was the requested session ID received in a cookie?
     * 是否需要从 cookie中获取 sessionId
     */
    protected boolean requestedSessionCookie = false;


    /**
     * The requested session ID (if any) for this request.
     * 本次req 携带的sessionId
     */
    protected String requestedSessionId = null;


    /**
     * Was the requested session ID received in a URL?
     * 是否要从 url 上获取 sessionId
     */
    protected boolean requestedSessionURL = false;


    /**
     * Was the requested session ID obtained from the SSL session?
     */
    protected boolean requestedSessionSSL = false;


    /**
     * Parse locales.
     */
    protected boolean localesParsed = false;


    /**
     * Local port
     */
    protected int localPort = -1;

    /**
     * Remote address.
     */
    protected String remoteAddr = null;


    /**
     * Remote host.
     */
    protected String remoteHost = null;


    /**
     * Remote port
     */
    protected int remotePort = -1;

    /**
     * Local address
     */
    protected String localAddr = null;


    /**
     * Local address
     */
    protected String localName = null;

    /**
     * AsyncContext  异步相关的先不看
     */
    private volatile AsyncContextImpl asyncContext = null;

    /**
     * 本req 对象 是否支持异步处理
     */
    protected Boolean asyncSupported = null;

    /**
     * 内部包含的 servlet.req
     */
    private HttpServletRequest applicationRequest = null;


    // --------------------------------------------------------- Public Methods

    /**
     * coyoteRequest 中 path相关参数 是一个 hashMap 这里是将数据插入到 map 中
     * @param name
     * @param value
     */
    protected void addPathParameter(String name, String value) {
        coyoteRequest.addPathParameter(name, value);
    }

    /**
     * 从 coyote.req 中获取路径参数
     * @param name
     * @return
     */
    protected String getPathParameter(String name) {
        return coyoteRequest.getPathParameter(name);
    }

    /**
     * 设置是否支持异步
     * @param asyncSupported
     */
    public void setAsyncSupported(boolean asyncSupported) {
        this.asyncSupported = Boolean.valueOf(asyncSupported);
    }

    /**
     * Release all object references, and initialize instance variables, in
     * preparation for reuse of this object.
     * 将该对象的引用置空后存储起来 避免重复创建
     */
    public void recycle() {

        internalDispatcherType = null;
        requestDispatcherPath = null;

        authType = null;
        inputBuffer.recycle();
        usingInputStream = false;
        usingReader = false;
        userPrincipal = null;
        subject = null;
        parametersParsed = false;
        if (parts != null) {
            // 如果存在文件碎片 进行删除
            for (Part part: parts) {
                try {
                    part.delete();
                } catch (IOException ignored) {
                    // ApplicationPart.delete() never throws an IOEx
                }
            }
            parts = null;
        }
        partsParseException = null;
        locales.clear();
        localesParsed = false;
        secure = false;
        remoteAddr = null;
        remoteHost = null;
        remotePort = -1;
        localPort = -1;
        localAddr = null;
        localName = null;

        attributes.clear();
        sslAttributesParsed = false;
        notes.clear();

        // 更新session的最后访问时间  并置空 该req 对象中与session 有关的配置
        recycleSessionInfo();
        // 回收所有cookie
        recycleCookieInfo(false);

        if (Globals.IS_SECURITY_ENABLED || Connector.RECYCLE_FACADES) {
            // 这里初始化了一个 paramMap
            parameterMap = new ParameterMap<>();
        } else {
            // 这里 该对象本身没有回收只是 置空了内部真正的map 对象
            parameterMap.setLocked(false);
            parameterMap.clear();
        }

        // 回收 mappingData 对象  该对象内部有很多描述 path 的 MessageBytes
        mappingData.recycle();
        // 将appMapping 也回收 该对象是用来匹配 path 与 servlet 的关系的 比如哪个路径应该由哪个servlet 处理
        applicationMapping.recycle();

        // servlet 传播过来的req 对象  这里也置空
        applicationRequest = null;
        if (Globals.IS_SECURITY_ENABLED || Connector.RECYCLE_FACADES) {
            if (facade != null) {
                // 此门面对象 内部包装的就是自身 不过对外隐藏了接口 这样用户就不能通过强转访问到一些不该访问的属性
                facade.clear();
                facade = null;
            }
            // 将输入流清空
            if (inputStream != null) {
                inputStream.clear();
                inputStream = null;
            }
            if (reader != null) {
                reader.clear();
                reader = null;
            }
        }

        asyncSupported = null;
        // 回收异步上下文对象
        if (asyncContext!=null) {
            asyncContext.recycle();
        }
        asyncContext = null;
    }


    /**
     * 准备回收session 信息
     */
    protected void recycleSessionInfo() {
        if (session != null) {
            try {
                session.endAccess();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.warn(sm.getString("coyoteRequest.sessionEndAccessFail"), t);
            }
        }
        session = null;
        requestedSessionCookie = false;
        requestedSessionId = null;
        requestedSessionURL = false;
        requestedSessionSSL = false;
    }


    /**
     * 代表是否要回收 cookie
     * @param recycleCoyote
     */
    protected void recycleCookieInfo(boolean recycleCoyote) {
        cookiesParsed = false;
        cookiesConverted = false;
        cookies = null;
        if (recycleCoyote) {
            getCoyoteRequest().getCookies().recycle();
        }
    }


    // -------------------------------------------------------- Request Methods

    /**
     * Associated Catalina connector.
     * 内部关联的连接对象 实际上 数据流就是通过connector 变成req的 这里又要转发给 container
     */
    protected Connector connector;

    /**
     * @return the Connector through which this Request was received.
     */
    public Connector getConnector() {
        return this.connector;
    }

    /**
     * Set the Connector through which this Request was received.
     *
     * @param connector The new connector
     */
    public void setConnector(Connector connector) {
        this.connector = connector;
    }


    /**
     * Return the Context within which this Request is being processed.
     * <p>
     * This is available as soon as the appropriate Context is identified.
     * Note that availability of a Context allows <code>getContextPath()</code>
     * to return a value, and thus enables parsing of the request URI.
     *
     * @return the Context mapped with the request
     * 获取 context 级别的容器
     */
    public Context getContext() {
        return mappingData.context;
    }


    /**
     * @param context The newly associated Context
     * @deprecated Use setters on {@link #getMappingData() MappingData} object.
     * Depending on use case, you may need to update other
     * <code>MappingData</code> fields as well, such as
     * <code>contextSlashCount</code> and <code>host</code>.
     */
    @Deprecated
    public void setContext(Context context) {
        mappingData.context = context;
    }


    /**
     * Filter chain associated with the request.
     * 之后用于处理该req 的过滤链  当链式处理完成后 就会交由servlet
     */
    protected FilterChain filterChain = null;

    /**
     * Get filter chain associated with the request.
     * 获取绑定在该请求上的过滤链
     * @return the associated filter chain
     */
    public FilterChain getFilterChain() {
        return this.filterChain;
    }

    /**
     * Set filter chain associated with the request.
     *
     * @param filterChain new filter chain
     */
    public void setFilterChain(FilterChain filterChain) {
        this.filterChain = filterChain;
    }


    /**
     * @return the Host within which this Request is being processed.
     * 每个tomcat req 对象内包含了 host context wrapper
     */
    public Host getHost() {
        return mappingData.host;
    }


    /**
     * Mapping data.
     * req 的映射相关信息
     */
    protected final MappingData mappingData = new MappingData();
    private final ApplicationMapping applicationMapping = new ApplicationMapping(mappingData);

    /**
     * @return mapping data.
     */
    public MappingData getMappingData() {
        return mappingData;
    }


    /**
     * The facade associated with this request.
     */
    protected RequestFacade facade = null;


    /**
     * @return the <code>ServletRequest</code> for which this object
     * is the facade.  This method must be implemented by a subclass.
     * 获取内部的 httpServletRequest 对象
     */
    public HttpServletRequest getRequest() {
        // 这里采用门面模式 加工自身
        if (facade == null) {
            facade = new RequestFacade(this);
        }
        if (applicationRequest == null) {
            applicationRequest = facade;
        }
        return applicationRequest;
    }


    /**
     * Set a wrapped HttpServletRequest to pass to the application. Components
     * wishing to wrap the request should obtain the request via
     * {@link #getRequest()}, wrap it and then call this method with the
     * wrapped request.
     *
     * @param applicationRequest The wrapped request to pass to the application
     */
    public void setRequest(HttpServletRequest applicationRequest) {
        // Check the wrapper wraps this request
        ServletRequest r = applicationRequest;
        // 解包装
        while (r instanceof HttpServletRequestWrapper) {
            r = ((HttpServletRequestWrapper) r).getRequest();
        }
        // 这里是什么意思 必须要是门面对象才可以???
        if (r != facade) {
            throw new IllegalArgumentException(sm.getString("request.illegalWrap"));
        }
        this.applicationRequest = applicationRequest;
    }


    /**
     * The response with which this request is associated.
     * 该请求对象关联的res
     */
    protected org.apache.catalina.connector.Response response = null;

    /**
     * @return the Response with which this Request is associated.
     */
    public org.apache.catalina.connector.Response getResponse() {
        return this.response;
    }

    /**
     * Set the Response with which this Request is associated.
     *
     * @param response The new associated response
     */
    public void setResponse(org.apache.catalina.connector.Response response) {
        this.response = response;
    }

    /**
     * @return the input stream associated with this Request.
     */
    public InputStream getStream() {
        if (inputStream == null) {
            // coyoteInputStream 就是封装了 访问 inputBuffer 的逻辑
            inputStream = new CoyoteInputStream(inputBuffer);
        }
        return inputStream;
    }

    /**
     * URI byte to char converter.
     * 一个 字符转换器
     */
    protected B2CConverter URIConverter = null;

    /**
     * @return the URI converter.
     */
    protected B2CConverter getURIConverter() {
        return URIConverter;
    }

    /**
     * Set the URI converter.
     *
     * @param URIConverter the new URI converter
     */
    protected void setURIConverter(B2CConverter URIConverter) {
        this.URIConverter = URIConverter;
    }


    /**
     * @return the Wrapper within which this Request is being processed.
     * 代表该请求对象最终被哪个 Wrapper 处理
     */
    public Wrapper getWrapper() {
        return mappingData.wrapper;
    }


    /**
     * @param wrapper The newly associated Wrapper
     * @deprecated Use setters on {@link #getMappingData() MappingData} object.
     * Depending on use case, you may need to update other
     * <code>MappingData</code> fields as well, such as <code>context</code>
     * and <code>contextSlashCount</code>.
     */
    @Deprecated
    public void setWrapper(Wrapper wrapper) {
        mappingData.wrapper = wrapper;
    }


    // ------------------------------------------------- Request Public Methods

    /**
     * Create and return a ServletInputStream to read the content
     * associated with this Request.
     *
     * @return the created input stream
     * @exception IOException if an input/output error occurs
     */
    public ServletInputStream createInputStream()
            throws IOException {
        if (inputStream == null) {
            inputStream = new CoyoteInputStream(inputBuffer);
        }
        return inputStream;
    }


    /**
     * Perform whatever actions are required to flush and close the input
     * stream or reader, in a single operation.
     *
     * @exception IOException if an input/output error occurs
     * 结束本次请求 ???
     */
    public void finishRequest() throws IOException {
        // 如果 res的状态为 reqEntity too large
        if (response.getStatus() == HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE) {
            // 检查吞吐
            checkSwallowInput();
        }
    }


    /**
     * @return the object bound with the specified name to the internal notes
     * for this request, or <code>null</code> if no such binding exists.
     *
     * @param name Name of the note to be returned
     */
    public Object getNote(String name) {
        return notes.get(name);
    }


    /**
     * Remove any object bound to the specified name in the internal notes
     * for this request.
     * 从notes 中移除某个属性
     *
     * @param name Name of the note to be removed
     */
    public void removeNote(String name) {
        notes.remove(name);
    }


    /**
     * Set the port number of the server to process this request.
     *
     * @param port The server port
     */
    public void setLocalPort(int port) {
        localPort = port;
    }

    /**
     * Bind an object to a specified name in the internal notes associated
     * with this request, replacing any existing binding for this name.
     *
     * @param name Name to which the object should be bound
     * @param value Object to be bound to the specified name
     */
    public void setNote(String name, Object value) {
        notes.put(name, value);
    }


    /**
     * Set the IP address of the remote client associated with this Request.
     *
     * @param remoteAddr The remote IP address
     */
    public void setRemoteAddr(String remoteAddr) {
        this.remoteAddr = remoteAddr;
    }


    /**
     * Set the fully qualified name of the remote client associated with this
     * Request.
     *
     * @param remoteHost The remote host name
     */
    public void setRemoteHost(String remoteHost) {
        this.remoteHost = remoteHost;
    }


    /**
     * Set the value to be returned by <code>isSecure()</code>
     * for this Request.
     *
     * @param secure The new isSecure value
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }


    /**
     * Set the port number of the server to process this request.
     *
     * @param port The server port
     */
    public void setServerPort(int port) {
        coyoteRequest.setServerPort(port);
    }


    // ------------------------------------------------- ServletRequest Methods

    /**
     * @return the specified request attribute if it exists; otherwise, return
     * <code>null</code>.
     *
     * @param name Name of the request attribute to return
     *             获取 req 中的某个属性
     */
    @Override
    public Object getAttribute(String name) {
        // Special attributes  这里优先尝试从特殊属性中获取  这些属性是在 req 对象被创建时的静态块中设置的
        SpecialAttributeAdapter adapter = specialAttributes.get(name);
        if (adapter != null) {
            return adapter.get(this, name);
        }

        // 其次打算从 attr 中根据name 获取相关属性
        Object attr = attributes.get(name);

        if (attr != null) {
            return attr;
        }

        // 之后 打算从req 中 获取attr 属性 如果存在直接返回
        attr = coyoteRequest.getAttribute(name);
        if (attr != null) {
            return attr;
        }

        // 判断尝试获取的是否是某些特殊属性   这个先忽略吧 反正就是获取一些特殊属性
        if (TLSUtil.isTLSRequestAttribute(name)) {
            coyoteRequest.action(ActionCode.REQ_SSL_ATTRIBUTE, coyoteRequest);
            attr = coyoteRequest.getAttribute(Globals.CERTIFICATES_ATTR);
            if (attr != null) {
                attributes.put(Globals.CERTIFICATES_ATTR, attr);
            }
            attr = coyoteRequest.getAttribute(Globals.CIPHER_SUITE_ATTR);
            if (attr != null) {
                attributes.put(Globals.CIPHER_SUITE_ATTR, attr);
            }
            attr = coyoteRequest.getAttribute(Globals.KEY_SIZE_ATTR);
            if (attr != null) {
                attributes.put(Globals.KEY_SIZE_ATTR, attr);
            }
            attr = coyoteRequest.getAttribute(Globals.SSL_SESSION_ID_ATTR);
            if (attr != null) {
                attributes.put(Globals.SSL_SESSION_ID_ATTR, attr);
            }
            attr = coyoteRequest.getAttribute(Globals.SSL_SESSION_MGR_ATTR);
            if (attr != null) {
                attributes.put(Globals.SSL_SESSION_MGR_ATTR, attr);
            }
            attr = coyoteRequest.getAttribute(SSLSupport.PROTOCOL_VERSION_KEY);
            if (attr != null) {
                attributes.put(SSLSupport.PROTOCOL_VERSION_KEY, attr);
            }
            attr = attributes.get(name);
            sslAttributesParsed = true;
        }
        return attr;
    }


    /**
     * 获取内容体长度
     * @return
     */
    @Override
    public long getContentLengthLong() {
        return coyoteRequest.getContentLengthLong();
    }


    /**
     * Return the names of all request attributes for this Request, or an
     * empty <code>Enumeration</code> if there are none. Note that the attribute
     * names returned will only be those for the attributes set via
     * {@link #setAttribute(String, Object)}. Tomcat internal attributes will
     * not be included although they are accessible via
     * {@link #getAttribute(String)}. The Tomcat internal attributes include:
     * <ul>
     * <li>{@link Globals#DISPATCHER_TYPE_ATTR}</li>
     * <li>{@link Globals#DISPATCHER_REQUEST_PATH_ATTR}</li>
     * <li>{@link Globals#ASYNC_SUPPORTED_ATTR}</li>
     * <li>{@link Globals#CERTIFICATES_ATTR} (SSL connections only)</li>
     * <li>{@link Globals#CIPHER_SUITE_ATTR} (SSL connections only)</li>
     * <li>{@link Globals#KEY_SIZE_ATTR} (SSL connections only)</li>
     * <li>{@link Globals#SSL_SESSION_ID_ATTR} (SSL connections only)</li>
     * <li>{@link Globals#SSL_SESSION_MGR_ATTR} (SSL connections only)</li>
     * <li>{@link Globals#PARAMETER_PARSE_FAILED_ATTR}</li>
     * </ul>
     * The underlying connector may also expose request attributes. These all
     * have names starting with "org.apache.tomcat" and include:
     * <ul>
     * <li>{@link Globals#SENDFILE_SUPPORTED_ATTR}</li>
     * </ul>
     * Connector implementations may return some, all or none of these
     * attributes and may also support additional attributes.
     *
     * @return the attribute names enumeration
     * 尝试获取所有属性 并设置到一个 Enumeration
     */
    @Override
    public Enumeration<String> getAttributeNames() {
        if (isSecure() && !sslAttributesParsed) {
            getAttribute(Globals.CERTIFICATES_ATTR);
        }
        // Take a copy to prevent ConcurrentModificationExceptions if used to
        // remove attributes
        Set<String> names = new HashSet<>();
        // 将 本对象内部的 attrs 返回
        names.addAll(attributes.keySet());
        return Collections.enumeration(names);
    }


    /**
     * @return the character encoding for this Request.
     * 获取字符集
     */
    @Override
    public String getCharacterEncoding() {
        String characterEncoding = coyoteRequest.getCharacterEncoding();
        if (characterEncoding != null) {
            return characterEncoding;
        }

        // 如果req 设置了 那么使用req指定的字符集 否则使用 context 也就是容器默认的字符集
        Context context = getContext();
        if (context != null) {
            return context.getRequestCharacterEncoding();
        }

        return null;
    }

    /**
     * 尝试获取字符集 如果 req 对象中不存在 则尝试从 context 中获取
     * @return
     */
    private Charset getCharset() {
        Charset charset = null;
        try {
            charset = coyoteRequest.getCharset();
        } catch (UnsupportedEncodingException e) {
            // Ignore
        }
        if (charset != null) {
            return charset;
        }

        Context context = getContext();
        if (context != null) {
            String encoding = context.getRequestCharacterEncoding();
            if (encoding != null) {
                try {
                    return B2CConverter.getCharset(encoding);
                } catch (UnsupportedEncodingException e) {
                    // Ignore
                }
            }
        }

        return org.apache.coyote.Constants.DEFAULT_BODY_CHARSET;
    }


    /**
     * @return the content length for this Request.
     */
    @Override
    public int getContentLength() {
        return coyoteRequest.getContentLength();
    }


    /**
     * @return the content type for this Request.
     */
    @Override
    public String getContentType() {
        return coyoteRequest.getContentType();
    }


    /**
     * Set the content type for this Request.
     *
     * @param contentType The content type
     */
    public void setContentType(String contentType) {
        coyoteRequest.setContentType(contentType);
    }


    /**
     * @return the servlet input stream for this Request.  The default
     * implementation returns a servlet input stream created by
     * <code>createInputStream()</code>.
     *
     * @exception IllegalStateException if <code>getReader()</code> has
     *  already been called for this request
     * @exception IOException if an input/output error occurs
     * 获取输入流
     */
    @Override
    public ServletInputStream getInputStream() throws IOException {

        // 如果指定了使用reader 则不允许调用该方法
        if (usingReader) {
            throw new IllegalStateException(sm.getString("coyoteRequest.getInputStream.ise"));
        }

        usingInputStream = true;
        if (inputStream == null) {
            inputStream = new CoyoteInputStream(inputBuffer);
        }
        return inputStream;

    }


    /**
     * @return the preferred Locale that the client will accept content in,
     * based on the value for the first <code>Accept-Language</code> header
     * that was encountered.  If the request did not specify a preferred
     * language, the server's default Locale is returned.
     */
    @Override
    public Locale getLocale() {

        if (!localesParsed) {
            parseLocales();
        }

        if (locales.size() > 0) {
            return locales.get(0);
        }

        return defaultLocale;
    }


    /**
     * @return the set of preferred Locales that the client will accept
     * content in, based on the values for any <code>Accept-Language</code>
     * headers that were encountered.  If the request did not specify a
     * preferred language, the server's default Locale is returned.
     */
    @Override
    public Enumeration<Locale> getLocales() {

        if (!localesParsed) {
            parseLocales();
        }

        if (locales.size() > 0) {
            return Collections.enumeration(locales);
        }
        ArrayList<Locale> results = new ArrayList<>();
        results.add(defaultLocale);
        return Collections.enumeration(results);

    }


    /**
     * @return the value of the specified request parameter, if any; otherwise,
     * return <code>null</code>.  If there is more than one value defined,
     * return only the first one.
     *
     * @param name Name of the desired request parameter
     *             coyoteRequest 中有一个 paramMap 存放了 url 后面的 参数信息
     */
    @Override
    public String getParameter(String name) {

        // 如果参数还没有被解析 那么先解析参数
        if (!parametersParsed) {
            parseParameters();
        }

        // 从paramMap 中查询对应的参数
        return coyoteRequest.getParameters().getParameter(name);

    }



    /**
     * Returns a <code>Map</code> of the parameters of this request.
     * Request parameters are extra information sent with the request.
     * For HTTP servlets, parameters are contained in the query string
     * or posted form data.
     *
     * @return A <code>Map</code> containing parameter names as keys
     *  and parameter values as map values.
     */
    @Override
    public Map<String, String[]> getParameterMap() {

        // 可能上锁代表着数据已经从 coyoteRequest中拷贝到 paramMap 中了
        if (parameterMap.isLocked()) {
            return parameterMap;
        }

        // 将 coyoteRequest 的数据 转移到了该 req 中
        Enumeration<String> enumeration = getParameterNames();
        while (enumeration.hasMoreElements()) {
            String name = enumeration.nextElement();
            String[] values = getParameterValues(name);
            parameterMap.put(name, values);
        }

        // 当设置 locked 标识后 对map 内部数据的操作都会抛出异常 除非清除了 标记
        parameterMap.setLocked(true);

        return parameterMap;

    }


    /**
     * @return the names of all defined request parameters for this request.
     * 获取 param中所有的属性名
     */
    @Override
    public Enumeration<String> getParameterNames() {

        if (!parametersParsed) {
            parseParameters();
        }

        return coyoteRequest.getParameters().getParameterNames();

    }


    /**
     * @return the defined values for the specified request parameter, if any;
     * otherwise, return <code>null</code>.
     *
     * @param name Name of the desired request parameter
     */
    @Override
    public String[] getParameterValues(String name) {

        if (!parametersParsed) {
            parseParameters();
        }

        return coyoteRequest.getParameters().getParameterValues(name);

    }


    /**
     * @return the protocol and version used to make this Request.
     * 获取 请求对象使用的协议
     */
    @Override
    public String getProtocol() {
        return coyoteRequest.protocol().toString();
    }


    /**
     * Read the Reader wrapping the input stream for this Request.  The
     * default implementation wraps a <code>BufferedReader</code> around the
     * servlet input stream returned by <code>createInputStream()</code>.
     *
     * @return a buffered reader for the request
     * @exception IllegalStateException if <code>getInputStream()</code>
     *  has already been called for this request
     * @exception IOException if an input/output error occurs
     * 获取一个 reader 对象
     */
    @Override
    public BufferedReader getReader() throws IOException {

        // 如果指定了使用 inputStream 则抛出异常
        if (usingInputStream) {
            throw new IllegalStateException(sm.getString("coyoteRequest.getReader.ise"));
        }

        usingReader = true;
        // 初始化 converter 对象
        inputBuffer.checkConverter();
        if (reader == null) {
            reader = new CoyoteReader(inputBuffer);
        }
        return reader;
    }


    /**
     * @return the real path of the specified virtual path.
     *
     * @param path Path to be translated
     *
     * @deprecated As of version 2.1 of the Java Servlet API, use
     *  <code>ServletContext.getRealPath()</code>.
     */
    @Override
    @Deprecated
    public String getRealPath(String path) {

        Context context = getContext();
        if (context == null) {
            return null;
        }
        ServletContext servletContext = context.getServletContext();
        if (servletContext == null) {
            return null;
        }

        try {
            return servletContext.getRealPath(path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }


    /**
     * @return the remote IP address making this Request.
     * 获取远端地址  从req 中获取   coyoteRequest 应该就是 connector 封装成的吧
     */
    @Override
    public String getRemoteAddr() {
        if (remoteAddr == null) {
            coyoteRequest.action(ActionCode.REQ_HOST_ADDR_ATTRIBUTE, coyoteRequest);
            remoteAddr = coyoteRequest.remoteAddr().toString();
        }
        return remoteAddr;
    }


    /**
     * @return the remote host name making this Request.
     * 获取远端主机   同样从req 中获取
     */
    @Override
    public String getRemoteHost() {
        if (remoteHost == null) {
            if (!connector.getEnableLookups()) {
                remoteHost = getRemoteAddr();
            } else {
                // action 会转发到 req.hook 上 实际上 hook 实现类 为 各种协议对应的processor 比如 Http11Processor
                coyoteRequest.action(ActionCode.REQ_HOST_ATTRIBUTE, coyoteRequest);
                remoteHost = coyoteRequest.remoteHost().toString();
            }
        }
        return remoteHost;
    }

    /**
     * @return the Internet Protocol (IP) source port of the client
     * or last proxy that sent the request.
     */
    @Override
    public int getRemotePort(){
        if (remotePort == -1) {
            // 对应属性的首次初始化 会触发一次 hook
            coyoteRequest.action(ActionCode.REQ_REMOTEPORT_ATTRIBUTE, coyoteRequest);
            remotePort = coyoteRequest.getRemotePort();
        }
        return remotePort;
    }

    /**
     * @return the host name of the Internet Protocol (IP) interface on
     * which the request was received.
     */
    @Override
    public String getLocalName(){
        if (localName == null) {
            coyoteRequest.action(ActionCode.REQ_LOCAL_NAME_ATTRIBUTE, coyoteRequest);
            localName = coyoteRequest.localName().toString();
        }
        return localName;
    }

    /**
     * @return the Internet Protocol (IP) address of the interface on
     * which the request  was received.
     */
    @Override
    public String getLocalAddr(){
        if (localAddr == null) {
            coyoteRequest.action(ActionCode.REQ_LOCAL_ADDR_ATTRIBUTE, coyoteRequest);
            localAddr = coyoteRequest.localAddr().toString();
        }
        return localAddr;
    }


    /**
     * @return the Internet Protocol (IP) port number of the interface
     * on which the request was received.
     */
    @Override
    public int getLocalPort(){
        if (localPort == -1){
            coyoteRequest.action(ActionCode.REQ_LOCALPORT_ATTRIBUTE, coyoteRequest);
            localPort = coyoteRequest.getLocalPort();
        }
        return localPort;
    }

    /**
     * @return a RequestDispatcher that wraps the resource at the specified
     * path, which may be interpreted as relative to the current request path.
     *
     * @param path Path of the resource to be wrapped
     *             根据路径获取 请求分发对象
     */
    @Override
    public RequestDispatcher getRequestDispatcher(String path) {

        Context context = getContext();
        if (context == null) {
            return null;
        }

        if (path == null) {
            return null;
        }

        int fragmentPos = path.indexOf('#');
        if (fragmentPos > -1) {
            log.warn(sm.getString("request.fragmentInDispatchPath", path));
            path = path.substring(0, fragmentPos);
        }

        // If the path is already context-relative, just pass it through  如果已经是一个相对路径了 直接进行分发就好
        if (path.startsWith("/")) {
            return context.getServletContext().getRequestDispatcher(path);
        }

        /*
         * Relative to what, exactly?
         *
         * From the Servlet 4.0 Javadoc:
         * - The pathname specified may be relative, although it cannot extend
         *   outside the current servlet context.
         * - If it is relative, it must be relative against the current servlet
         *
         * From Section 9.1 of the spec:
         * - The servlet container uses information in the request object to
         *   transform the given relative path against the current servlet to a
         *   complete path.
         *
         * It is undefined whether the requestURI is used or whether servletPath
         * and pathInfo are used. Given that the RequestURI includes the
         * contextPath (and extracting that is messy) , using the servletPath and
         * pathInfo looks to be the more reasonable choice.
         */

        // Convert a request-relative path to a context-relative one
        // 尝试直接从req 中获取某个属性
        String servletPath = (String) getAttribute(
                RequestDispatcher.INCLUDE_SERVLET_PATH);
        if (servletPath == null) {
            // 代表没有直接获取到 servletPath  那么需要从 mappingData 中获取 该对象记录了req 交由哪个 host wrapper context 处理 且指定了 会交由哪个servlet 处理
            servletPath = getServletPath();
        }

        // Add the path info, if there is any    获取路径信息
        String pathInfo = getPathInfo();
        String requestPath = null;

        if (pathInfo == null) {
            requestPath = servletPath;
        } else {
            requestPath = servletPath + pathInfo;
        }

        // 获取最后一个 /
        int pos = requestPath.lastIndexOf('/');
        String relative = null;
        // 判断 dispatcher 是否处理 加密后的路径
        if (context.getDispatchersUseEncodedPaths()) {
            // 将路径加密后 拼接上  path
            if (pos >= 0) {
                relative = URLEncoder.DEFAULT.encode(
                        requestPath.substring(0, pos + 1), StandardCharsets.UTF_8) + path;
            } else {
                relative = URLEncoder.DEFAULT.encode(requestPath, StandardCharsets.UTF_8) + path;
            }
        } else {
            if (pos >= 0) {
                relative = requestPath.substring(0, pos + 1) + path;
            } else {
                relative = requestPath + path;
            }
        }

        // 将路径交由请求分发器进行分发
        return context.getServletContext().getRequestDispatcher(relative);
    }


    /**
     * @return the scheme used to make this Request.
     */
    @Override
    public String getScheme() {
        return coyoteRequest.scheme().toString();
    }


    /**
     * @return the server name responding to this Request.
     */
    @Override
    public String getServerName() {
        return coyoteRequest.serverName().toString();
    }


    /**
     * @return the server port responding to this Request.
     */
    @Override
    public int getServerPort() {
        return coyoteRequest.getServerPort();
    }


    /**
     * @return <code>true</code> if this request was received on a secure connection.
     */
    @Override
    public boolean isSecure() {
        return secure;
    }


    /**
     * Remove the specified request attribute if it exists.
     *
     * @param name Name of the request attribute to remove
     *             从 attr 中移除某个属性
     */
    @Override
    public void removeAttribute(String name) {
        // Remove the specified attribute
        // Pass special attributes to the native layer
        if (name.startsWith("org.apache.tomcat.")) {
            coyoteRequest.getAttributes().remove(name);
        }

        // 从attr 中移除某个属性
        boolean found = attributes.containsKey(name);
        if (found) {
            Object value = attributes.get(name);
            attributes.remove(name);

            // Notify interested application event listeners  触发事件监听器
            notifyAttributeRemoved(name, value);
        }
    }


    /**
     * Set the specified request attribute to the specified value.
     *
     * @param name Name of the request attribute to set
     * @param value The associated value
     *              将某个属性 设置到 attr容器中
     */
    @Override
    public void setAttribute(String name, Object value) {

        // Name cannot be null
        if (name == null) {
            throw new IllegalArgumentException(sm.getString("coyoteRequest.setAttribute.namenull"));
        }

        // Null value is the same as removeAttribute()  当添加的 value 为null时 就看作是removeAttr
        if (value == null) {
            removeAttribute(name);
            return;
        }

        // Special attributes
        // 如果尝试设置的是特殊属性 直接调用适配器添加属性
        SpecialAttributeAdapter adapter = specialAttributes.get(name);
        if (adapter != null) {
            adapter.set(this, name, value);
            return;
        }

        // Add or replace the specified attribute
        // Do the security check before any updates are made   如果是设置文件相关的属性 需要将名称转换为 规范名称
        if (Globals.IS_SECURITY_ENABLED &&
                name.equals(Globals.SENDFILE_FILENAME_ATTR)) {
            // Use the canonical file name to avoid any possible symlink and
            // relative path issues
            String canonicalPath;
            try {
                canonicalPath = new File(value.toString()).getCanonicalPath();
            } catch (IOException e) {
                throw new SecurityException(sm.getString(
                        "coyoteRequest.sendfileNotCanonical", value), e);
            }
            // Sendfile is performed in Tomcat's security context so need to
            // check if the web app is permitted to access the file while still
            // in the web app's security context
            System.getSecurityManager().checkRead(canonicalPath);
            // Update the value so the canonical path is used
            value = canonicalPath;
        }

        // 将属性添加到容器中
        Object oldValue = attributes.put(name, value);

        // Pass special attributes to the native layer  如果该属性 以tomcat 开头 还需要设置一份到 coyoteReq 中
        if (name.startsWith("org.apache.tomcat.")) {
            coyoteRequest.setAttribute(name, value);
        }

        // Notify interested application event listeners  触发监听器
        notifyAttributeAssigned(name, value, oldValue);
    }


    /**
     * Notify interested listeners that attribute has been assigned a value.
     *
     * @param name Attribute name
     * @param value New attribute value
     * @param oldValue Old attribute value
     *                 将属性添加到 attr 中
     */
    private void notifyAttributeAssigned(String name, Object value,
            Object oldValue) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        // 尝试获取监听器对象
        Object listeners[] = context.getApplicationEventListeners();
        if ((listeners == null) || (listeners.length == 0)) {
            return;
        }
        boolean replaced = (oldValue != null);
        ServletRequestAttributeEvent event = null;
        if (replaced) {
            event = new ServletRequestAttributeEvent(
                    context.getServletContext(), getRequest(), name, oldValue);
        } else {
            event = new ServletRequestAttributeEvent(
                    context.getServletContext(), getRequest(), name, value);
        }

        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof ServletRequestAttributeListener)) {
                continue;
            }
            ServletRequestAttributeListener listener =
                    (ServletRequestAttributeListener) listeners[i];
            try {
                // 根据是否替换触发不同的函数
                if (replaced) {
                    listener.attributeReplaced(event);
                } else {
                    listener.attributeAdded(event);
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                // Error valve will pick this exception up and display it to user
                attributes.put(RequestDispatcher.ERROR_EXCEPTION, t);
                context.getLogger().error(sm.getString("coyoteRequest.attributeEvent"), t);
            }
        }
    }


    /**
     * Notify interested listeners that attribute has been removed.
     *
     * @param name Attribute name
     * @param value Attribute value
     *              当某个attr 被移除时 同时触发监听器
     */
    private void notifyAttributeRemoved(String name, Object value) {
        Context context = getContext();
        Object listeners[] = context.getApplicationEventListeners();
        if ((listeners == null) || (listeners.length == 0)) {
            return;
        }
        ServletRequestAttributeEvent event =
                new ServletRequestAttributeEvent(context.getServletContext(),
                        getRequest(), name, value);
        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof ServletRequestAttributeListener)) {
                continue;
            }
            ServletRequestAttributeListener listener =
                    (ServletRequestAttributeListener) listeners[i];
            try {
                listener.attributeRemoved(event);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                // Error valve will pick this exception up and display it to user
                attributes.put(RequestDispatcher.ERROR_EXCEPTION, t);
                context.getLogger().error(sm.getString("coyoteRequest.attributeEvent"), t);
            }
        }
    }


    /**
     * Overrides the name of the character encoding used in the body of
     * this request.  This method must be called prior to reading request
     * parameters or reading input using <code>getReader()</code>.
     *
     * @param enc The character encoding to be used
     *
     * @exception UnsupportedEncodingException if the specified encoding
     *  is not supported
     *
     * @since Servlet 2.3
     * 设置字符集
     */
    @Override
    public void setCharacterEncoding(String enc) throws UnsupportedEncodingException {

        // 如果使用的本身就是字符流 那么不需要设置
        if (usingReader) {
            return;
        }

        // Confirm that the encoding name is valid
        Charset charset = B2CConverter.getCharset(enc);

        // Save the validated encoding
        coyoteRequest.setCharset(charset);
    }


    /**
     * 获取servlet 上下文
     * @return
     */
    @Override
    public ServletContext getServletContext() {
        return getContext().getServletContext();
     }

    /**
     * 开启异步处理
     * @return
     */
    @Override
    public AsyncContext startAsync() {
        return startAsync(getRequest(),response.getResponse());
    }

    /**
     * 使用异步方式处理req
     * @param request
     * @param response
     * @return
     */
    @Override
    public AsyncContext startAsync(ServletRequest request,
            ServletResponse response) {
        // 如果不支持异步处理 直接抛出异常
        if (!isAsyncSupported()) {
            IllegalStateException ise =
                    new IllegalStateException(sm.getString("request.asyncNotSupported"));
            log.warn(sm.getString("coyoteRequest.noAsync",
                    StringUtils.join(getNonAsyncClassNames())), ise);
            throw ise;
        }

        // 如果异步上下文对象还没有创建 那么先进行初始化
        if (asyncContext == null) {
            asyncContext = new AsyncContextImpl(this);
        }

        // 启动上下文 并设置超时处理时间
        asyncContext.setStarted(getContext(), request, response,
                request==getRequest() && response==getResponse().getResponse());
        asyncContext.setTimeout(getConnector().getAsyncTimeout());

        return asyncContext;
    }


    /**
     * 从管道中获取不支持异步处理的 className
     * @return
     */
    private Set<String> getNonAsyncClassNames() {
        Set<String> result = new HashSet<>();

        // 获取处理本次请求的 wrapper
        Wrapper wrapper = getWrapper();
        // 如果 wrapper 不支持异步 那么添加servlet
        if (!wrapper.isAsyncSupported()) {
            result.add(wrapper.getServletClass());
        }

        // 获取关联的 过滤链
        FilterChain filterChain = getFilterChain();
        if (filterChain instanceof ApplicationFilterChain) {
            ((ApplicationFilterChain) filterChain).findNonAsyncFilters(result);
        } else {
            result.add(sm.getString("coyoteRequest.filterAsyncSupportUnknown"));
        }

        // 递归往上层查询不支持的 异步处理容器
        Container c = wrapper;
        while (c != null) {
            c.getPipeline().findNonAsyncValves(result);
            c = c.getParent();
        }

        return result;
    }

    /**
     * 判断此req 对象是否已经以异步方式开始处理
     * @return
     */
    @Override
    public boolean isAsyncStarted() {
        if (asyncContext == null) {
            return false;
        }

        return asyncContext.isStarted();
    }

    /**
     * 异步请求对象是否已经开始派发   以下方法都会以不同的action触发hook
     * @return
     */
    public boolean isAsyncDispatching() {
        if (asyncContext == null) {
            return false;
        }

        AtomicBoolean result = new AtomicBoolean(false);
        coyoteRequest.action(ActionCode.ASYNC_IS_DISPATCHING, result);
        return result.get();
    }

    public boolean isAsyncCompleting() {
        if (asyncContext == null) {
            return false;
        }

        AtomicBoolean result = new AtomicBoolean(false);
        coyoteRequest.action(ActionCode.ASYNC_IS_COMPLETING, result);
        return result.get();
    }

    public boolean isAsync() {
        if (asyncContext == null) {
            return false;
        }

        AtomicBoolean result = new AtomicBoolean(false);
        coyoteRequest.action(ActionCode.ASYNC_IS_ASYNC, result);
        return result.get();
    }

    /**
     * 判断该请求本身是否支持异步  针对servlet3+规范
     * @return
     */
    @Override
    public boolean isAsyncSupported() {
        if (this.asyncSupported == null) {
            return true;
        }

        return asyncSupported.booleanValue();
    }

    @Override
    public AsyncContext getAsyncContext() {
        if (!isAsyncStarted()) {
            throw new IllegalStateException(sm.getString("request.notAsync"));
        }
        return asyncContext;
    }

    public AsyncContextImpl getAsyncContextInternal() {
        return asyncContext;
    }

    /**
     * 获取分发方式
     * @return
     */
    @Override
    public DispatcherType getDispatcherType() {
        // 默认情况使用 request方式
        if (internalDispatcherType == null) {
            return DispatcherType.REQUEST;
        }

        return this.internalDispatcherType;
    }

    // ---------------------------------------------------- HttpRequest Methods


    /**
     * Add a Cookie to the set of Cookies associated with this Request.
     *
     * @param cookie The new cookie
     *               为req 增加某个 cookie
     */
    public void addCookie(Cookie cookie) {

        // 如果cookie 还没有转换 那么先转换cookie
        if (!cookiesConverted) {
            convertCookies();
        }

        int size = 0;
        if (cookies != null) {
            size = cookies.length;
        }

        // 扩容 并将新的cookie 设置进去
        Cookie[] newCookies = new Cookie[size + 1];
        for (int i = 0; i < size; i++) {
            newCookies[i] = cookies[i];
        }
        newCookies[size] = cookie;

        cookies = newCookies;

    }


    /**
     * Add a Locale to the set of preferred Locales for this Request.  The
     * first added Locale will be the first one returned by getLocales().
     *
     * @param locale The new preferred Locale
     */
    public void addLocale(Locale locale) {
        locales.add(locale);
    }


    /**
     * Clear the collection of Cookies associated with this Request.
     * 清除 cookie[]
     */
    public void clearCookies() {
        cookiesParsed = true;
        cookiesConverted = true;
        cookies = null;
    }


    /**
     * Clear the collection of Locales associated with this Request.
     */
    public void clearLocales() {
        locales.clear();
    }


    /**
     * Set the authentication type used for this request, if any; otherwise
     * set the type to <code>null</code>.  Typical values are "BASIC",
     * "DIGEST", or "SSL".
     *
     * @param type The authentication type used
     */
    public void setAuthType(String type) {
        this.authType = type;
    }


    /**
     * Set the path information for this Request.  This will normally be called
     * when the associated Context is mapping the Request to a particular
     * Wrapper.
     *
     * @param path The path information
     */
    public void setPathInfo(String path) {
        mappingData.pathInfo.setString(path);
    }


    /**
     * Set a flag indicating whether or not the requested session ID for this
     * request came in through a cookie.  This is normally called by the
     * HTTP Connector, when it parses the request headers.
     *
     * @param flag The new flag
     */
    public void setRequestedSessionCookie(boolean flag) {

        this.requestedSessionCookie = flag;

    }


    /**
     * Set the requested session ID for this request.  This is normally called
     * by the HTTP Connector, when it parses the request headers.
     *
     * @param id The new session id
     */
    public void setRequestedSessionId(String id) {

        this.requestedSessionId = id;

    }


    /**
     * Set a flag indicating whether or not the requested session ID for this
     * request came in through a URL.  This is normally called by the
     * HTTP Connector, when it parses the request headers.
     *
     * @param flag The new flag
     */
    public void setRequestedSessionURL(boolean flag) {

        this.requestedSessionURL = flag;

    }


    /**
     * Set a flag indicating whether or not the requested session ID for this
     * request came in through SSL.  This is normally called by the
     * HTTP Connector, when it parses the request headers.
     *
     * @param flag The new flag
     */
    public void setRequestedSessionSSL(boolean flag) {

        this.requestedSessionSSL = flag;

    }


    /**
     * Get the decoded request URI.
     *
     * @return the URL decoded request URI
     */
    public String getDecodedRequestURI() {
        return coyoteRequest.decodedURI().toString();
    }


    /**
     * Get the decoded request URI.
     *
     * @return the URL decoded request URI
     */
    public MessageBytes getDecodedRequestURIMB() {
        return coyoteRequest.decodedURI();
    }


    /**
     * Set the Principal who has been authenticated for this Request.  This
     * value is also used to calculate the value to be returned by the
     * <code>getRemoteUser()</code> method.
     *
     * @param principal The user Principal
     */
    public void setUserPrincipal(final Principal principal) {
        if (Globals.IS_SECURITY_ENABLED && principal != null) {
            if (subject == null) {
                final HttpSession session = getSession(false);
                if (session == null) {
                    // Cache the subject in the request
                    subject = newSubject(principal);
                } else {
                    // Cache the subject in the request and the session
                    subject = (Subject) session.getAttribute(Globals.SUBJECT_ATTR);
                    if (subject == null) {
                        subject = newSubject(principal);
                        session.setAttribute(Globals.SUBJECT_ATTR, subject);
                    } else {
                        subject.getPrincipals().add(principal);
                    }
                }
            } else {
                subject.getPrincipals().add(principal);
            }
        }
        userPrincipal = principal;
    }


    private Subject newSubject(final Principal principal) {
        final Subject result = new Subject();
        result.getPrincipals().add(principal);
        return result;
    }


    // --------------------------------------------- HttpServletRequest Methods

    /**
     * Pulled forward from Servlet 4.0. The method signature may be modified,
     * removed or replaced at any time until Servlet 4.0 becomes final.
     *
     * @return A builder to use to construct the push request
     */
    @Override
    public PushBuilder newPushBuilder() {
        return newPushBuilder(this);
    }


    public PushBuilder newPushBuilder(HttpServletRequest request) {
        AtomicBoolean result = new AtomicBoolean();
        coyoteRequest.action(ActionCode.IS_PUSH_SUPPORTED, result);
        if (result.get()) {
            return new ApplicationPushBuilder(this, request);
        } else {
            return null;
        }
    }


    /**
     * {@inheritDoc}
     *
     * @since Servlet 3.1
     * 升级 http处理器 先不看
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T extends HttpUpgradeHandler> T upgrade(
            Class<T> httpUpgradeHandlerClass) throws java.io.IOException, ServletException {
        T handler;
        InstanceManager instanceManager = null;
        try {
            // Do not go through the instance manager for internal Tomcat classes since they don't
            // need injection
            if (InternalHttpUpgradeHandler.class.isAssignableFrom(httpUpgradeHandlerClass)) {
                handler = httpUpgradeHandlerClass.getConstructor().newInstance();
            } else {
                instanceManager = getContext().getInstanceManager();
                handler = (T) instanceManager.newInstance(httpUpgradeHandlerClass);
            }
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                NamingException | IllegalArgumentException | NoSuchMethodException |
                SecurityException e) {
            throw new ServletException(e);
        }
        UpgradeToken upgradeToken = new UpgradeToken(handler,
                getContext(), instanceManager);

        coyoteRequest.action(ActionCode.UPGRADE, upgradeToken);

        // Output required by RFC2616. Protocol specific headers should have
        // already been set.
        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);

        return handler;
    }

    /**
     * Return the authentication type used for this Request.
     */
    @Override
    public String getAuthType() {
        return authType;
    }


    /**
     * Return the portion of the request URI used to select the Context
     * of the Request. The value returned is not decoded which also implies it
     * is not normalised.
     * 获取 context 的路径
     */
    @Override
    public String getContextPath() {
        int lastSlash = mappingData.contextSlashCount;
        // Special case handling for the root context
        if (lastSlash == 0) {
            return "";
        }

        String canonicalContextPath = getServletContext().getContextPath();

        String uri = getRequestURI();
        int pos = 0;
        if (!getContext().getAllowMultipleLeadingForwardSlashInPath()) {
            // Ensure that the returned value only starts with a single '/'.
            // This prevents the value being misinterpreted as a protocol-
            // relative URI if used with sendRedirect().
            do {
                pos++;
            } while (pos < uri.length() && uri.charAt(pos) == '/');
            pos--;
            uri = uri.substring(pos);
        }

        char[] uriChars = uri.toCharArray();
        // Need at least the number of slashes in the context path
        while (lastSlash > 0) {
            pos = nextSlash(uriChars, pos + 1);
            if (pos == -1) {
                break;
            }
            lastSlash--;
        }
        // Now allow for path parameters, normalization and/or encoding.
        // Essentially, keep extending the candidate path up to the next slash
        // until the decoded and normalized candidate path (with the path
        // parameters removed) is the same as the canonical path.
        String candidate;
        if (pos == -1) {
            candidate = uri;
        } else {
            candidate = uri.substring(0, pos);
        }
        candidate = removePathParameters(candidate);
        candidate = UDecoder.URLDecode(candidate, connector.getURICharset());
        candidate = org.apache.tomcat.util.http.RequestUtil.normalize(candidate);
        boolean match = canonicalContextPath.equals(candidate);
        while (!match && pos != -1) {
            pos = nextSlash(uriChars, pos + 1);
            if (pos == -1) {
                candidate = uri;
            } else {
                candidate = uri.substring(0, pos);
            }
            candidate = removePathParameters(candidate);
            candidate = UDecoder.URLDecode(candidate, connector.getURICharset());
            candidate = org.apache.tomcat.util.http.RequestUtil.normalize(candidate);
            match = canonicalContextPath.equals(candidate);
        }
        if (match) {
            if (pos == -1) {
                return uri;
            } else {
                return uri.substring(0, pos);
            }
        } else {
            // Should never happen
            throw new IllegalStateException(sm.getString(
                    "coyoteRequest.getContextPath.ise", canonicalContextPath, uri));
        }
    }


    private String removePathParameters(String input) {
        int nextSemiColon = input.indexOf(';');
        // Shortcut
        if (nextSemiColon == -1) {
            return input;
        }
        StringBuilder result = new StringBuilder(input.length());
        result.append(input.substring(0, nextSemiColon));
        while (true) {
            int nextSlash = input.indexOf('/', nextSemiColon);
            if (nextSlash == -1) {
                break;
            }
            nextSemiColon = input.indexOf(';', nextSlash);
            if (nextSemiColon == -1) {
                result.append(input.substring(nextSlash));
                break;
            } else {
                result.append(input.substring(nextSlash, nextSemiColon));
            }
        }

        return result.toString();
    }


    private int nextSlash(char[] uri, int startPos) {
        int len = uri.length;
        int pos = startPos;
        while (pos < len) {
            if (uri[pos] == '/') {
                return pos;
            } else if (UDecoder.ALLOW_ENCODED_SLASH && uri[pos] == '%' && pos + 2 < len &&
                    uri[pos+1] == '2' && (uri[pos + 2] == 'f' || uri[pos + 2] == 'F')) {
                return pos;
            }
            pos++;
        }
        return -1;
    }


    /**
     * Return the set of Cookies received with this Request. Triggers parsing of
     * the Cookie HTTP headers followed by conversion to Cookie objects if this
     * has not already been performed.
     *
     * @return the array of cookies
     */
    @Override
    public Cookie[] getCookies() {
        if (!cookiesConverted) {
            convertCookies();
        }
        return cookies;
    }


    /**
     * Return the server representation of the cookies associated with this
     * request. Triggers parsing of the Cookie HTTP headers (but not conversion
     * to Cookie objects) if the headers have not yet been parsed.
     *
     * @return the server cookies
     */
    public ServerCookies getServerCookies() {
        parseCookies();
        return coyoteRequest.getCookies();
    }


    /**
     * Return the value of the specified date header, if any; otherwise
     * return -1.
     *
     * @param name Name of the requested date header
     * @return the date as a long
     *
     * @exception IllegalArgumentException if the specified header value
     *  cannot be converted to a date
     */
    @Override
    public long getDateHeader(String name) {

        String value = getHeader(name);
        if (value == null) {
            return -1L;
        }

        // Attempt to convert the date header in a variety of formats
        long result = FastHttpDateFormat.parseDate(value);
        if (result != (-1L)) {
            return result;
        }
        throw new IllegalArgumentException(value);

    }


    /**
     * Return the first value of the specified header, if any; otherwise,
     * return <code>null</code>
     *
     * @param name Name of the requested header
     * @return the header value
     */
    @Override
    public String getHeader(String name) {
        return coyoteRequest.getHeader(name);
    }


    /**
     * Return all of the values of the specified header, if any; otherwise,
     * return an empty enumeration.
     *
     * @param name Name of the requested header
     * @return the enumeration with the header values
     */
    @Override
    public Enumeration<String> getHeaders(String name) {
        return coyoteRequest.getMimeHeaders().values(name);
    }


    /**
     * @return the names of all headers received with this request.
     */
    @Override
    public Enumeration<String> getHeaderNames() {
        return coyoteRequest.getMimeHeaders().names();
    }


    /**
     * Return the value of the specified header as an integer, or -1 if there
     * is no such header for this request.
     *
     * @param name Name of the requested header
     * @return the header value as an int
     *
     * @exception IllegalArgumentException if the specified header value
     *  cannot be converted to an integer
     */
    @Override
    public int getIntHeader(String name) {

        String value = getHeader(name);
        if (value == null) {
            return -1;
        }

        return Integer.parseInt(value);
    }


    @Override
    public HttpServletMapping getHttpServletMapping() {
        return applicationMapping.getHttpServletMapping();
    }


    /**
     * @return the HTTP request method used in this Request.
     */
    @Override
    public String getMethod() {
        return coyoteRequest.method().toString();
    }


    /**
     * @return the path information associated with this Request.
     */
    @Override
    public String getPathInfo() {
        return mappingData.pathInfo.toString();
    }


    /**
     * @return the extra path information for this request, translated
     * to a real path.
     */
    @Override
    public String getPathTranslated() {

        Context context = getContext();
        if (context == null) {
            return null;
        }

        if (getPathInfo() == null) {
            return null;
        }

        return context.getServletContext().getRealPath(getPathInfo());
    }


    /**
     * @return the query string associated with this request.
     */
    @Override
    public String getQueryString() {
        return coyoteRequest.queryString().toString();
    }


    /**
     * @return the name of the remote user that has been authenticated
     * for this Request.
     */
    @Override
    public String getRemoteUser() {

        if (userPrincipal == null) {
            return null;
        }

        return userPrincipal.getName();
    }


    /**
     * Get the request path.
     *
     * @return the request path
     */
    public MessageBytes getRequestPathMB() {
        return mappingData.requestPath;
    }


    /**
     * @return the session identifier included in this request, if any.
     */
    @Override
    public String getRequestedSessionId() {
        return requestedSessionId;
    }


    /**
     * @return the request URI for this request.
     */
    @Override
    public String getRequestURI() {
        return coyoteRequest.requestURI().toString();
    }


    @Override
    public StringBuffer getRequestURL() {

        StringBuffer url = new StringBuffer();
        String scheme = getScheme();
        int port = getServerPort();
        if (port < 0)
         {
            port = 80; // Work around java.net.URL bug
        }

        url.append(scheme);
        url.append("://");
        url.append(getServerName());
        if ((scheme.equals("http") && (port != 80))
            || (scheme.equals("https") && (port != 443))) {
            url.append(':');
            url.append(port);
        }
        url.append(getRequestURI());

        return url;
    }


    /**
     * @return the portion of the request URI used to select the servlet
     * that will process this request.
     */
    @Override
    public String getServletPath() {
        return mappingData.wrapperPath.toString();
    }


    /**
     * @return the session associated with this Request, creating one
     * if necessary.
     */
    @Override
    public HttpSession getSession() {
        Session session = doGetSession(true);
        if (session == null) {
            return null;
        }

        return session.getSession();
    }


    /**
     * @return the session associated with this Request, creating one
     * if necessary and requested.
     *
     * @param create Create a new session if one does not exist
     */
    @Override
    public HttpSession getSession(boolean create) {
        Session session = doGetSession(create);
        if (session == null) {
            return null;
        }

        return session.getSession();
    }


    /**
     * @return <code>true</code> if the session identifier included in this
     * request came from a cookie.
     */
    @Override
    public boolean isRequestedSessionIdFromCookie() {

        if (requestedSessionId == null) {
            return false;
        }

        return requestedSessionCookie;
    }


    /**
     * @return <code>true</code> if the session identifier included in this
     * request came from the request URI.
     */
    @Override
    public boolean isRequestedSessionIdFromURL() {

        if (requestedSessionId == null) {
            return false;
        }

        return requestedSessionURL;
    }


    /**
     * @return <code>true</code> if the session identifier included in this
     * request came from the request URI.
     *
     * @deprecated As of Version 2.1 of the Java Servlet API, use
     *  <code>isRequestedSessionIdFromURL()</code> instead.
     */
    @Override
    @Deprecated
    public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }


    /**
     * @return <code>true</code> if the session identifier included in this
     * request identifies a valid session.
     */
    @Override
    public boolean isRequestedSessionIdValid() {

        if (requestedSessionId == null) {
            return false;
        }

        Context context = getContext();
        if (context == null) {
            return false;
        }

        Manager manager = context.getManager();
        if (manager == null) {
            return false;
        }

        Session session = null;
        try {
            session = manager.findSession(requestedSessionId);
        } catch (IOException e) {
            // Can't find the session
        }

        if ((session == null) || !session.isValid()) {
            // Check for parallel deployment contexts
            if (getMappingData().contexts == null) {
                return false;
            } else {
                for (int i = (getMappingData().contexts.length); i > 0; i--) {
                    Context ctxt = getMappingData().contexts[i - 1];
                    try {
                        if (ctxt.getManager().findSession(requestedSessionId) !=
                                null) {
                            return true;
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                return false;
            }
        }

        return true;
    }


    /**
     * @return <code>true</code> if the authenticated user principal
     * possesses the specified role name.
     *
     * @param role Role name to be validated
     */
    @Override
    public boolean isUserInRole(String role) {

        // Have we got an authenticated principal at all?
        if (userPrincipal == null) {
            return false;
        }

        // Identify the Realm we will use for checking role assignments
        Context context = getContext();
        if (context == null) {
            return false;
        }

        // If the role is "*" then the return value must be false
        // Servlet 31, section 13.3
        if ("*".equals(role)) {
            return false;
        }

        // If the role is "**" then, unless the application defines a role with
        // that name, only check if the user is authenticated
        if ("**".equals(role) && !context.findSecurityRole("**")) {
            return userPrincipal != null;
        }

        Realm realm = context.getRealm();
        if (realm == null) {
            return false;
        }

        // Check for a role defined directly as a <security-role>
        return realm.hasRole(getWrapper(), userPrincipal, role);
    }


    /**
     * @return the principal that has been authenticated for this Request.
     */
    public Principal getPrincipal() {
        return userPrincipal;
    }


    /**
     * @return the principal that has been authenticated for this Request.
     */
    @Override
    public Principal getUserPrincipal() {
        if (userPrincipal instanceof TomcatPrincipal) {
            GSSCredential gssCredential =
                    ((TomcatPrincipal) userPrincipal).getGssCredential();
            if (gssCredential != null) {
                int left = -1;
                try {
                    left = gssCredential.getRemainingLifetime();
                } catch (GSSException e) {
                    log.warn(sm.getString("coyoteRequest.gssLifetimeFail",
                            userPrincipal.getName()), e);
                }
                if (left == 0) {
                    // GSS credential has expired. Need to re-authenticate.
                    try {
                        logout();
                    } catch (ServletException e) {
                        // Should never happen (no code called by logout()
                        // throws a ServletException
                    }
                    return null;
                }
            }
            return ((TomcatPrincipal) userPrincipal).getUserPrincipal();
        }

        return userPrincipal;
    }


    /**
     * @return the session associated with this Request, creating one
     * if necessary.
     */
    public Session getSessionInternal() {
        return doGetSession(true);
    }


    /**
     * Change the ID of the session that this request is associated with. There
     * are several things that may trigger an ID change. These include moving
     * between nodes in a cluster and session fixation prevention during the
     * authentication process.
     *
     * @param newSessionId   The session to change the session ID for
     */
    public void changeSessionId(String newSessionId) {
        // This should only ever be called if there was an old session ID but
        // double check to be sure
        if (requestedSessionId != null && requestedSessionId.length() > 0) {
            requestedSessionId = newSessionId;
        }

        Context context = getContext();
        if (context != null &&
                !context.getServletContext()
                        .getEffectiveSessionTrackingModes()
                        .contains(SessionTrackingMode.COOKIE)) {
            return;
        }

        if (response != null) {
            Cookie newCookie = ApplicationSessionCookieConfig.createSessionCookie(context,
                    newSessionId, isSecure());
            response.addSessionCookieInternal(newCookie);
        }
    }


    /**
     * Changes the session ID of the session associated with this request.
     *
     * @return the old session ID before it was changed
     * @see javax.servlet.http.HttpSessionIdListener
     * @since Servlet 3.1
     */
    @Override
    public String changeSessionId() {

        Session session = this.getSessionInternal(false);
        if (session == null) {
            throw new IllegalStateException(
                sm.getString("coyoteRequest.changeSessionId"));
        }

        Manager manager = this.getContext().getManager();
        manager.changeSessionId(session);

        String newSessionId = session.getId();
        this.changeSessionId(newSessionId);

        return newSessionId;
    }

    /**
     * @return the session associated with this Request, creating one
     * if necessary and requested.
     *
     * @param create Create a new session if one does not exist
     */
    public Session getSessionInternal(boolean create) {
        return doGetSession(create);
    }


    /**
     * @return <code>true</code> if we have parsed parameters
     */
    public boolean isParametersParsed() {
        return parametersParsed;
    }


    /**
     * @return <code>true</code> if an attempt has been made to read the request
     *         body and all of the request body has been read.
     */
    public boolean isFinished() {
        return coyoteRequest.isFinished();
    }


    /**
     * Check the configuration for aborted uploads and if configured to do so,
     * disable the swallowing of any remaining input and close the connection
     * once the response has been written.
     * 检查吞吐量
     */
    protected void checkSwallowInput() {
        Context context = getContext();
        // 如果是禁止下载  触发  DISABLE_SWALLOW_INPUT
        if (context != null && !context.getSwallowAbortedUploads()) {
            // 这里会 触发 request内部的hook 对象 实际上就是调用 http11Processor
            coyoteRequest.action(ActionCode.DISABLE_SWALLOW_INPUT, null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean authenticate(HttpServletResponse response)
            throws IOException, ServletException {
        if (response.isCommitted()) {
            throw new IllegalStateException(
                    sm.getString("coyoteRequest.authenticate.ise"));
        }

        return getContext().getAuthenticator().authenticate(this, response);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void login(String username, String password)
            throws ServletException {
        if (getAuthType() != null || getRemoteUser() != null ||
                getUserPrincipal() != null) {
            throw new ServletException(
                    sm.getString("coyoteRequest.alreadyAuthenticated"));
        }

        getContext().getAuthenticator().login(username, password, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void logout() throws ServletException {
        getContext().getAuthenticator().logout(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Part> getParts() throws IOException, IllegalStateException,
            ServletException {

        parseParts(true);

        if (partsParseException != null) {
            if (partsParseException instanceof IOException) {
                throw (IOException) partsParseException;
            } else if (partsParseException instanceof IllegalStateException) {
                throw (IllegalStateException) partsParseException;
            } else if (partsParseException instanceof ServletException) {
                throw (ServletException) partsParseException;
            }
        }

        return parts;
    }

    private void parseParts(boolean explicit) {

        // Return immediately if the parts have already been parsed
        if (parts != null || partsParseException != null) {
            return;
        }

        Context context = getContext();
        MultipartConfigElement mce = getWrapper().getMultipartConfigElement();

        if (mce == null) {
            if(context.getAllowCasualMultipartParsing()) {
                mce = new MultipartConfigElement(null, connector.getMaxPostSize(),
                        connector.getMaxPostSize(), connector.getMaxPostSize());
            } else {
                if (explicit) {
                    partsParseException = new IllegalStateException(
                            sm.getString("coyoteRequest.noMultipartConfig"));
                    return;
                } else {
                    parts = Collections.emptyList();
                    return;
                }
            }
        }

        Parameters parameters = coyoteRequest.getParameters();
        parameters.setLimit(getConnector().getMaxParameterCount());

        boolean success = false;
        try {
            File location;
            String locationStr = mce.getLocation();
            if (locationStr == null || locationStr.length() == 0) {
                location = ((File) context.getServletContext().getAttribute(
                        ServletContext.TEMPDIR));
            } else {
                // If relative, it is relative to TEMPDIR
                location = new File(locationStr);
                if (!location.isAbsolute()) {
                    location = new File(
                            (File) context.getServletContext().getAttribute(ServletContext.TEMPDIR),
                            locationStr).getAbsoluteFile();
                }
            }

            if (!location.exists() && context.getCreateUploadTargets()) {
                log.warn(sm.getString("coyoteRequest.uploadCreate",
                        location.getAbsolutePath(), getMappingData().wrapper.getName()));
                if (!location.mkdirs()) {
                    log.warn(sm.getString("coyoteRequest.uploadCreateFail",
                            location.getAbsolutePath()));
                }
            }

            if (!location.isDirectory()) {
                parameters.setParseFailedReason(FailReason.MULTIPART_CONFIG_INVALID);
                partsParseException = new IOException(
                        sm.getString("coyoteRequest.uploadLocationInvalid",
                                location));
                return;
            }


            // Create a new file upload handler
            DiskFileItemFactory factory = new DiskFileItemFactory();
            try {
                factory.setRepository(location.getCanonicalFile());
            } catch (IOException ioe) {
                parameters.setParseFailedReason(FailReason.IO_ERROR);
                partsParseException = ioe;
                return;
            }
            factory.setSizeThreshold(mce.getFileSizeThreshold());

            ServletFileUpload upload = new ServletFileUpload();
            upload.setFileItemFactory(factory);
            upload.setFileSizeMax(mce.getMaxFileSize());
            upload.setSizeMax(mce.getMaxRequestSize());

            parts = new ArrayList<>();
            try {
                List<FileItem> items =
                        upload.parseRequest(new ServletRequestContext(this));
                int maxPostSize = getConnector().getMaxPostSize();
                int postSize = 0;
                Charset charset = getCharset();
                for (FileItem item : items) {
                    ApplicationPart part = new ApplicationPart(item, location);
                    parts.add(part);
                    if (part.getSubmittedFileName() == null) {
                        String name = part.getName();
                        String value = null;
                        try {
                            value = part.getString(charset.name());
                        } catch (UnsupportedEncodingException uee) {
                            // Not possible
                        }
                        if (maxPostSize >= 0) {
                            // Have to calculate equivalent size. Not completely
                            // accurate but close enough.
                            postSize += name.getBytes(charset).length;
                            if (value != null) {
                                // Equals sign
                                postSize++;
                                // Value length
                                postSize += part.getSize();
                            }
                            // Value separator
                            postSize++;
                            if (postSize > maxPostSize) {
                                parameters.setParseFailedReason(FailReason.POST_TOO_LARGE);
                                throw new IllegalStateException(sm.getString(
                                        "coyoteRequest.maxPostSizeExceeded"));
                            }
                        }
                        parameters.addParameter(name, value);
                    }
                }

                success = true;
            } catch (InvalidContentTypeException e) {
                parameters.setParseFailedReason(FailReason.INVALID_CONTENT_TYPE);
                partsParseException = new ServletException(e);
            } catch (FileUploadBase.SizeException e) {
                parameters.setParseFailedReason(FailReason.POST_TOO_LARGE);
                checkSwallowInput();
                partsParseException = new IllegalStateException(e);
            } catch (FileUploadException e) {
                parameters.setParseFailedReason(FailReason.IO_ERROR);
                partsParseException = new IOException(e);
            } catch (IllegalStateException e) {
                // addParameters() will set parseFailedReason
                checkSwallowInput();
                partsParseException = e;
            }
        } finally {
            // This might look odd but is correct. setParseFailedReason() only
            // sets the failure reason if none is currently set. This code could
            // be more efficient but it is written this way to be robust with
            // respect to changes in the remainder of the method.
            if (partsParseException != null || !success) {
                parameters.setParseFailedReason(FailReason.UNKNOWN);
            }
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Part getPart(String name) throws IOException, IllegalStateException,
            ServletException {
        for (Part part : getParts()) {
            if (name.equals(part.getName())) {
                return part;
            }
        }
        return null;
    }


    // ------------------------------------------------------ Protected Methods

    protected Session doGetSession(boolean create) {

        // There cannot be a session if no context has been assigned yet
        Context context = getContext();
        if (context == null) {
            return null;
        }

        // Return the current session if it exists and is valid
        if ((session != null) && !session.isValid()) {
            session = null;
        }
        if (session != null) {
            return session;
        }

        // Return the requested session if it exists and is valid
        Manager manager = context.getManager();
        if (manager == null) {
            return null;      // Sessions are not supported
        }
        if (requestedSessionId != null) {
            try {
                session = manager.findSession(requestedSessionId);
            } catch (IOException e) {
                session = null;
            }
            if ((session != null) && !session.isValid()) {
                session = null;
            }
            if (session != null) {
                session.access();
                return session;
            }
        }

        // Create a new session if requested and the response is not committed
        if (!create) {
            return null;
        }
        boolean trackModesIncludesCookie =
                context.getServletContext().getEffectiveSessionTrackingModes().contains(SessionTrackingMode.COOKIE);
        if (trackModesIncludesCookie && response.getResponse().isCommitted()) {
            throw new IllegalStateException(sm.getString("coyoteRequest.sessionCreateCommitted"));
        }

        // Re-use session IDs provided by the client in very limited
        // circumstances.
        String sessionId = getRequestedSessionId();
        if (requestedSessionSSL) {
            // If the session ID has been obtained from the SSL handshake then
            // use it.
        } else if (("/".equals(context.getSessionCookiePath())
                && isRequestedSessionIdFromCookie())) {
            /* This is the common(ish) use case: using the same session ID with
             * multiple web applications on the same host. Typically this is
             * used by Portlet implementations. It only works if sessions are
             * tracked via cookies. The cookie must have a path of "/" else it
             * won't be provided for requests to all web applications.
             *
             * Any session ID provided by the client should be for a session
             * that already exists somewhere on the host. Check if the context
             * is configured for this to be confirmed.
             */
            if (context.getValidateClientProvidedNewSessionId()) {
                boolean found = false;
                for (Container container : getHost().findChildren()) {
                    Manager m = ((Context) container).getManager();
                    if (m != null) {
                        try {
                            if (m.findSession(sessionId) != null) {
                                found = true;
                                break;
                            }
                        } catch (IOException e) {
                            // Ignore. Problems with this manager will be
                            // handled elsewhere.
                        }
                    }
                }
                if (!found) {
                    sessionId = null;
                }
            }
        } else {
            sessionId = null;
        }
        session = manager.createSession(sessionId);

        // Creating a new session cookie based on that session
        if (session != null && trackModesIncludesCookie) {
            Cookie cookie = ApplicationSessionCookieConfig.createSessionCookie(
                    context, session.getIdInternal(), isSecure());

            response.addSessionCookieInternal(cookie);
        }

        if (session == null) {
            return null;
        }

        session.access();
        return session;
    }

    protected String unescape(String s) {
        if (s==null) {
            return null;
        }
        if (s.indexOf('\\') == -1) {
            return s;
        }
        StringBuilder buf = new StringBuilder();
        for (int i=0; i<s.length(); i++) {
            char c = s.charAt(i);
            if (c!='\\') {
                buf.append(c);
            } else {
                if (++i >= s.length()) {
                    throw new IllegalArgumentException();//invalid escape, hence invalid cookie
                }
                c = s.charAt(i);
                buf.append(c);
            }
        }
        return buf.toString();
    }

    /**
     * Parse cookies. This only parses the cookies into the memory efficient
     * ServerCookies structure. It does not populate the Cookie objects.
     */
    protected void parseCookies() {
        if (cookiesParsed) {
            return;
        }

        cookiesParsed = true;

        ServerCookies serverCookies = coyoteRequest.getCookies();
        serverCookies.setLimit(connector.getMaxCookieCount());
        CookieProcessor cookieProcessor = getContext().getCookieProcessor();
        cookieProcessor.parseCookieHeader(coyoteRequest.getMimeHeaders(), serverCookies);
    }

    /**
     * Converts the parsed cookies (parsing the Cookie headers first if they
     * have not been parsed) into Cookie objects.
     * 转换 cookie
     */
    protected void convertCookies() {
        if (cookiesConverted) {
            return;
        }

        // 代表已经转换完成了
        cookiesConverted = true;

        if (getContext() == null) {
            return;
        }

        parseCookies();

        ServerCookies serverCookies = coyoteRequest.getCookies();
        CookieProcessor cookieProcessor = getContext().getCookieProcessor();

        int count = serverCookies.getCookieCount();
        if (count <= 0) {
            return;
        }

        cookies = new Cookie[count];

        int idx=0;
        for (int i = 0; i < count; i++) {
            ServerCookie scookie = serverCookies.getCookie(i);
            try {
                // We must unescape the '\\' escape character
                Cookie cookie = new Cookie(scookie.getName().toString(),null);
                int version = scookie.getVersion();
                cookie.setVersion(version);
                scookie.getValue().getByteChunk().setCharset(cookieProcessor.getCharset());
                cookie.setValue(unescape(scookie.getValue().toString()));
                cookie.setPath(unescape(scookie.getPath().toString()));
                String domain = scookie.getDomain().toString();
                if (domain!=null) {
                    cookie.setDomain(unescape(domain));//avoid NPE
                }
                String comment = scookie.getComment().toString();
                cookie.setComment(version==1?unescape(comment):null);
                cookies[idx++] = cookie;
            } catch(IllegalArgumentException e) {
                // Ignore bad cookie
            }
        }
        if( idx < count ) {
            Cookie [] ncookies = new Cookie[idx];
            System.arraycopy(cookies, 0, ncookies, 0, idx);
            cookies = ncookies;
        }
    }


    /**
     * Parse request parameters.
     */
    protected void parseParameters() {

        parametersParsed = true;

        Parameters parameters = coyoteRequest.getParameters();
        boolean success = false;
        try {
            // Set this every time in case limit has been changed via JMX
            parameters.setLimit(getConnector().getMaxParameterCount());

            // getCharacterEncoding() may have been overridden to search for
            // hidden form field containing request encoding
            Charset charset = getCharset();

            boolean useBodyEncodingForURI = connector.getUseBodyEncodingForURI();
            parameters.setCharset(charset);
            if (useBodyEncodingForURI) {
                parameters.setQueryStringCharset(charset);
            }
            // Note: If !useBodyEncodingForURI, the query string encoding is
            //       that set towards the start of CoyoyeAdapter.service()

            parameters.handleQueryParameters();

            if (usingInputStream || usingReader) {
                success = true;
                return;
            }

            String contentType = getContentType();
            if (contentType == null) {
                contentType = "";
            }
            int semicolon = contentType.indexOf(';');
            if (semicolon >= 0) {
                contentType = contentType.substring(0, semicolon).trim();
            } else {
                contentType = contentType.trim();
            }

            if ("multipart/form-data".equals(contentType)) {
                parseParts(false);
                success = true;
                return;
            }

            if( !getConnector().isParseBodyMethod(getMethod()) ) {
                success = true;
                return;
            }

            if (!("application/x-www-form-urlencoded".equals(contentType))) {
                success = true;
                return;
            }

            int len = getContentLength();

            if (len > 0) {
                int maxPostSize = connector.getMaxPostSize();
                if ((maxPostSize >= 0) && (len > maxPostSize)) {
                    Context context = getContext();
                    if (context != null && context.getLogger().isDebugEnabled()) {
                        context.getLogger().debug(
                                sm.getString("coyoteRequest.postTooLarge"));
                    }
                    checkSwallowInput();
                    parameters.setParseFailedReason(FailReason.POST_TOO_LARGE);
                    return;
                }
                byte[] formData = null;
                if (len < CACHED_POST_LEN) {
                    if (postData == null) {
                        postData = new byte[CACHED_POST_LEN];
                    }
                    formData = postData;
                } else {
                    formData = new byte[len];
                }
                try {
                    if (readPostBody(formData, len) != len) {
                        parameters.setParseFailedReason(FailReason.REQUEST_BODY_INCOMPLETE);
                        return;
                    }
                } catch (IOException e) {
                    // Client disconnect
                    Context context = getContext();
                    if (context != null && context.getLogger().isDebugEnabled()) {
                        context.getLogger().debug(
                                sm.getString("coyoteRequest.parseParameters"), e);
                    }
                    parameters.setParseFailedReason(FailReason.CLIENT_DISCONNECT);
                    return;
                }
                parameters.processParameters(formData, 0, len);
            } else if ("chunked".equalsIgnoreCase(
                    coyoteRequest.getHeader("transfer-encoding"))) {
                byte[] formData = null;
                try {
                    formData = readChunkedPostBody();
                } catch (IllegalStateException ise) {
                    // chunkedPostTooLarge error
                    parameters.setParseFailedReason(FailReason.POST_TOO_LARGE);
                    Context context = getContext();
                    if (context != null && context.getLogger().isDebugEnabled()) {
                        context.getLogger().debug(
                                sm.getString("coyoteRequest.parseParameters"),
                                ise);
                    }
                    return;
                } catch (IOException e) {
                    // Client disconnect
                    parameters.setParseFailedReason(FailReason.CLIENT_DISCONNECT);
                    Context context = getContext();
                    if (context != null && context.getLogger().isDebugEnabled()) {
                        context.getLogger().debug(
                                sm.getString("coyoteRequest.parseParameters"), e);
                    }
                    return;
                }
                if (formData != null) {
                    parameters.processParameters(formData, 0, formData.length);
                }
            }
            success = true;
        } finally {
            if (!success) {
                parameters.setParseFailedReason(FailReason.UNKNOWN);
            }
        }

    }


    /**
     * Read post body in an array.
     *
     * @param body The bytes array in which the body will be read
     * @param len The body length
     * @return the bytes count that has been read
     * @throws IOException if an IO exception occurred
     */
    protected int readPostBody(byte[] body, int len)
            throws IOException {

        int offset = 0;
        do {
            int inputLen = getStream().read(body, offset, len - offset);
            if (inputLen <= 0) {
                return offset;
            }
            offset += inputLen;
        } while ((len - offset) > 0);
        return len;

    }


    /**
     * Read chunked post body.
     *
     * @return the post body as a bytes array
     * @throws IOException if an IO exception occurred
     */
    protected byte[] readChunkedPostBody() throws IOException {
        ByteChunk body = new ByteChunk();

        byte[] buffer = new byte[CACHED_POST_LEN];

        int len = 0;
        while (len > -1) {
            len = getStream().read(buffer, 0, CACHED_POST_LEN);
            if (connector.getMaxPostSize() >= 0 &&
                    (body.getLength() + len) > connector.getMaxPostSize()) {
                // Too much data
                checkSwallowInput();
                throw new IllegalStateException(
                        sm.getString("coyoteRequest.chunkedPostTooLarge"));
            }
            if (len > 0) {
                body.append(buffer, 0, len);
            }
        }
        if (body.getLength() == 0) {
            return null;
        }
        if (body.getLength() < body.getBuffer().length) {
            int length = body.getLength();
            byte[] result = new byte[length];
            System.arraycopy(body.getBuffer(), 0, result, 0, length);
            return result;
        }

        return body.getBuffer();
    }


    /**
     * Parse request locales.
     */
    protected void parseLocales() {

        localesParsed = true;

        // Store the accumulated languages that have been requested in
        // a local collection, sorted by the quality value (so we can
        // add Locales in descending order).  The values will be ArrayLists
        // containing the corresponding Locales to be added
        TreeMap<Double, ArrayList<Locale>> locales = new TreeMap<>();

        Enumeration<String> values = getHeaders("accept-language");

        while (values.hasMoreElements()) {
            String value = values.nextElement();
            parseLocalesHeader(value, locales);
        }

        // Process the quality values in highest->lowest order (due to
        // negating the Double value when creating the key)
        for (ArrayList<Locale> list : locales.values()) {
            for (Locale locale : list) {
                addLocale(locale);
            }
        }
    }


    /**
     * Parse accept-language header value.
     *
     * @param value the header value
     * @param locales the map that will hold the result
     */
    protected void parseLocalesHeader(String value, TreeMap<Double, ArrayList<Locale>> locales) {

        List<AcceptLanguage> acceptLanguages;
        try {
            acceptLanguages = AcceptLanguage.parse(new StringReader(value));
        } catch (IOException e) {
            // Mal-formed headers are ignore. Do the same in the unlikely event
            // of an IOException.
            return;
        }

        for (AcceptLanguage acceptLanguage : acceptLanguages) {
            // Add a new Locale to the list of Locales for this quality level
            Double key = Double.valueOf(-acceptLanguage.getQuality());  // Reverse the order
            ArrayList<Locale> values = locales.get(key);
            if (values == null) {
                values = new ArrayList<>();
                locales.put(key, values);
            }
            values.add(acceptLanguage.getLocale());
        }
    }


    // ----------------------------------------------------- Special attributes handling

    /**
     * 特殊属性适配器
     */
    private static interface SpecialAttributeAdapter {
        // 传入指定的req  对象 并根据 attrName 获取对应属性
        Object get(Request request, String name);

        void set(Request request, String name, Object value);

        // None of special attributes support removal
        // void remove(Request request, String name);
    }

    /**
     * 该容器内部是 存储特殊属性的
     */
    private static final Map<String, SpecialAttributeAdapter> specialAttributes = new HashMap<>();

    /**
     * 一开始 填充 特殊属性容器
     */
    static {
        // key 是 分发类型
        specialAttributes.put(Globals.DISPATCHER_TYPE_ATTR,
                new SpecialAttributeAdapter() {
                    @Override
                    public Object get(Request request, String name) {
                        return (request.internalDispatcherType == null) ? DispatcherType.REQUEST
                                : request.internalDispatcherType;
                    }

                    // 注意这里name 参数是不使用的 直接将 dispatcherType修改成 value
                    @Override
                    public void set(Request request, String name, Object value) {
                        request.internalDispatcherType = (DispatcherType) value;
                    }
                });
        specialAttributes.put(Globals.DISPATCHER_REQUEST_PATH_ATTR,
                new SpecialAttributeAdapter() {
                    @Override
                    public Object get(Request request, String name) {
                        return (request.requestDispatcherPath == null) ? request
                                .getRequestPathMB().toString()
                                : request.requestDispatcherPath.toString();
                    }

                    @Override
                    public void set(Request request, String name, Object value) {
                        request.requestDispatcherPath = value;
                    }
                });
        specialAttributes.put(Globals.ASYNC_SUPPORTED_ATTR,
                new SpecialAttributeAdapter() {
                    @Override
                    public Object get(Request request, String name) {
                        return request.asyncSupported;
                    }

                    @Override
                    public void set(Request request, String name, Object value) {
                        Boolean oldValue = request.asyncSupported;
                        request.asyncSupported = (Boolean)value;
                        request.notifyAttributeAssigned(name, value, oldValue);
                    }
                });
        specialAttributes.put(Globals.GSS_CREDENTIAL_ATTR,
                new SpecialAttributeAdapter() {
                    @Override
                    public Object get(Request request, String name) {
                        if (request.userPrincipal instanceof TomcatPrincipal) {
                            return ((TomcatPrincipal) request.userPrincipal)
                                    .getGssCredential();
                        }
                        return null;
                    }

                    @Override
                    public void set(Request request, String name, Object value) {
                        // NO-OP
                    }
                });
        specialAttributes.put(Globals.PARAMETER_PARSE_FAILED_ATTR,
                new SpecialAttributeAdapter() {
                    @Override
                    public Object get(Request request, String name) {
                        if (request.getCoyoteRequest().getParameters()
                                .isParseFailed()) {
                            return Boolean.TRUE;
                        }
                        return null;
                    }

                    @Override
                    public void set(Request request, String name, Object value) {
                        // NO-OP
                    }
                });
        specialAttributes.put(Globals.PARAMETER_PARSE_FAILED_REASON_ATTR,
                new SpecialAttributeAdapter() {
                    @Override
                    public Object get(Request request, String name) {
                        return request.getCoyoteRequest().getParameters().getParseFailedReason();
                    }

                    @Override
                    public void set(Request request, String name, Object value) {
                        // NO-OP
                    }
                });
        specialAttributes.put(Globals.SENDFILE_SUPPORTED_ATTR,
                new SpecialAttributeAdapter() {
                    @Override
                    public Object get(Request request, String name) {
                        return Boolean.valueOf(
                                request.getConnector().getProtocolHandler(
                                        ).isSendfileSupported() && request.getCoyoteRequest().getSendfile());
                    }
                    @Override
                    public void set(Request request, String name, Object value) {
                        // NO-OP
                    }
                });

        for (SimpleDateFormat sdf : formatsTemplate) {
            sdf.setTimeZone(GMT_ZONE);
        }
    }
}
