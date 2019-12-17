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
package org.apache.catalina.session;


import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.SessionIdGenerator;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.catalina.util.SessionIdGeneratorBase;
import org.apache.catalina.util.StandardSessionIdGenerator;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;


/**
 * Minimal implementation of the <b>Manager</b> interface that supports
 * no session persistence or distributable capabilities.  This class may
 * be subclassed to create more sophisticated Manager implementations.
 *
 * @author Craig R. McClanahan
 * manager 的实现基类
 */
public abstract class ManagerBase extends LifecycleMBeanBase implements Manager {

    private final Log log = LogFactory.getLog(ManagerBase.class); // must not be static

    // ----------------------------------------------------- Instance Variables

    /**
     * The Context with which this Manager is associated.
     * 每个 manager会关联一个 context 对象 而 context 接口又继承了 container 接口
     */
    private Context context;


    /**
     * The descriptive name of this Manager implementation (for logging).
     */
    private static final String name = "ManagerBase";


    /**
     * The Java class name of the secure random number generator class to be
     * used when generating session identifiers. The random number generator
     * class must be self-seeding and have a zero-argument constructor. If not
     * specified, an instance of {@link java.security.SecureRandom} will be
     * generated.
     * sessionIdGenerator 使用的 随机数生成类
     */
    protected String secureRandomClass = null;

    /**
     * The name of the algorithm to use to create instances of
     * {@link java.security.SecureRandom} which are used to generate session IDs.
     * If no algorithm is specified, SHA1PRNG is used. To use the platform
     * default (which may be SHA1PRNG), specify the empty string. If an invalid
     * algorithm and/or provider is specified the SecureRandom instances will be
     * created using the defaults. If that fails, the SecureRandom instances
     * will be created using platform defaults.
     */
    protected String secureRandomAlgorithm = "SHA1PRNG";

    /**
     * The name of the provider to use to create instances of
     * {@link java.security.SecureRandom} which are used to generate session IDs.
     * If no algorithm is specified the of SHA1PRNG default is used. If an
     * invalid algorithm and/or provider is specified the SecureRandom instances
     * will be created using the defaults. If that fails, the SecureRandom
     * instances will be created using platform defaults.
     */
    protected String secureRandomProvider = null;

    /**
     * id 生成器
     */
    protected SessionIdGenerator sessionIdGenerator = null;
    /**
     * id生成器实现类
     */
    protected Class<? extends SessionIdGenerator> sessionIdGeneratorClass = null;

    /**
     * The longest time (in seconds) that an expired session had been alive.
     * manager 下每个session 默认的最大存活时间
     */
    protected volatile int sessionMaxAliveTime;
    /**
     * 可能多个线程会同时 操作manager 所以需要对 sessionMaxAliveTime 进行加锁
     */
    private final Object sessionMaxAliveTimeUpdateLock = new Object();


    /**
     * 某个缓存大小
     */
    protected static final int TIMING_STATS_CACHE_SIZE = 100;

    /**
     * session 的创建时间被包装成一个 Timing 对象 并保存到 相应的队列中 不过需要注意的是 这里使用的是非线程安全的队列
     */
    protected final Deque<SessionTiming> sessionCreationTiming =
            new LinkedList<>();

    /**
     * 存放过期的session
     */
    protected final Deque<SessionTiming> sessionExpirationTiming =
            new LinkedList<>();

    /**
     * Number of sessions that have expired.
     * 已经过期的session 数量
     */
    protected final AtomicLong expiredSessions = new AtomicLong(0);


    /**
     * The set of currently active Sessions for this Manager, keyed by
     * session identifier.
     * key 对应 sessionId value 对应session 对象
     */
    protected Map<String, Session> sessions = new ConcurrentHashMap<>();

    // Number of sessions created by this manager    每当触发一次 createSession 时 该数值就会加1 但是 addSession 不会修改该数值
    protected long sessionCounter=0;

    /**
     * 最大活跃数量  每当添加一个 sesion 时都会记录此时 manager下的session数量如果超过了之前的最大值，则进行更新
     */
    protected volatile int maxActive=0;

    /**
     * 更新最大活跃数使用的 lock
     */
    private final Object maxActiveUpdateLock = new Object();

    /**
     * The maximum number of active Sessions allowed, or -1 for no limit.
     * 最大活跃session 数量  -1 代表没有限制
     */
    protected int maxActiveSessions = -1;

    /**
     * Number of session creations that failed due to maxActiveSessions.
     * 当超过了 最大维护的session 数量后 继续创建session 将会失败
     */
    protected int rejectedSessions = 0;

    // number of duplicated session ids - anything >0 means we have problems   代表 sessionid 的重复数量 如果超过0 就代表出现了问题
    protected volatile int duplicates=0;

    /**
     * Processing time during session expiration.
     * 处理超时session 所花的时间
     */
    protected long processingTime = 0;

    /**
     * Iteration count for background processing.
     * 记录后台处理器的数量
     */
    private int count = 0;


    /**
     * Frequency of the session expiration, and related manager operations.
     * Manager operations will be done once for the specified amount of
     * backgroundProcess calls (ie, the lower the amount, the most often the
     * checks will occur).
     * 处理频率
     */
    protected int processExpiresFrequency = 6;

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(ManagerBase.class);

    /**
     * The property change support for this component.
     */
    protected final PropertyChangeSupport support =
            new PropertyChangeSupport(this);

    /**
     * session attr 名称的正则对象
     */
    private Pattern sessionAttributeNamePattern;

    /**
     * session attr.value 的类名正则对象
     */
    private Pattern sessionAttributeValueClassNamePattern;

    /**
     * 当被拦截时 是否需要发出警告
     */
    private boolean warnOnSessionAttributeFilterFailure;


    // ------------------------------------------------------------ Constructors

    public ManagerBase() {
        // 是否开启安全措施
        if (Globals.IS_SECURITY_ENABLED) {
            // Minimum set required for default distribution/persistence to work
            // plus String  当开启时  value 必须是 java 中的 某些基本类型 而不能是自定义类型
            setSessionAttributeValueClassNameFilter(
                    "java\\.lang\\.(?:Boolean|Integer|Long|Number|String)");
            // 当为 session设置 attr 被拦截时 需要打印警告信息
            setWarnOnSessionAttributeFilterFailure(true);
        }
    }


    // -------------------------------------------------------------- Properties

    /**
     * Obtain the regular expression used to filter session attribute based on
     * attribute name. The regular expression is anchored so it must match the
     * entire name
     *
     * @return The regular expression currently used to filter attribute names.
     *         {@code null} means no filter is applied. If an empty string is
     *         specified then no names will match the filter and all attributes
     *         will be blocked.
     */
    public String getSessionAttributeNameFilter() {
        if (sessionAttributeNamePattern == null) {
            return null;
        }
        return sessionAttributeNamePattern.toString();
    }


    /**
     * Set the regular expression to use to filter session attributes based on
     * attribute name. The regular expression is anchored so it must match the
     * entire name.
     *
     * @param sessionAttributeNameFilter The regular expression to use to filter
     *        session attributes based on attribute name. Use {@code null} if no
     *        filtering is required. If an empty string is specified then no
     *        names will match the filter and all attributes will be blocked.
     *
     * @throws PatternSyntaxException If the expression is not valid
     */
    public void setSessionAttributeNameFilter(String sessionAttributeNameFilter)
            throws PatternSyntaxException {
        if (sessionAttributeNameFilter == null || sessionAttributeNameFilter.length() == 0) {
            sessionAttributeNamePattern = null;
        } else {
            sessionAttributeNamePattern = Pattern.compile(sessionAttributeNameFilter);
        }
    }


    /**
     * Provides {@link #getSessionAttributeNameFilter()} as a pre-compiled
     * regular expression pattern.
     *
     * @return The pre-compiled pattern used to filter session attributes based
     *         on attribute name. {@code null} means no filter is applied.
     */
    protected Pattern getSessionAttributeNamePattern() {
        return sessionAttributeNamePattern;
    }


    /**
     * Obtain the regular expression used to filter session attribute based on
     * the implementation class of the value. The regular expression is anchored
     * and must match the fully qualified class name.
     *
     * @return The regular expression currently used to filter class names.
     *         {@code null} means no filter is applied. If an empty string is
     *         specified then no names will match the filter and all attributes
     *         will be blocked.
     */
    public String getSessionAttributeValueClassNameFilter() {
        if (sessionAttributeValueClassNamePattern == null) {
            return null;
        }
        return sessionAttributeValueClassNamePattern.toString();
    }


    /**
     * Provides {@link #getSessionAttributeValueClassNameFilter()} as a
     * pre-compiled regular expression pattern.
     *
     * @return The pre-compiled pattern used to filter session attributes based
     *         on the implementation class name of the value. {@code null} means
     *         no filter is applied.
     */
    protected Pattern getSessionAttributeValueClassNamePattern() {
        return sessionAttributeValueClassNamePattern;
    }


    /**
     * Set the regular expression to use to filter classes used for session
     * attributes. The regular expression is anchored and must match the fully
     * qualified class name.
     *
     * @param sessionAttributeValueClassNameFilter The regular expression to use
     *            to filter session attributes based on class name. Use {@code
     *            null} if no filtering is required. If an empty string is
     *           specified then no names will match the filter and all
     *           attributes will be blocked.
     *
     * @throws PatternSyntaxException If the expression is not valid
     *
     */
    public void setSessionAttributeValueClassNameFilter(String sessionAttributeValueClassNameFilter)
            throws PatternSyntaxException {
        if (sessionAttributeValueClassNameFilter == null ||
                sessionAttributeValueClassNameFilter.length() == 0) {
            sessionAttributeValueClassNamePattern = null;
        } else {
            sessionAttributeValueClassNamePattern =
                    Pattern.compile(sessionAttributeValueClassNameFilter);
        }
    }


    /**
     * Should a warn level log message be generated if a session attribute is
     * not persisted / replicated / restored.
     *
     * @return {@code true} if a warn level log message should be generated
     */
    public boolean getWarnOnSessionAttributeFilterFailure() {
        return warnOnSessionAttributeFilterFailure;
    }


    /**
     * Configure whether or not a warn level log message should be generated if
     * a session attribute is not persisted / replicated / restored.
     *
     * @param warnOnSessionAttributeFilterFailure {@code true} if the
     *            warn level message should be generated
     *
     */
    public void setWarnOnSessionAttributeFilterFailure(
            boolean warnOnSessionAttributeFilterFailure) {
        this.warnOnSessionAttributeFilterFailure = warnOnSessionAttributeFilterFailure;
    }


    /**
     * 获得 上下文对象  当session 触发响应事件时都要从 context中找到对应的 监听器
     * @return
     */
    @Override
    public Context getContext() {
        return context;
    }


    /**
     * 设置上下文对象
     * @param context The newly associated Context
     */
    @Override
    public void setContext(Context context) {
        if (this.context == context) {
            // NO-OP
            return;
        }
        // 必须在初始化阶段才能设置 context
        if (!getState().equals(LifecycleState.NEW)) {
            throw new IllegalStateException(sm.getString("managerBase.setContextNotNew"));
        }
        Context oldContext = this.context;
        this.context = context;
        support.firePropertyChange("context", oldContext, this.context);
    }


    /**
     * @return The name of the implementation class.
     */
    public String getClassName() {
        return this.getClass().getName();
    }


    @Override
    public SessionIdGenerator getSessionIdGenerator() {
        if (sessionIdGenerator != null) {
            return sessionIdGenerator;
        } else if (sessionIdGeneratorClass != null) {
            try {
                sessionIdGenerator = sessionIdGeneratorClass.getConstructor().newInstance();
                return sessionIdGenerator;
            } catch(ReflectiveOperationException ex) {
                // Ignore
            }
        }
        return null;
    }


    @Override
    public void setSessionIdGenerator(SessionIdGenerator sessionIdGenerator) {
        this.sessionIdGenerator = sessionIdGenerator;
        sessionIdGeneratorClass = sessionIdGenerator.getClass();
    }


    /**
     * @return The descriptive short name of this Manager implementation.
     * 获取当前manager 的实现类名
     */
    public String getName() {
        return name;
    }

    /**
     * @return The secure random number generator class name.
     */
    public String getSecureRandomClass() {
        return this.secureRandomClass;
    }


    /**
     * Set the secure random number generator class name.
     *
     * @param secureRandomClass The new secure random number generator class
     *                          name
     */
    public void setSecureRandomClass(String secureRandomClass) {

        String oldSecureRandomClass = this.secureRandomClass;
        this.secureRandomClass = secureRandomClass;
        support.firePropertyChange("secureRandomClass", oldSecureRandomClass,
                                   this.secureRandomClass);

    }


    /**
     * @return The secure random number generator algorithm name.
     */
    public String getSecureRandomAlgorithm() {
        return secureRandomAlgorithm;
    }


    /**
     * Set the secure random number generator algorithm name.
     *
     * @param secureRandomAlgorithm The new secure random number generator
     *                              algorithm name
     */
    public void setSecureRandomAlgorithm(String secureRandomAlgorithm) {
        this.secureRandomAlgorithm = secureRandomAlgorithm;
    }


    /**
     * @return The secure random number generator provider name.
     */
    public String getSecureRandomProvider() {
        return secureRandomProvider;
    }


    /**
     * Set the secure random number generator provider name.
     *
     * @param secureRandomProvider The new secure random number generator
     *                             provider name
     */
    public void setSecureRandomProvider(String secureRandomProvider) {
        this.secureRandomProvider = secureRandomProvider;
    }


    @Override
    public int getRejectedSessions() {
        return rejectedSessions;
    }


    @Override
    public long getExpiredSessions() {
        return expiredSessions.get();
    }


    @Override
    public void setExpiredSessions(long expiredSessions) {
        this.expiredSessions.set(expiredSessions);
    }

    public long getProcessingTime() {
        return processingTime;
    }


    public void setProcessingTime(long processingTime) {
        this.processingTime = processingTime;
    }

    /**
     * @return The frequency of manager checks.
     */
    public int getProcessExpiresFrequency() {
        return this.processExpiresFrequency;
    }

    /**
     * Set the manager checks frequency.
     *
     * @param processExpiresFrequency the new manager checks frequency
     *                                设置 处理频率 该属性在子类中用到
     */
    public void setProcessExpiresFrequency(int processExpiresFrequency) {

        if (processExpiresFrequency <= 0) {
            return;
        }

        int oldProcessExpiresFrequency = this.processExpiresFrequency;
        this.processExpiresFrequency = processExpiresFrequency;
        support.firePropertyChange("processExpiresFrequency",
                                   Integer.valueOf(oldProcessExpiresFrequency),
                                   Integer.valueOf(this.processExpiresFrequency));

    }
    // --------------------------------------------------------- Public Methods


    /**
     * {@inheritDoc}
     * <p>
     * Direct call to {@link #processExpires()}
     * 调用该方法会触发 processExpire 方法
     */
    @Override
    public void backgroundProcess() {
        // 每调用 processExpiresFrequency 次数的 backgroundProcess  方法才会触发一次 processExpires
        count = (count + 1) % processExpiresFrequency;
        if (count == 0)
            processExpires();
    }

    /**
     * Invalidate all sessions that have expired.
     * 处理过期事件
     */
    public void processExpires() {

        long timeNow = System.currentTimeMillis();
        // 获取当前 manager 下所有的session
        Session sessions[] = findSessions();
        int expireHere = 0 ;

        if(log.isDebugEnabled())
            log.debug("Start expire sessions " + getName() + " at " + timeNow + " sessioncount " + sessions.length);
        // 记录当前所有session 中 过期的数量
        for (int i = 0; i < sessions.length; i++) {
            if (sessions[i]!=null && !sessions[i].isValid()) {
                expireHere++;
            }
        }
        long timeEnd = System.currentTimeMillis();
        if(log.isDebugEnabled())
             log.debug("End expire sessions " + getName() + " processingTime " + (timeEnd - timeNow) + " expired sessions: " + expireHere);
        // 代表处理一次所消耗的时间
        processingTime += ( timeEnd - timeNow );

    }


    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal();

        // 触发 init 前 manager 必须要初始化 context 才可以
        if (context == null) {
            throw new LifecycleException(sm.getString("managerBase.contextNull"));
        }
    }


    /**
     * 启动 manager 对象
     * @throws LifecycleException
     */
    @Override
    protected void startInternal() throws LifecycleException {

        // Ensure caches for timing stats are the right size by filling with
        // nulls.   这个 cache 代表队列中预存的对象数量
        while (sessionCreationTiming.size() < TIMING_STATS_CACHE_SIZE) {
            // 注意 linkedList 是可以插入 null 作为元素的
            sessionCreationTiming.add(null);
        }
        while (sessionExpirationTiming.size() < TIMING_STATS_CACHE_SIZE) {
            sessionExpirationTiming.add(null);
        }

        /* Create sessionIdGenerator if not explicitly configured */
        SessionIdGenerator sessionIdGenerator = getSessionIdGenerator();
        if (sessionIdGenerator == null) {
            sessionIdGenerator = new StandardSessionIdGenerator();
            setSessionIdGenerator(sessionIdGenerator);
        }

        // 这里获取 jvm路径 主要是用于 分布式环境下确保能生成唯一sessionId
        sessionIdGenerator.setJvmRoute(getJvmRoute());
        if (sessionIdGenerator instanceof SessionIdGeneratorBase) {
            SessionIdGeneratorBase sig = (SessionIdGeneratorBase)sessionIdGenerator;
            sig.setSecureRandomAlgorithm(getSecureRandomAlgorithm());
            sig.setSecureRandomClass(getSecureRandomClass());
            sig.setSecureRandomProvider(getSecureRandomProvider());
        }

        if (sessionIdGenerator instanceof Lifecycle) {
            ((Lifecycle) sessionIdGenerator).start();
        } else {
            // Force initialization of the random number generator
            if (log.isDebugEnabled())
                log.debug("Force random number initialization starting");
            sessionIdGenerator.generateSessionId();
            if (log.isDebugEnabled())
                log.debug("Force random number initialization completed");
        }
    }


    /**
     * stop 只是停止了 id生成器
     * @throws LifecycleException
     */
    @Override
    protected void stopInternal() throws LifecycleException {
        if (sessionIdGenerator instanceof Lifecycle) {
            ((Lifecycle) sessionIdGenerator).stop();
        }
    }


    /**
     * 将某个session 对象添加到manager 中
     * @param session Session to be added
     */
    @Override
    public void add(Session session) {
        sessions.put(session.getIdInternal(), session);
        // 此时获取最新的 活跃数量  也就是 sessions.size()
        int size = getActiveSessions();
        if( size > maxActive ) {
            synchronized(maxActiveUpdateLock) {
                if( size > maxActive ) {
                    maxActive = size;
                }
            }
        }
    }


    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        support.addPropertyChangeListener(listener);
    }


    /**
     * 通过 指定sessionId 的方式 创建 session 对象
     * @param sessionId The session id which should be used to create the
     *  new session; if <code>null</code>, the session
     *  id will be assigned by this method, and available via the getId()
     *  method of the returned session.
     * @return
     */
    @Override
    public Session createSession(String sessionId) {

        // 如果当前 活跃数量超过了 最大限制 那么抛出异常  不过 addSession 方法 却没有相似的限制
        if ((maxActiveSessions >= 0) &&
                (getActiveSessions() >= maxActiveSessions)) {
            rejectedSessions++;
            throw new TooManyActiveSessionsException(
                    sm.getString("managerBase.createSession.ise"),
                    maxActiveSessions);
        }

        // Recycle or create a Session instance
        // 这里创建一个新的session
        Session session = createEmptySession();

        // Initialize the properties of the new session and return it
        session.setNew(true);   // 创建出来的session 对象  isNew 为true
        session.setValid(true);  // 默认是有效的
        session.setCreationTime(System.currentTimeMillis()); // 设置创建时间
        session.setMaxInactiveInterval(getContext().getSessionTimeout() * 60);  // 从context 中获取到session的默认存活时间
        String id = sessionId;
        // 如果为指定session 的场景 使用 idGenerator 生成
        if (id == null) {
            id = generateSessionId();
        }
        session.setId(id);
        // 增加计数器的值
        sessionCounter++;

        // 同时生成一个 timing 对象
        SessionTiming timing = new SessionTiming(session.getCreationTime(), 0);
        synchronized (sessionCreationTiming) {
            // 将 timing 对象设置到 链表中
            sessionCreationTiming.add(timing);
            // 这里将首节点 从链表中移除 保证了 链表的长度不变  记得一开始初始化该对象是 链表中 维护了一堆 null节点
            sessionCreationTiming.poll();
        }
        return session;
    }


    @Override
    public Session createEmptySession() {
        return getNewSession();
    }


    /**
     * 通过 sessionId 查询 session 对象
     * @param id The session id for the session to be returned
     *
     * @return
     * @throws IOException
     */
    @Override
    public Session findSession(String id) throws IOException {
        if (id == null) {
            return null;
        }
        return sessions.get(id);
    }


    @Override
    public Session[] findSessions() {
        return sessions.values().toArray(new Session[0]);
    }


    /**
     * 将session 从数组中移除
     * @param session Session to be removed
     */
    @Override
    public void remove(Session session) {
        remove(session, false);
    }


    @Override
    public void remove(Session session, boolean update) {
        // If the session has expired - as opposed to just being removed from
        // the manager because it is being persisted - update the expired stats
        // 如果需要更新manager
        if (update) {
            long timeNow = System.currentTimeMillis();
            // 记录该session 的存活时间
            int timeAlive =
                (int) (timeNow - session.getCreationTimeInternal())/1000;
            // 尝试更新session的最大存活时间
            updateSessionMaxAliveTime(timeAlive);
            expiredSessions.incrementAndGet();
            // 如果某个 session 要移除 会添加到 expiration 链表中
            SessionTiming timing = new SessionTiming(timeNow, timeAlive);
            synchronized (sessionExpirationTiming) {
                sessionExpirationTiming.add(timing);
                sessionExpirationTiming.poll();
            }
        }

        if (session.getIdInternal() != null) {
            sessions.remove(session.getIdInternal());
        }
    }


    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        support.removePropertyChangeListener(listener);
    }


    /**
     * 修改某个session 的 id
     * @param session   The session to change the session ID for
     */
    @Override
    public void changeSessionId(Session session) {
        String newId = generateSessionId();
        changeSessionId(session, newId, true, true);
    }


    @Override
    public void changeSessionId(Session session, String newId) {
        changeSessionId(session, newId, true, true);
    }


    protected void changeSessionId(Session session, String newId,
            boolean notifySessionListeners, boolean notifyContainerListeners) {
        String oldId = session.getIdInternal();
        // 在更新时 会将session 先从 manager 中移除 之后又重新加入
        session.setId(newId, false);
        // 触发监听器
        session.tellChangedSessionId(newId, oldId,
                notifySessionListeners, notifyContainerListeners);
    }


    /**
     * {@inheritDoc}
     * <p>
     * This implementation excludes session attributes from distribution if the:
     * <ul>
     * <li>attribute name matches {@link #getSessionAttributeNameFilter()}</li>
     * </ul>
     * 判断attr 是否支持分布式环境
     */
    @Override
    public boolean willAttributeDistribute(String name, Object value) {
        Pattern sessionAttributeNamePattern = getSessionAttributeNamePattern();
        if (sessionAttributeNamePattern != null) {
            // 这里还会对 类型进行校验
            if (!sessionAttributeNamePattern.matcher(name).matches()) {
                if (getWarnOnSessionAttributeFilterFailure() || log.isDebugEnabled()) {
                    String msg = sm.getString("managerBase.sessionAttributeNameFilter",
                            name, sessionAttributeNamePattern);
                    if (getWarnOnSessionAttributeFilterFailure()) {
                        log.warn(msg);
                    } else {
                        log.debug(msg);
                    }
                }
                return false;
            }
        }

        Pattern sessionAttributeValueClassNamePattern = getSessionAttributeValueClassNamePattern();
        if (value != null && sessionAttributeValueClassNamePattern != null) {
            if (!sessionAttributeValueClassNamePattern.matcher(
                    value.getClass().getName()).matches()) {
                if (getWarnOnSessionAttributeFilterFailure() || log.isDebugEnabled()) {
                    String msg = sm.getString("managerBase.sessionAttributeValueClassNameFilter",
                            name, value.getClass().getName(), sessionAttributeValueClassNamePattern);
                    if (getWarnOnSessionAttributeFilterFailure()) {
                        log.warn(msg);
                    } else {
                        log.debug(msg);
                    }
                }
                return false;
            }
        }

        return true;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Get new session class to be used in the doLoad() method.
     * @return a new session for use with this manager
     */
    protected StandardSession getNewSession() {
        return new StandardSession(this);
    }


    /**
     * Generate and return a new session identifier.
     * @return a new session id
     * 生成唯一的 sessionId
     */
    protected String generateSessionId() {

        String result = null;

        do {
            if (result != null) {
                // Not thread-safe but if one of multiple increments is lost
                // that is not a big deal since the fact that there was any
                // duplicate is a much bigger issue.   当发生重复时 修改该数值 没有做原子操作 因为 只要超过0 就代表出现问题了
                // 至于变成了1 还是2没有 区别
                duplicates++;
            }

            result = sessionIdGenerator.generateSessionId();

        } while (sessions.containsKey(result));

        return result;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Retrieve the enclosing Engine for this Manager.
     *
     * @return an Engine object (or null).
     * 获取manager 绑定的 engine 对象
     */
    public Engine getEngine() {
        Engine e = null;
        // 获取当前上下文对象 如果不是engine 类型 那么获取父类型 直到获得的是engine
        for (Container c = getContext(); e == null && c != null ; c = c.getParent()) {
            if (c instanceof Engine) {
                e = (Engine)c;
            }
        }
        return e;
    }


    /**
     * Retrieve the JvmRoute for the enclosing Engine.
     * @return the JvmRoute or null.
     * 获取 engine.jvmRoute() 属性
     */
    public String getJvmRoute() {
        Engine e = getEngine();
        return e == null ? null : e.getJvmRoute();
    }


    // -------------------------------------------------------- Package Methods


    /**
     * 获取 当前一共产生的 session 数量
     * @param sessionCounter Total number of sessions created by this manager.
     */
    @Override
    public void setSessionCounter(long sessionCounter) {
        this.sessionCounter = sessionCounter;
    }


    @Override
    public long getSessionCounter() {
        return sessionCounter;
    }


    /**
     * Number of duplicated session IDs generated by the random source.
     * Anything bigger than 0 means problems.
     *
     * @return The count of duplicates
     * 获取 sessionId 重复数量 一旦超过0 就代表出现了问题
     */
    public int getDuplicates() {
        return duplicates;
    }


    public void setDuplicates(int duplicates) {
        this.duplicates = duplicates;
    }


    @Override
    public int getActiveSessions() {
        return sessions.size();
    }


    @Override
    public int getMaxActive() {
        return maxActive;
    }


    @Override
    public void setMaxActive(int maxActive) {
        synchronized (maxActiveUpdateLock) {
            this.maxActive = maxActive;
        }
    }


    /**
     * @return The maximum number of active Sessions allowed, or -1 for no
     *         limit.
     */
    public int getMaxActiveSessions() {
        return this.maxActiveSessions;
    }


    /**
     * Set the maximum number of active Sessions allowed, or -1 for
     * no limit.
     *
     * @param max The new maximum number of sessions
     */
    public void setMaxActiveSessions(int max) {

        int oldMaxActiveSessions = this.maxActiveSessions;
        this.maxActiveSessions = max;
        support.firePropertyChange("maxActiveSessions",
                                   Integer.valueOf(oldMaxActiveSessions),
                                   Integer.valueOf(this.maxActiveSessions));

    }


    @Override
    public int getSessionMaxAliveTime() {
        return sessionMaxAliveTime;
    }


    @Override
    public void setSessionMaxAliveTime(int sessionMaxAliveTime) {
        synchronized (sessionMaxAliveTimeUpdateLock) {
            this.sessionMaxAliveTime = sessionMaxAliveTime;
        }
    }


    /**
     * Updates the sessionMaxAliveTime attribute if the candidate value is
     * larger than the current value.
     *
     * @param sessionAliveTime  The candidate value (in seconds) for the new
     *                          sessionMaxAliveTime value.
     *                          尝试更新session 最大存活时长
     */
    public void updateSessionMaxAliveTime(int sessionAliveTime) {
        if (sessionAliveTime > this.sessionMaxAliveTime) {
            synchronized (sessionMaxAliveTimeUpdateLock) {
                if (sessionAliveTime > this.sessionMaxAliveTime) {
                    this.sessionMaxAliveTime = sessionAliveTime;
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Based on the last 100 sessions to expire. If less than 100 sessions have
     * expired then all available data is used.
     * 获取session的平均存活时间
     */
    @Override
    public int getSessionAverageAliveTime() {
        // Copy current stats
        List<SessionTiming> copy = new ArrayList<>();
        // 通过拷贝副本的方式 减少并发控制范围 实际上就是一个  CopyOnWriter 数组
        synchronized (sessionExpirationTiming) {
            copy.addAll(sessionExpirationTiming);
        }

        // Init
        int counter = 0;
        int result = 0;

        // Calculate average
        for (SessionTiming timing : copy) {
            if (timing != null) {
                // 针对过期 session duration 是该session 的存活时间
                int timeAlive = timing.getDuration();
                counter++;
                // Very careful not to overflow - probably not necessary
                result =
                    (result * ((counter - 1)/counter)) + (timeAlive/counter);
            }
        }
        return result;
    }


    /**
     * {@inheritDoc}<p>
     * Based on the creation time of the previous 100 sessions created. If less
     * than 100 sessions have been created then all available data is used.
     * 计算单位时间内 创建的 session 数量
     */
    @Override
    public int getSessionCreateRate() {
        // Copy current stats
        List<SessionTiming> copy = new ArrayList<>();
        synchronized (sessionCreationTiming) {
            copy.addAll(sessionCreationTiming);
        }

        return calculateRate(copy);
    }


    /**
     * {@inheritDoc}
     * <p>
     * Based on the expiry time of the previous 100 sessions expired. If less
     * than 100 sessions have expired then all available data is used.
     *
     * @return  The current rate (in sessions per minute) of session expiration
     */
    @Override
    public int getSessionExpireRate() {
        // Copy current stats
        List<SessionTiming> copy = new ArrayList<>();
        synchronized (sessionExpirationTiming) {
            copy.addAll(sessionExpirationTiming);
        }

        return calculateRate(copy);
    }


    /**
     * 计算比率
     * @param sessionTiming
     * @return
     */
    private static int calculateRate(List<SessionTiming> sessionTiming) {
        // Init
        long now = System.currentTimeMillis();
        long oldest = now;
        int counter = 0;
        int result = 0;

        // Calculate rate  这里计算最小的 timing 对应的时间戳
        for (SessionTiming timing : sessionTiming) {
            if (timing != null) {
                counter++;
                if (timing.getTimestamp() < oldest) {
                    oldest = timing.getTimestamp();
                }
            }
        }
        if (counter > 0) {
            if (oldest < now) {
                // 将创建数量 比上时间 就得到创建的时间比率
                result = (1000*60*counter)/(int) (now - oldest);
            } else {
                // Better than reporting zero
                result = Integer.MAX_VALUE;
            }
        }
        return result;
    }


    /**
     * For debugging.
     *
     * @return A space separated list of all session IDs currently active
     */
    public String listSessionIds() {
        StringBuilder sb = new StringBuilder();
        for (String s : sessions.keySet()) {
            sb.append(s).append(" ");
        }
        return sb.toString();
    }


    /**
     * For debugging.
     *
     * @param sessionId The ID for the session of interest
     * @param key       The key for the attribute to obtain
     *
     * @return The attribute value for the specified session, if found, null
     *         otherwise
     */
    public String getSessionAttribute( String sessionId, String key ) {
        // 通过id 找到session 再通过key 找到 attrValue
        Session s = sessions.get(sessionId);
        if( s==null ) {
            if(log.isInfoEnabled())
                log.info("Session not found " + sessionId);
            return null;
        }
        Object o=s.getSession().getAttribute(key);
        if( o==null ) return null;
        return o.toString();
    }


    /**
     * Returns information about the session with the given session id.
     *
     * <p>The session information is organized as a HashMap, mapping
     * session attribute names to the String representation of their values.
     *
     * @param sessionId Session id
     *
     * @return HashMap mapping session attribute names to the String
     * representation of their values, or null if no session with the
     * specified id exists, or if the session does not have any attributes
     */
    public HashMap<String, String> getSession(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null) {
            if (log.isInfoEnabled()) {
                log.info("Session not found " + sessionId);
            }
            return null;
        }

        // 获取内部的 httpSession  实际上是返回一个门面类对象
        Enumeration<String> ee = s.getSession().getAttributeNames();
        if (ee == null || !ee.hasMoreElements()) {
            return null;
        }

        HashMap<String, String> map = new HashMap<>();
        while (ee.hasMoreElements()) {
            String attrName = ee.nextElement();
            map.put(attrName, getSessionAttribute(sessionId, attrName));
        }

        return map;
    }


    /**
     * 将某个session 设置成过期状态
     * @param sessionId
     */
    public void expireSession( String sessionId ) {
        Session s=sessions.get(sessionId);
        if( s==null ) {
            if(log.isInfoEnabled())
                log.info("Session not found " + sessionId);
            return;
        }
        s.expire();
    }

    public long getThisAccessedTimestamp( String sessionId ) {
        Session s=sessions.get(sessionId);
        if(s== null)
            return -1 ;
        return s.getThisAccessedTime();
    }

    public String getThisAccessedTime( String sessionId ) {
        Session s=sessions.get(sessionId);
        if( s==null ) {
            if(log.isInfoEnabled())
                log.info("Session not found " + sessionId);
            return "";
        }
        return new Date(s.getThisAccessedTime()).toString();
    }

    public long getLastAccessedTimestamp( String sessionId ) {
        Session s=sessions.get(sessionId);
        if(s== null)
            return -1 ;
        return s.getLastAccessedTime();
    }

    public String getLastAccessedTime( String sessionId ) {
        Session s=sessions.get(sessionId);
        if( s==null ) {
            if(log.isInfoEnabled())
                log.info("Session not found " + sessionId);
            return "";
        }
        return new Date(s.getLastAccessedTime()).toString();
    }

    public String getCreationTime( String sessionId ) {
        Session s=sessions.get(sessionId);
        if( s==null ) {
            if(log.isInfoEnabled())
                log.info("Session not found " + sessionId);
            return "";
        }
        return new Date(s.getCreationTime()).toString();
    }

    public long getCreationTimestamp( String sessionId ) {
        Session s=sessions.get(sessionId);
        if(s== null)
            return -1 ;
        return s.getCreationTime();
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(this.getClass().getName());
        sb.append('[');
        if (context == null) {
            sb.append("Context is null");
        } else {
            sb.append(context.getName());
        }
        sb.append(']');
        return sb.toString();
    }


    // -------------------- JMX and Registration  --------------------
    @Override
    public String getObjectNameKeyProperties() {

        StringBuilder name = new StringBuilder("type=Manager");

        name.append(",host=");
        name.append(context.getParent().getName());

        name.append(",context=");
        String contextName = context.getName();
        if (!contextName.startsWith("/")) {
            name.append('/');
        }
        name.append(contextName);

        return name.toString();
    }

    @Override
    public String getDomainInternal() {
        return context.getDomain();
    }


    // ----------------------------------------------------------- Inner classes

    /**
     * manager 的内部类
     */
    protected static final class SessionTiming {

        /**
         * 当前时间戳
         */
        private final long timestamp;
        /**
         * 间隔时间
         */
        private final int duration;

        public SessionTiming(long timestamp, int duration) {
            this.timestamp = timestamp;
            this.duration = duration;
        }

        /**
         * @return Time stamp associated with this piece of timing information
         *         in milliseconds.
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * @return Duration associated with this piece of timing information in
         *         seconds.
         */
        public int getDuration() {
            return duration;
        }
    }
}
