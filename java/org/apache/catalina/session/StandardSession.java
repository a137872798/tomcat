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

import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.WriteAbortedException;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.SessionEvent;
import org.apache.catalina.SessionListener;
import org.apache.catalina.TomcatPrincipal;
import org.apache.catalina.security.SecurityUtil;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * Standard implementation of the <b>Session</b> interface.  This object is
 * serializable, so that it can be stored in persistent storage or transferred
 * to a different JVM for distributable session support.
 * <p>
 * <b>IMPLEMENTATION NOTE</b>:  An instance of this class represents both the
 * internal (Session) and application level (HttpSession) view of the session.
 * However, because the class itself is not declared public, Java logic outside
 * of the <code>org.apache.catalina.session</code> package cannot cast an
 * HttpSession view of this instance back to a Session view.
 * <p>
 * <b>IMPLEMENTATION NOTE</b>:  If you add fields to this class, you must
 * make sure that you carry them over in the read/writeObject methods so
 * that this class is properly serialized.
 *
 * @author Craig R. McClanahan
 * @author Sean Legassick
 * @author <a href="mailto:jon@latchkey.com">Jon S. Stevens</a>
 *
 * tomcat 内部的标准session 对象 它实现了 servlet规范中 httpSession 接口 同时还实现了 tomcat内部的session 接口
 */
public class StandardSession implements HttpSession, Session, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否严格遵守servlet 规范
     */
    protected static final boolean STRICT_SERVLET_COMPLIANCE;

    /**
     * 活跃性检查
     */
    protected static final boolean ACTIVITY_CHECK;

    /**
     * 上一次访问的起始时间
     */
    protected static final boolean LAST_ACCESS_AT_START;

    static {
        STRICT_SERVLET_COMPLIANCE = Globals.STRICT_SERVLET_COMPLIANCE;

        // 从系统变量中获取是否需要做 活跃性检查
        String activityCheck = System.getProperty(
                "org.apache.catalina.session.StandardSession.ACTIVITY_CHECK");
        if (activityCheck == null) {
            // 如果严格遵守  那么就需要做活跃性检查
            ACTIVITY_CHECK = STRICT_SERVLET_COMPLIANCE;
        } else {
            ACTIVITY_CHECK = Boolean.parseBoolean(activityCheck);
        }

        // 如果严格遵守servlet 规范 那么就要记录最后次访问的起始时间
        String lastAccessAtStart = System.getProperty(
                "org.apache.catalina.session.StandardSession.LAST_ACCESS_AT_START");
        if (lastAccessAtStart == null) {
            LAST_ACCESS_AT_START = STRICT_SERVLET_COMPLIANCE;
        } else {
            LAST_ACCESS_AT_START = Boolean.parseBoolean(lastAccessAtStart);
        }
    }


    // ----------------------------------------------------------- Constructors


    /**
     * Construct a new Session associated with the specified Manager.
     *
     * @param manager The manager with which this Session is associated
     *                根据 manager 创建一个关联的session 对象
     */
    public StandardSession(Manager manager) {

        super();
        this.manager = manager;

        // Initialize access count  如果设置了 activity_check 那么就初始化一个计数器
        if (ACTIVITY_CHECK) {
            accessCount = new AtomicInteger();
        }

    }


    // ----------------------------------------------------- Instance Variables


    /**
     * Type array.
     * 创建一个空数组对象
     */
    protected static final String EMPTY_ARRAY[] = new String[0];


    /**
     * The collection of user data attributes associated with this Session.
     * 该session 内部维护的 属性容器  该属性在序列化时会保留
     */
    protected ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();


    /**
     * The authentication type used to authenticate our cached Principal,
     * if any.  NOTE:  This value is not included in the serialized
     * version of this object.
     */
    protected transient String authType = null;


    /**
     * The time this session was created, in milliseconds since midnight,
     * January 1, 1970 GMT.
     * 该session的创建时间
     */
    protected long creationTime = 0L;


    /**
     * We are currently processing a session expiration, so bypass
     * certain IllegalStateException tests.  NOTE:  This value is not
     * included in the serialized version of this object.
     * 当前是否过期
     */
    protected transient volatile boolean expiring = false;


    /**
     * The facade associated with this session.  NOTE:  This value is not
     * included in the serialized version of this object.
     * 这里包含一个门面类
     */
    protected transient StandardSessionFacade facade = null;


    /**
     * The session identifier of this Session.
     * 该session 关联的id
     */
    protected String id = null;


    /**
     * The last accessed time for this Session.
     * 最后一次访问该session 的时间戳
     */
    protected volatile long lastAccessedTime = creationTime;


    /**
     * The session event listeners for this Session.
     * 该session 关联的监听器对象
     */
    protected transient ArrayList<SessionListener> listeners = new ArrayList<>();


    /**
     * The Manager with which this Session is associated.
     * 该session 关联的manager 对象
     */
    protected transient Manager manager = null;


    /**
     * The maximum time interval, in seconds, between client requests before
     * the servlet container may invalidate this session.  A negative time
     * indicates that the session should never time out.
     * 最大失活时间 看来session 默认是不会过期 的 等下 这个过期机制是怎么实现的??? 惰性删除吗 或者使用一个额外线程进行清理???
     */
    protected volatile int maxInactiveInterval = -1;


    /**
     * Flag indicating whether this session is new or not.
     * 默认情况 session.isNew 为 false
     */
    protected volatile boolean isNew = false;


    /**
     * Flag indicating whether this session is valid or not.
     * 默认为无效
     */
    protected volatile boolean isValid = false;


    /**
     * Internal notes associated with this session by Catalina components
     * and event listeners.  <b>IMPLEMENTATION NOTE:</b> This object is
     * <em>not</em> saved and restored across session serializations!
     * 内部包含一个  note容器  该属性在序列化时不会被保存
     */
    protected transient Map<String, Object> notes = new Hashtable<>();


    /**
     * The authenticated Principal associated with this session, if any.
     * <b>IMPLEMENTATION NOTE:</b>  This object is <i>not</i> saved and
     * restored across session serializations!
     */
    protected transient Principal principal = null;


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(StandardSession.class);


    /**
     * The HTTP session context associated with this session.
     * httpSession 关联的 上下文对象  该类已经被弃用了 此时相关方法返回null 或者空容器
     */
    @Deprecated
    protected static volatile
            javax.servlet.http.HttpSessionContext sessionContext = null;


    /**
     * The property change support for this component.  NOTE:  This value
     * is not included in the serialized version of this object.
     */
    protected final transient PropertyChangeSupport support =
        new PropertyChangeSupport(this);


    /**
     * The current accessed time for this session.
     * 访问时间 默认为创建时间
     */
    protected volatile long thisAccessedTime = creationTime;


    /**
     * The access count for this session.
     * 访问次数
     */
    protected transient AtomicInteger accessCount = null;


    // ----------------------------------------------------- Session Properties


    /**
     * Return the authentication type used to authenticate our cached
     * Principal, if any.
     */
    @Override
    public String getAuthType() {
        return this.authType;
    }


    /**
     * Set the authentication type used to authenticate our cached
     * Principal, if any.
     *
     * @param authType The new cached authentication type
     *                 设置权限类型
     */
    @Override
    public void setAuthType(String authType) {
        String oldAuthType = this.authType;
        this.authType = authType;
        // 当设置权限类型时 触发 属性变化监听器  该对象属于 java.beans 的 先不看
        support.firePropertyChange("authType", oldAuthType, this.authType);
    }


    /**
     * Set the creation time for this session.  This method is called by the
     * Manager when an existing Session instance is reused.
     *
     * @param time The new creation time
     *             设置当前session的创建时间  同时访问时间也会与创建时间同步
     */
    @Override
    public void setCreationTime(long time) {

        this.creationTime = time;
        this.lastAccessedTime = time;
        this.thisAccessedTime = time;

    }


    /**
     * Return the session identifier for this session.
     */
    @Override
    public String getId() {
        return this.id;
    }


    /**
     * Return the session identifier for this session.
     * tomcat 内部的session 默认id 与 servlet.session 的id 一致
     */
    @Override
    public String getIdInternal() {
        return this.id;
    }


    /**
     * Set the session identifier for this session.
     *
     * @param id The new session identifier
     */
    @Override
    public void setId(String id) {
        setId(id, true);
    }


    /**
     * {@inheritDoc}
     * 当修改id 的时候是否要通知监听器 默认为true
     */
    @Override
    public void setId(String id, boolean notify) {

        // 如果将某个 session的id 修改 那么要先将 session 从manager中移除 之后更新id 后重新设置回manager
        if ((this.id != null) && (manager != null))
            manager.remove(this);

        this.id = id;

        if (manager != null)
            manager.add(this);

        // 通知生成了一个新的session
        if (notify) {
            tellNew();
        }
    }


    /**
     * Inform the listeners about the new session.
     * 代表创建了一个新的session
     */
    public void tellNew() {

        // Notify interested session event listeners
        // 触发session的创建事件 传入的data为null
        fireSessionEvent(Session.SESSION_CREATED_EVENT, null);

        // Notify interested application event listeners
        // 获取 manager相关的上下文对象  在tomcat 中 container 就是一种context  这里就是在获取session当前所属的容器
        Context context = manager.getContext();
        // 获取 容器级别的生命周期监听对象
        Object listeners[] = context.getApplicationLifecycleListeners();
        if (listeners != null && listeners.length > 0) {
            // 这里是 servlet 规范内的sessionEvent
            HttpSessionEvent event =
                new HttpSessionEvent(getSession());
            // 找到 监听器中 是 servlet.HttpSessionListener 的对象 并触发事件
            for (int i = 0; i < listeners.length; i++) {
                if (!(listeners[i] instanceof HttpSessionListener))
                    continue;
                HttpSessionListener listener =
                    (HttpSessionListener) listeners[i];
                try {
                    // 使用上下文对象去触发事件
                    context.fireContainerEvent("beforeSessionCreated",
                            listener);
                    // 触发监听器相关事件
                    listener.sessionCreated(event);
                    context.fireContainerEvent("afterSessionCreated", listener);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    try {
                        context.fireContainerEvent("afterSessionCreated",
                                listener);
                    } catch (Exception e) {
                        // Ignore
                    }
                    manager.getContext().getLogger().error
                        (sm.getString("standardSession.sessionEvent"), t);
                }
            }
        }

    }

    /**
     * Inform the listeners about the change session ID.
     *
     * @param newId  new session ID
     * @param oldId  old session ID
     * @param notifySessionListeners  Should any associated sessionListeners be
     *        notified that session ID has been changed?
     * @param notifyContainerListeners  Should any associated ContainerListeners
     *        be notified that session ID has been changed?
     *                                  修改当前session的id 同时根据  参数判断是否要通知相关监听器
     */
    @Override
    public void tellChangedSessionId(String newId, String oldId,
            boolean notifySessionListeners, boolean notifyContainerListeners) {
        // 获取 context对象 (实际上在 tomcat中还有 container 的含义)
        Context context = manager.getContext();
         // notify ContainerListeners  当需要通知 容器级别的监听器时
        if (notifyContainerListeners) {
            // 使用一个 session_id_change 事件触发监听器
            context.fireContainerEvent(Context.CHANGE_SESSION_ID_EVENT,
                    new String[] {oldId, newId});
        }

        // notify HttpSessionIdListener  当需要通知session级别监听器时
        if (notifySessionListeners) {
            // 从上下文对象中获取监听器 如果可以转换成 HttpSessionIdListener 则触发相关逻辑
            Object listeners[] = context.getApplicationEventListeners();
            if (listeners != null && listeners.length > 0) {
                HttpSessionEvent event =
                    new HttpSessionEvent(getSession());

                for(Object listener : listeners) {
                    if (!(listener instanceof HttpSessionIdListener))
                        continue;

                    HttpSessionIdListener idListener =
                        (HttpSessionIdListener)listener;
                    try {
                        // 触发 sessionIdChange
                        idListener.sessionIdChanged(event, oldId);
                    } catch (Throwable t) {
                        manager.getContext().getLogger().error
                            (sm.getString("standardSession.sessionEvent"), t);
                    }
                }
            }
        }
    }


    /**
     * Return the last time the client sent a request associated with this
     * session, as the number of milliseconds since midnight, January 1, 1970
     * GMT.  Actions that your application takes, such as getting or setting
     * a value associated with the session, do not affect the access time.
     * This one gets updated whenever a request starts.
     * 获取当前访问时间
     */
    @Override
    public long getThisAccessedTime() {

        // 判断 isValid 字段 如果为false 则代表当前session 已经无效了
        if (!isValidInternal()) {
            throw new IllegalStateException
                (sm.getString("standardSession.getThisAccessedTime.ise"));
        }

        return this.thisAccessedTime;
    }

    /**
     * Return the last client access time without invalidation check
     * @see #getThisAccessedTime()
     */
    @Override
    public long getThisAccessedTimeInternal() {
        return this.thisAccessedTime;
    }

    /**
     * Return the last time the client sent a request associated with this
     * session, as the number of milliseconds since midnight, January 1, 1970
     * GMT.  Actions that your application takes, such as getting or setting
     * a value associated with the session, do not affect the access time.
     * This one gets updated whenever a request finishes.
     * 获取最后的访问时间
     */
    @Override
    public long getLastAccessedTime() {

        if (!isValidInternal()) {
            throw new IllegalStateException
                (sm.getString("standardSession.getLastAccessedTime.ise"));
        }

        return this.lastAccessedTime;
    }

    /**
     * Return the last client access time without invalidation check
     * @see #getLastAccessedTime()
     */
    @Override
    public long getLastAccessedTimeInternal() {
        return this.lastAccessedTime;
    }

    /**
     * Return the idle time (in milliseconds) from last client access time.
     * 获取空闲时间
     */
    @Override
    public long getIdleTime() {

        if (!isValidInternal()) {
            throw new IllegalStateException
                (sm.getString("standardSession.getIdleTime.ise"));
        }

        return getIdleTimeInternal();
    }

    /**
     * Return the idle time from last client access time without invalidation check
     * @see #getIdleTime()
     * 获取空闲时间  也就是将 当前时间 - 最后访问时间
     */
    @Override
    public long getIdleTimeInternal() {
        long timeNow = System.currentTimeMillis();
        long timeIdle;
        // 在servlet 规范中 lastAccess 是代表开始处理req 的时候 那么就使用 lastAccessedTime字段
        if (LAST_ACCESS_AT_START) {
            timeIdle = timeNow - lastAccessedTime;
        } else {
            timeIdle = timeNow - thisAccessedTime;
        }
        return timeIdle;
    }

    /**
     * Return the Manager within which this Session is valid.
     * 获取该session 相关的manager 对象
     */
    @Override
    public Manager getManager() {
        return this.manager;
    }


    /**
     * Set the Manager within which this Session is valid.
     *
     * @param manager The new Manager
     */
    @Override
    public void setManager(Manager manager) {
        this.manager = manager;
    }


    /**
     * Return the maximum time interval, in seconds, between client requests
     * before the servlet container will invalidate the session.  A negative
     * time indicates that the session should never time out.
     * 获取session 的最大存活时间
     */
    @Override
    public int getMaxInactiveInterval() {
        return this.maxInactiveInterval;
    }


    /**
     * Set the maximum time interval, in seconds, between client requests
     * before the servlet container will invalidate the session.  A zero or
     * negative time indicates that the session should never time out.
     *
     * @param interval The new maximum interval
     */
    @Override
    public void setMaxInactiveInterval(int interval) {
        this.maxInactiveInterval = interval;
    }


    /**
     * Set the <code>isNew</code> flag for this session.
     *
     * @param isNew The new value for the <code>isNew</code> flag
     *              设置 isNew 标识
     */
    @Override
    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }


    /**
     * Return the authenticated Principal that is associated with this Session.
     * This provides an <code>Authenticator</code> with a means to cache a
     * previously authenticated Principal, and avoid potentially expensive
     * <code>Realm.authenticate()</code> calls on every request.  If there
     * is no current associated Principal, return <code>null</code>.
     */
    @Override
    public Principal getPrincipal() {
        return this.principal;
    }


    /**
     * Set the authenticated Principal that is associated with this Session.
     * This provides an <code>Authenticator</code> with a means to cache a
     * previously authenticated Principal, and avoid potentially expensive
     * <code>Realm.authenticate()</code> calls on every request.
     *
     * @param principal The new Principal, or <code>null</code> if none
     */
    @Override
    public void setPrincipal(Principal principal) {

        Principal oldPrincipal = this.principal;
        this.principal = principal;
        support.firePropertyChange("principal", oldPrincipal, this.principal);

    }


    /**
     * Return the <code>HttpSession</code> for which this object
     * is the facade.
     * 这里返回一个门面对象  虽然本对象也实现了 httpSession 接口 但是除此之外还有很多的 额外方法 为了不将这些暴露给用户 (比如使用者通过 强转的方式 访问到了一些不该访问的方法)
     * 所以这里又单独使用了一个门面对象
     */
    @Override
    public HttpSession getSession() {

        if (facade == null){
            if (SecurityUtil.isPackageProtectionEnabled()){
                final StandardSession fsession = this;
                facade = AccessController.doPrivileged(
                        new PrivilegedAction<StandardSessionFacade>(){
                    @Override
                    public StandardSessionFacade run(){
                        return new StandardSessionFacade(fsession);
                    }
                });
            } else {
                facade = new StandardSessionFacade(this);
            }
        }
        return (facade);

    }


    /**
     * Return the <code>isValid</code> flag for this session.
     * 这类型 方法 实际上都需要考虑并发问题 当校验session 是否有效时 一些核心的标识类是否正在被其他线程处理
     */
    @Override
    public boolean isValid() {

        // 代表当前session 已经无效
        if (!this.isValid) {
            return false;
        }

        // 代表正在处理过期的情况 此时session 还是有效的
        if (this.expiring) {
            return true;
        }

        // 如果访问次数大于0 就代表当前session 还是有效的  这里的 accessCount 使用 AtomicInteger 修饰  看来当某个session无效时 会将 accessCount重置
        if (ACTIVITY_CHECK && accessCount.get() > 0) {
            return true;
        }

        // 代表设置了 session最大超时时间  每当session 被重新访问时 会更新 accessTime 这样 类似于 续约 这样session 不会过期
        if (maxInactiveInterval > 0) {
            int timeIdle = (int) (getIdleTimeInternal() / 1000L);
            // 如果超时时间 超过了 最大存活时间 那么就需要将该session 设置为过期 那么session的删除机制使用的是惰性删除
            if (timeIdle >= maxInactiveInterval) {
                // 设置成超时 同时触发监听器
                expire(true);
            }
        }

        // 推测在 expire中会将 isVaild 设置成false
        return this.isValid;
    }


    /**
     * Set the <code>isValid</code> flag for this session.
     *
     * @param isValid The new value for the <code>isValid</code> flag
     */
    @Override
    public void setValid(boolean isValid) {
        this.isValid = isValid;
    }


    // ------------------------------------------------- Session Public Methods


    /**
     * Update the accessed time information for this session.  This method
     * should be called by the context when a request comes in for a particular
     * session, even if the application does not reference it.
     * 当某个session 被访问时触发
     */
    @Override
    public void access() {

        // 记录当前访问时间  如果需要记录访问次数 则增加计数器
        this.thisAccessedTime = System.currentTimeMillis();

        if (ACTIVITY_CHECK) {
            accessCount.incrementAndGet();
        }

    }


    /**
     * End the access.
     * 当访问结束时触发
     */
    @Override
    public void endAccess() {

        // 修改 isNew 标识
        isNew = false;

        /**
         * The servlet spec mandates to ignore request handling time
         * in lastAccessedTime.
         */
        if (LAST_ACCESS_AT_START) {
            this.lastAccessedTime = this.thisAccessedTime;
            this.thisAccessedTime = System.currentTimeMillis();
        } else {
            this.thisAccessedTime = System.currentTimeMillis();
            this.lastAccessedTime = this.thisAccessedTime;
        }

        if (ACTIVITY_CHECK) {
            accessCount.decrementAndGet();
        }

    }


    /**
     * Add a session event listener to this component.
     * 设置 session 监听器
     */
    @Override
    public void addSessionListener(SessionListener listener) {

        listeners.add(listener);
    }


    /**
     * Perform the internal processing required to invalidate this session,
     * without triggering an exception if the session has already expired.
     * 处理session 过期 默认情况会触发监听器
     */
    @Override
    public void expire() {

        expire(true);
    }


    /**
     * Perform the internal processing required to invalidate this session,
     * without triggering an exception if the session has already expired.
     *
     * @param notify Should we notify listeners about the demise of
     *  this session?   代表是否要通知监听器
     *               当前session 过期
     */
    public void expire(boolean notify) {

        // Check to see if session has already been invalidated.
        // Do not check expiring at this point as expire should not return until
        // isValid is false  如果当前session 已经失效 可能是多线程 同时触发 isValid 方法 且都进入到这里
        if (!isValid)
            return;

        // 实际处理逻辑进行加锁  尽可能减小锁范围
        synchronized (this) {
            // Check again, now we are inside the sync so this code only runs once
            // Double check locking - isValid needs to be volatile
            // The check of expiring is to ensure that an infinite loop is not
            // entered as per bug 56339   expiring 代表正在处理超时逻辑 这里是进行双重检查
            if (expiring || !isValid)
                return;

            // 如果manager 为null 则不需要处理 不过session 在创建时都会关联到一个manager 上
            if (manager == null)
                return;

            // Mark this session as "being expired"  标记成 正在处理
            expiring = true;

            // Notify interested application event listeners
            // FIXME - Assumes we call listeners in reverse order
            // 通过manager 获取到关联的上下文对象
            Context context = manager.getContext();

            // The call to expire() may not have been triggered by the webapp.
            // Make sure the webapp's class loader is set when calling the
            // listeners
            // 触发监听器的逻辑都是类似的 首先通过context 获取到绑定的一组监听器 然后遍历 当发现监听器是servlet.httpSessionListener 时进行触发
            if (notify) {
                ClassLoader oldContextClassLoader = null;
                try {
                    // TODO 这里绑定类加载器 需要注意 之后回顾下
                    oldContextClassLoader = context.bind(Globals.IS_SECURITY_ENABLED, null);
                    Object listeners[] = context.getApplicationLifecycleListeners();
                    if (listeners != null && listeners.length > 0) {
                        HttpSessionEvent event =
                                // getSession() 返回的是门面对象 隐藏了 除了 httpSession 之外的其他实现
                            new HttpSessionEvent(getSession());
                        for (int i = 0; i < listeners.length; i++) {
                            int j = (listeners.length - 1) - i;
                            if (!(listeners[j] instanceof HttpSessionListener))
                                continue;
                            HttpSessionListener listener =
                                (HttpSessionListener) listeners[j];
                            try {
                                // 触发 sessionDestroyed 事件
                                context.fireContainerEvent("beforeSessionDestroyed",
                                        listener);
                                listener.sessionDestroyed(event);
                                context.fireContainerEvent("afterSessionDestroyed",
                                        listener);
                            } catch (Throwable t) {
                                ExceptionUtils.handleThrowable(t);
                                try {
                                    context.fireContainerEvent(
                                            "afterSessionDestroyed", listener);
                                } catch (Exception e) {
                                    // Ignore
                                }
                                manager.getContext().getLogger().error
                                    (sm.getString("standardSession.sessionEvent"), t);
                            }
                        }
                    }
                } finally {
                    // 这里又 解除了绑定
                    context.unbind(Globals.IS_SECURITY_ENABLED, oldContextClassLoader);
                }
            }

            // 将访问数清空
            if (ACTIVITY_CHECK) {
                accessCount.set(0);
            }

            // Remove this session from our manager's active sessions
            // 将该session 从manager中移除
            manager.remove(this, true);

            // Notify interested session event listeners 如果需要通知监听器 这里触发
            if (notify) {
                fireSessionEvent(Session.SESSION_DESTROYED_EVENT, null);
            }

            // Call the logout method  权限相关的先不看
            if (principal instanceof TomcatPrincipal) {
                TomcatPrincipal gp = (TomcatPrincipal) principal;
                try {
                    // 这里进行登出
                    gp.logout();
                } catch (Exception e) {
                    manager.getContext().getLogger().error(
                            sm.getString("standardSession.logoutfail"),
                            e);
                }
            }

            // We have completed expire of this session  将session 标记成无效
            setValid(false);
            // 代表处理超时相关逻辑完成
            expiring = false;

            // Unbind any objects associated with this session  获取当前session 保存的所有attr.key
            String keys[] = keys();
            ClassLoader oldContextClassLoader = null;
            try {
                // TODO 这里记录了 旧的类加载器后 将 attr 移除 之后又恢复了 classLoader
                oldContextClassLoader = context.bind(Globals.IS_SECURITY_ENABLED, null);
                for (int i = 0; i < keys.length; i++) {
                    removeAttributeInternal(keys[i], notify);
                }
            } finally {
                context.unbind(Globals.IS_SECURITY_ENABLED, oldContextClassLoader);
            }
        }

    }


    /**
     * Perform the internal processing required to passivate
     * this session.
     * 使得该session 失活
     */
    public void passivate() {

        // Notify interested session event listeners
        fireSessionEvent(Session.SESSION_PASSIVATED_EVENT, null);

        // Notify ActivationListeners  这个失活的监听器是从 attr 中获取的 而不是从context中获取的！
        HttpSessionEvent event = null;
        String keys[] = keys();
        for (int i = 0; i < keys.length; i++) {
            // 获取对应的 attr 属性
            Object attribute = attributes.get(keys[i]);
            // 处理session失活事件
            if (attribute instanceof HttpSessionActivationListener) {
                if (event == null)
                    event = new HttpSessionEvent(getSession());
                try {
                    ((HttpSessionActivationListener)attribute)
                        .sessionWillPassivate(event);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    manager.getContext().getLogger().error
                        (sm.getString("standardSession.attributeEvent"), t);
                }
            }
        }

    }


    /**
     * Perform internal processing required to activate this
     * session.
     * 某个session 恢复活跃状态  当某个session被使用时会先调用该方法
     */
    public void activate() {

        // Initialize access count
        if (ACTIVITY_CHECK) {
            accessCount = new AtomicInteger();
        }

        // Notify interested session event listeners
        fireSessionEvent(Session.SESSION_ACTIVATED_EVENT, null);

        // Notify ActivationListeners
        HttpSessionEvent event = null;
        String keys[] = keys();
        // 同样从 attr 中获取监听器对象 并触发函数
        for (int i = 0; i < keys.length; i++) {
            Object attribute = attributes.get(keys[i]);
            if (attribute instanceof HttpSessionActivationListener) {
                if (event == null)
                    event = new HttpSessionEvent(getSession());
                try {
                    ((HttpSessionActivationListener)attribute)
                        .sessionDidActivate(event);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    manager.getContext().getLogger().error
                        (sm.getString("standardSession.attributeEvent"), t);
                }
            }
        }

    }


    /**
     * Return the object bound with the specified name to the internal notes
     * for this session, or <code>null</code> if no such binding exists.
     *
     * @param name Name of the note to be returned
     *             获取对应的note 属性
     */
    @Override
    public Object getNote(String name) {
        return notes.get(name);
    }


    /**
     * Return an Iterator containing the String names of all notes bindings
     * that exist for this session.
     * 获取内部 notes 关联的 key
     */
    @Override
    public Iterator<String> getNoteNames() {
        return notes.keySet().iterator();
    }


    /**
     * Release all object references, and initialize instance variables, in
     * preparation for reuse of this object.
     * 当对象被回收时置空相关引用
     */
    @Override
    public void recycle() {

        // Reset the instance variables associated with this Session
        attributes.clear();
        setAuthType(null);
        creationTime = 0L;
        expiring = false;
        id = null;
        lastAccessedTime = 0L;
        maxInactiveInterval = -1;
        notes.clear();
        setPrincipal(null);
        isNew = false;
        isValid = false;
        manager = null;

    }


    /**
     * Remove any object bound to the specified name in the internal notes
     * for this session.
     *
     * @param name Name of the note to be removed
     *             从该对象的 notes 中移除某个 note
     */
    @Override
    public void removeNote(String name) {

        notes.remove(name);

    }


    /**
     * Remove a session event listener from this component.
     * 将某个监听器移除
     */
    @Override
    public void removeSessionListener(SessionListener listener) {

        listeners.remove(listener);

    }


    /**
     * Bind an object to a specified name in the internal notes associated
     * with this session, replacing any existing binding for this name.
     *
     * @param name Name to which the object should be bound
     * @param value Object to be bound to the specified name
     *              将某个note 设置到session 中
     */
    @Override
    public void setNote(String name, Object value) {

        notes.put(name, value);

    }


    /**
     * Return a string representation of this object.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("StandardSession[");
        sb.append(id);
        sb.append("]");
        return sb.toString();
    }


    // ------------------------------------------------ Session Package Methods


    /**
     * Read a serialized version of the contents of this session object from
     * the specified object input stream, without requiring that the
     * StandardSession itself have been serialized.
     *
     * @param stream The object input stream to read from  该输入流是基于 java自带的序列化
     *
     * @exception ClassNotFoundException if an unknown class is specified
     * @exception IOException if an input/output error occurs
     * 从 某个输入流对象中读取数据用来初始化 session 对象
     */
    public void readObjectData(ObjectInputStream stream)
        throws ClassNotFoundException, IOException {

        doReadObject(stream);

    }


    /**
     * Write a serialized version of the contents of this session object to
     * the specified object output stream, without requiring that the
     * StandardSession itself have been serialized.
     *
     * @param stream The object output stream to write to
     *
     * @exception IOException if an input/output error occurs
     * 将session 对象写入到某个对象输出流中
     */
    public void writeObjectData(ObjectOutputStream stream)
        throws IOException {

        doWriteObject(stream);

    }


    // ------------------------------------------------- HttpSession Properties


    /**
     * Return the time when this session was created, in milliseconds since
     * midnight, January 1, 1970 GMT.
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     * 返回该session 对象的创建时间
     */
    @Override
    public long getCreationTime() {
        if (!isValidInternal())
            throw new IllegalStateException
                (sm.getString("standardSession.getCreationTime.ise"));

        return this.creationTime;
    }


    /**
     * Return the time when this session was created, in milliseconds since
     * midnight, January 1, 1970 GMT, bypassing the session validation checks.
     * 默认情况  creationTimeInternal 返回的时间与  creationTime 一致
     */
    @Override
    public long getCreationTimeInternal() {
        return this.creationTime;
    }


    /**
     * Return the ServletContext to which this session belongs.
     * 获取上下文对象
     */
    @Override
    public ServletContext getServletContext() {
        if (manager == null) {
            return null;
        }
        // 通过manager 获取到 servletContext
        Context context = manager.getContext();
        return context.getServletContext();
    }


    /**
     * Return the session context with which this session is associated.
     *
     * @deprecated As of Version 2.1, this method is deprecated and has no
     *  replacement.  It will be removed in a future version of the
     *  Java Servlet API.
     *  该对象已经被废弃 内部的接口实现都是返回 null 或者是无效数据
     */
    @Override
    @Deprecated
    public javax.servlet.http.HttpSessionContext getSessionContext() {
        if (sessionContext == null)
            sessionContext = new StandardSessionContext();
        return sessionContext;
    }


    // ----------------------------------------------HttpSession Public Methods


    /**
     * Return the object bound with the specified name in this session, or
     * <code>null</code> if no object is bound with that name.
     *
     * @param name Name of the attribute to be returned
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *  获取到 session 中某个属性
     */
    @Override
    public Object getAttribute(String name) {
        if (!isValidInternal())
            throw new IllegalStateException
                (sm.getString("standardSession.getAttribute.ise"));

        if (name == null) return null;

        return attributes.get(name);
    }


    /**
     * Return an <code>Enumeration</code> of <code>String</code> objects
     * containing the names of the objects bound to this session.
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *  获取该session 下所有的 attrName
     */
    @Override
    public Enumeration<String> getAttributeNames() {

        if (!isValidInternal())
            throw new IllegalStateException
                (sm.getString("standardSession.getAttributeNames.ise"));

        Set<String> names = new HashSet<>();
        // 采用拷贝的方式 返回一份副本  对外暴露数据的方法 尽可能返回副本对象 这样不会对源数据造成污染
        names.addAll(attributes.keySet());
        return Collections.enumeration(names);
    }


    /**
     * Return the object bound with the specified name in this session, or
     * <code>null</code> if no object is bound with that name.
     *
     * @param name Name of the value to be returned
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *
     * @deprecated As of Version 2.2, this method is replaced by
     *  <code>getAttribute()</code>
     */
    @Override
    @Deprecated
    public Object getValue(String name) {

        return getAttribute(name);

    }


    /**
     * Return the set of names of objects bound to this session.  If there
     * are no such objects, a zero-length array is returned.
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *
     * @deprecated As of Version 2.2, this method is replaced by
     *  <code>getAttributeNames()</code>
     */
    @Override
    @Deprecated
    public String[] getValueNames() {
        if (!isValidInternal())
            throw new IllegalStateException
                (sm.getString("standardSession.getValueNames.ise"));

        return keys();
    }


    /**
     * Invalidates this session and unbinds any objects bound to it.
     *
     * @exception IllegalStateException if this method is called on
     *  an invalidated session
     *  强制该session 无效
     */
    @Override
    public void invalidate() {

        if (!isValidInternal())
            throw new IllegalStateException
                (sm.getString("standardSession.invalidate.ise"));

        // Cause this session to expire
        expire();

    }


    /**
     * Return <code>true</code> if the client does not yet know about the
     * session, or if the client chooses not to join the session.  For
     * example, if the server used only cookie-based sessions, and the client
     * has disabled the use of cookies, then a session would be new on each
     * request.
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *  返回当前 session.isNew  如果 client 禁用cookie 那么每个请求对应的 session 都是新的
     */
    @Override
    public boolean isNew() {
        if (!isValidInternal())
            throw new IllegalStateException
                (sm.getString("standardSession.isNew.ise"));

        return this.isNew;
    }


    /**
     * Bind an object to this session, using the specified name.  If an object
     * of the same name is already bound to this session, the object is
     * replaced.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueBound()</code> on the object.
     *
     * @param name Name to which the object is bound, cannot be null
     * @param value Object to be bound, cannot be null
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *
     * @deprecated As of Version 2.2, this method is replaced by
     *  <code>setAttribute()</code>
     */
    @Override
    @Deprecated
    public void putValue(String name, Object value) {

        setAttribute(name, value);

    }


    /**
     * Remove the object bound with the specified name from this session.  If
     * the session does not have an object bound with this name, this method
     * does nothing.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueUnbound()</code> on the object.
     *
     * @param name Name of the object to remove from this session.
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *  将某个属性从attr 中移除
     */
    @Override
    public void removeAttribute(String name) {

        removeAttribute(name, true);

    }


    /**
     * Remove the object bound with the specified name from this session.  If
     * the session does not have an object bound with this name, this method
     * does nothing.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueUnbound()</code> on the object.
     *
     * @param name Name of the object to remove from this session.
     * @param notify Should we notify interested listeners that this
     *  attribute is being removed?  代表是否要触发监听器
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *  将某个属性从 attr 中移除的时候
     */
    public void removeAttribute(String name, boolean notify) {

        // Validate our current state
        if (!isValidInternal())
            throw new IllegalStateException
                (sm.getString("standardSession.removeAttribute.ise"));

        removeAttributeInternal(name, notify);

    }


    /**
     * Remove the object bound with the specified name from this session.  If
     * the session does not have an object bound with this name, this method
     * does nothing.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueUnbound()</code> on the object.
     *
     * @param name Name of the object to remove from this session.
     *
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *
     * @deprecated As of Version 2.2, this method is replaced by
     *  <code>removeAttribute()</code>
     */
    @Override
    @Deprecated
    public void removeValue(String name) {

        removeAttribute(name);

    }


    /**
     * Bind an object to this session, using the specified name.  If an object
     * of the same name is already bound to this session, the object is
     * replaced.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueBound()</code> on the object.
     *
     * @param name Name to which the object is bound, cannot be null
     * @param value Object to be bound, cannot be null
     *
     * @exception IllegalArgumentException if an attempt is made to add a
     *  non-serializable object in an environment marked distributable.
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     */
    @Override
    public void setAttribute(String name, Object value) {
        setAttribute(name,value,true);
    }
    /**
     * Bind an object to this session, using the specified name.  If an object
     * of the same name is already bound to this session, the object is
     * replaced.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueBound()</code> on the object.
     *
     * @param name Name to which the object is bound, cannot be null
     * @param value Object to be bound, cannot be null
     * @param notify whether to notify session listeners
     * @exception IllegalArgumentException if an attempt is made to add a
     *  non-serializable object in an environment marked distributable.
     * @exception IllegalStateException if this method is called on an
     *  invalidated session
     *  将某个属性设置到 attr中
     */

    public void setAttribute(String name, Object value, boolean notify) {

        // Name cannot be null
        if (name == null)
            throw new IllegalArgumentException
                (sm.getString("standardSession.setAttribute.namenull"));

        // Null value is the same as removeAttribute()  如果 name 对应的value 为nul 就代表是remove 操作
        if (value == null) {
            removeAttribute(name);
            return;
        }

        // Validate our current state
        if (!isValidInternal()) {
            throw new IllegalStateException(sm.getString(
                    "standardSession.setAttribute.ise", getIdInternal()));
        }
        // 如果当前context 支持分布式  而该属性不支持分布式  并且 不是被排除的 (应该就是指在支持分布式的前提下 允许某些属性 仅作为单节点session数据)
        if ((manager != null) && manager.getContext().getDistributable() &&
                // 只要 value 实现了序列化接口 就代表该属性 支持分布式
                !isAttributeDistributable(name, value) && !exclude(name, value)) {
            throw new IllegalArgumentException(sm.getString(
                    "standardSession.setAttribute.iae", name));
        }
        // Construct an event with the new value   生成一个sessionBind 事件
        HttpSessionBindingEvent event = null;

        // Call the valueBound() method if necessary
        // 如果 设置的 value 是  监听器  触发 监听器的 valueBound 方法  attr 中还有可能设置 sessionActivionListener 等监听session 其他状态的监听器
        if (notify && value instanceof HttpSessionBindingListener) {
            // Don't call any notification if replacing with the same value
            Object oldValue = attributes.get(name);
            if (value != oldValue) {
                event = new HttpSessionBindingEvent(getSession(), name, value);
                try {
                    ((HttpSessionBindingListener) value).valueBound(event);
                } catch (Throwable t){
                    manager.getContext().getLogger().error
                    (sm.getString("standardSession.bindingEvent"), t);
                }
            }
        }

        // Replace or add this attribute  将attr 键值对设置到 map中
        Object unbound = attributes.put(name, value);

        // Call the valueUnbound() method if necessary  如果是覆盖操作   而且被覆盖的对象也是 bindingListener  触发 valueBound（event, name） 方法
        if (notify && (unbound != null) && (unbound != value) &&
            (unbound instanceof HttpSessionBindingListener)) {
            try {
                ((HttpSessionBindingListener) unbound).valueUnbound
                    (new HttpSessionBindingEvent(getSession(), name));
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                manager.getContext().getLogger().error
                    (sm.getString("standardSession.bindingEvent"), t);
            }
        }

        // 如果不需要触发 context 级别的监听器 那么在此时可以返回了
        if ( !notify ) return;

        // Notify interested application event listeners
        Context context = manager.getContext();
        Object listeners[] = context.getApplicationEventListeners();
        if (listeners == null)
            return;
        // 寻找监听器中是 HttpSessionAttributeListener 子类的对象
        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof HttpSessionAttributeListener))
                continue;
            HttpSessionAttributeListener listener =
                (HttpSessionAttributeListener) listeners[i];
            try {
                // 这里根据是否有 旧的 数据映射成对应的事件
                if (unbound != null) {
                    context.fireContainerEvent("beforeSessionAttributeReplaced",
                            listener);
                    if (event == null) {
                        event = new HttpSessionBindingEvent
                            (getSession(), name, unbound);
                    }
                    listener.attributeReplaced(event);
                    context.fireContainerEvent("afterSessionAttributeReplaced",
                            listener);
                } else {
                    context.fireContainerEvent("beforeSessionAttributeAdded",
                            listener);
                    if (event == null) {
                        event = new HttpSessionBindingEvent
                            (getSession(), name, value);
                    }
                    listener.attributeAdded(event);
                    context.fireContainerEvent("afterSessionAttributeAdded",
                            listener);
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                try {
                    if (unbound != null) {
                        context.fireContainerEvent(
                                "afterSessionAttributeReplaced", listener);
                    } else {
                        context.fireContainerEvent("afterSessionAttributeAdded",
                                listener);
                    }
                } catch (Exception e) {
                    // Ignore
                }
                manager.getContext().getLogger().error
                    (sm.getString("standardSession.attributeEvent"), t);
            }
        }

    }


    // ------------------------------------------ HttpSession Protected Methods


    /**
     * @return the <code>isValid</code> flag for this session without any expiration
     * check.
     */
    protected boolean isValidInternal() {
        return this.isValid;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation simply checks the value for serializability.
     * Sub-classes might use other distribution technology not based on
     * serialization and can override this check.
     * 判断该attr 是否支持分布式环境 只要实现序列化接口即可
     */
    @Override
    public boolean isAttributeDistributable(String name, Object value) {
        return value instanceof Serializable;
    }


    /**
     * Read a serialized version of this session object from the specified
     * object input stream.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>:  The reference to the owning Manager
     * is not restored by this method, and must be set explicitly.
     *
     * @param stream The input stream to read from
     *
     * @exception ClassNotFoundException if an unknown class is specified
     * @exception IOException if an input/output error occurs
     * 从 输入流中读取数据并进行初始化  看来该方法应该是给 分布式session用的  默认采用java自带序列化方式 然后通过该方法 在另一个节点上反序列化session
     */
    protected void doReadObject(ObjectInputStream stream)
        throws ClassNotFoundException, IOException {

        // Deserialize the scalar instance variables (except Manager)
        // 看来序列化是以当前对象字段的顺序进行的 所以只要按顺序读取属性就能完成反序列化 同时 不需要的字段 使用transient 进行修饰
        authType = null;        // Transient only
        creationTime = ((Long) stream.readObject()).longValue();
        lastAccessedTime = ((Long) stream.readObject()).longValue();
        maxInactiveInterval = ((Integer) stream.readObject()).intValue();
        isNew = ((Boolean) stream.readObject()).booleanValue();
        isValid = ((Boolean) stream.readObject()).booleanValue();
        thisAccessedTime = ((Long) stream.readObject()).longValue();
        principal = null;        // Transient only
        //        setId((String) stream.readObject());
        id = (String) stream.readObject();
        // 这里 manager 没有参与序列化
        if (manager.getContext().getLogger().isDebugEnabled())
            manager.getContext().getLogger().debug
                ("readObject() loading session " + id);

        // Deserialize the attribute count and attribute values
        // 初始化 attr 对应容器
        if (attributes == null)
            attributes = new ConcurrentHashMap<>();
        // 这里应该是写入了 attributes的长度
        int n = ((Integer) stream.readObject()).intValue();
        boolean isValidSave = isValid;
        isValid = true;
        for (int i = 0; i < n; i++) {
            // 挨个读取键值对
            String name = (String) stream.readObject();
            final Object value;
            try {
                value = stream.readObject();
            } catch (WriteAbortedException wae) {
                if (wae.getCause() instanceof NotSerializableException) {
                    String msg = sm.getString("standardSession.notDeserializable", name, id);
                    if (manager.getContext().getLogger().isDebugEnabled()) {
                        manager.getContext().getLogger().debug(msg, wae);
                    } else {
                        manager.getContext().getLogger().warn(msg);
                    }
                    // Skip non serializable attributes
                    continue;
                }
                throw wae;
            }
            if (manager.getContext().getLogger().isDebugEnabled())
                manager.getContext().getLogger().debug("  loading attribute '" + name +
                    "' with value '" + value + "'");
            // Handle the case where the filter configuration was changed while
            // the web application was stopped.
            // 如果是不需要写入的属性 就跳过
            if (exclude(name, value)) {
                continue;
            }
            attributes.put(name, value);
        }
        isValid = isValidSave;

        // listener 和 notes 都没有做序列化 这样 session 只能实现读取 而无法正常触发其他事件啊   因为该理由没有启用 tomcat自带的 分布式session 吗???
        if (listeners == null) {
            listeners = new ArrayList<>();
        }

        if (notes == null) {
            notes = new Hashtable<>();
        }
    }


    /**
     * Write a serialized version of this session object to the specified
     * object output stream.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>:  The owning Manager will not be stored
     * in the serialized representation of this Session.  After calling
     * <code>readObject()</code>, you must set the associated Manager
     * explicitly.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>:  Any attribute that is not Serializable
     * will be unbound from the session, with appropriate actions if it
     * implements HttpSessionBindingListener.  If you do not want any such
     * attributes, be sure the <code>distributable</code> property of the
     * associated Manager is set to <code>true</code>.
     *
     * @param stream The output stream to write to
     *
     * @exception IOException if an input/output error occurs
     * 将session中核心字段写入到 对象输出流中
     */
    protected void doWriteObject(ObjectOutputStream stream) throws IOException {

        // Write the scalar instance variables (except Manager)
        stream.writeObject(Long.valueOf(creationTime));
        stream.writeObject(Long.valueOf(lastAccessedTime));
        stream.writeObject(Integer.valueOf(maxInactiveInterval));
        stream.writeObject(Boolean.valueOf(isNew));
        stream.writeObject(Boolean.valueOf(isValid));
        stream.writeObject(Long.valueOf(thisAccessedTime));
        stream.writeObject(id);
        if (manager.getContext().getLogger().isDebugEnabled())
            manager.getContext().getLogger().debug
                ("writeObject() storing session " + id);

        // Accumulate the names of serializable and non-serializable attributes
        String keys[] = keys();
        ArrayList<String> saveNames = new ArrayList<>();
        ArrayList<Object> saveValues = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            Object value = attributes.get(keys[i]);
            if (value == null) {
                continue;
            } else if (isAttributeDistributable(keys[i], value) && !exclude(keys[i], value)) {
                saveNames.add(keys[i]);
                saveValues.add(value);
            } else {
                // 不支持分布式的属性 会触发移除
                removeAttributeInternal(keys[i], true);
            }
        }

        // Serialize the attribute count and the Serializable attributes
        int n = saveNames.size();
        stream.writeObject(Integer.valueOf(n));
        for (int i = 0; i < n; i++) {
            stream.writeObject(saveNames.get(i));
            try {
                stream.writeObject(saveValues.get(i));
                if (manager.getContext().getLogger().isDebugEnabled())
                    manager.getContext().getLogger().debug(
                            "  storing attribute '" + saveNames.get(i) + "' with value '" + saveValues.get(i) + "'");
            } catch (NotSerializableException e) {
                manager.getContext().getLogger().warn(
                        sm.getString("standardSession.notSerializable", saveNames.get(i), id), e);
            }
        }

    }


    /**
     * Should the given session attribute be excluded? This implementation
     * checks:
     * <ul>
     * <li>{@link Constants#excludedAttributeNames}</li>
     * <li>{@link Manager#willAttributeDistribute(String, Object)}</li>
     * </ul>
     * Note: This method deliberately does not check
     *       {@link #isAttributeDistributable(String, Object)} which is kept
     *       separate to support the checks required in
     *       {@link #setAttribute(String, Object, boolean)}
     *
     * @param name  The attribute name
     * @param value The attribute value
     *
     * @return {@code true} if the attribute should be excluded from
     *         distribution, otherwise {@code false}
     *         该属性是否被排除在外
     */
    protected boolean exclude(String name, Object value) {
        if (Constants.excludedAttributeNames.contains(name)) {
            return true;
        }

        // Manager is required for remaining check
        Manager manager = getManager();
        if (manager == null) {
            // Manager may be null during replication of new sessions in a
            // cluster. Avoid the NPE.
            return false;
        }

        // Last check so use a short-cut  判断 该attr 是否支持分布式session
        return !manager.willAttributeDistribute(name, value);
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Notify all session event listeners that a particular event has
     * occurred for this Session.  The default implementation performs
     * this notification synchronously using the calling thread.
     *
     * @param type Event type
     * @param data Event data
     *             触发相关的session 事件
     */
    public void fireSessionEvent(String type, Object data) {
        // 如果当前没有设置监听器对象 直接返回
        if (listeners.size() < 1)
            return;
        // 将 type 和session 对象封装成 event 并触发监听器
        SessionEvent event = new SessionEvent(this, type, data);
        SessionListener list[] = new SessionListener[0];
        synchronized (listeners) {
            // 这里拷贝了一份副本对象  内部调用了 system.copyArray
            list = listeners.toArray(list);
        }

        for (int i = 0; i < list.length; i++){
            (list[i]).sessionEvent(event);
        }

    }


    /**
     * @return the names of all currently defined session attributes
     * as an array of Strings.  If there are no defined attributes, a
     * zero-length array is returned.
     */
    protected String[] keys() {

        return attributes.keySet().toArray(EMPTY_ARRAY);

    }


    /**
     * Remove the object bound with the specified name from this session.  If
     * the session does not have an object bound with this name, this method
     * does nothing.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>valueUnbound()</code> on the object.
     *
     * @param name Name of the object to remove from this session.
     * @param notify Should we notify interested listeners that this
     *  attribute is being removed?
     *               将attr中某个属性移除  当 将session 往分布式系统中其他节点写入时 如果某属性不支持分布式 那么会被移除  TODO 为什么这样设计
     */
    protected void removeAttributeInternal(String name, boolean notify) {

        // Avoid NPE
        if (name == null) return;

        // Remove this attribute from our collection
        Object value = attributes.remove(name);

        // Do we need to do valueUnbound() and attributeRemoved() notification?
        if (!notify || (value == null)) {
            return;
        }

        // Call the valueUnbound() method if necessary  代表需要触发监听器
        HttpSessionBindingEvent event = null;
        if (value instanceof HttpSessionBindingListener) {
            event = new HttpSessionBindingEvent(getSession(), name, value);
            ((HttpSessionBindingListener) value).valueUnbound(event);
        }

        // Notify interested application event listeners   获取容器内的监听器 并触发
        Context context = manager.getContext();
        Object listeners[] = context.getApplicationEventListeners();
        if (listeners == null)
            return;
        for (int i = 0; i < listeners.length; i++) {
            if (!(listeners[i] instanceof HttpSessionAttributeListener))
                continue;
            HttpSessionAttributeListener listener =
                (HttpSessionAttributeListener) listeners[i];
            try {
                context.fireContainerEvent("beforeSessionAttributeRemoved",
                        listener);
                if (event == null) {
                    event = new HttpSessionBindingEvent
                        (getSession(), name, value);
                }
                listener.attributeRemoved(event);
                context.fireContainerEvent("afterSessionAttributeRemoved",
                        listener);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                try {
                    context.fireContainerEvent("afterSessionAttributeRemoved",
                            listener);
                } catch (Exception e) {
                    // Ignore
                }
                manager.getContext().getLogger().error
                    (sm.getString("standardSession.attributeEvent"), t);
            }
        }

    }


}


// ------------------------------------------------------------ Protected Class


/**
 * This class is a dummy implementation of the <code>HttpSessionContext</code>
 * interface, to conform to the requirement that such an object be returned
 * when <code>HttpSession.getSessionContext()</code> is called.
 *
 * @author Craig R. McClanahan
 *
 * @deprecated As of Java Servlet API 2.1 with no replacement.  The
 *  interface will be removed in a future version of this API.
 */

@Deprecated
final class StandardSessionContext
        implements javax.servlet.http.HttpSessionContext {

    private static final List<String> emptyString = Collections.emptyList();

    /**
     * Return the session identifiers of all sessions defined
     * within this context.
     *
     * @deprecated As of Java Servlet API 2.1 with no replacement.
     *  This method must return an empty <code>Enumeration</code>
     *  and will be removed in a future version of the API.
     */
    @Override
    @Deprecated
    public Enumeration<String> getIds() {
        return Collections.enumeration(emptyString);
    }


    /**
     * Return the <code>HttpSession</code> associated with the
     * specified session identifier.
     *
     * @param id Session identifier for which to look up a session
     *
     * @deprecated As of Java Servlet API 2.1 with no replacement.
     *  This method must return null and will be removed in a
     *  future version of the API.
     */
    @Override
    @Deprecated
    public HttpSession getSession(String id) {
        return null;
    }
}
