/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package javax.servlet.http;

import java.util.Enumeration;

import javax.servlet.ServletContext;

/**
 * Provides a way to identify a user across more than one page request or visit
 * to a Web site and to store information about that user.
 * <p>
 * The servlet container uses this interface to create a session between an HTTP
 * client and an HTTP server. The session persists for a specified time period,
 * across more than one connection or page request from the user. A session
 * usually corresponds to one user, who may visit a site many times. The server
 * can maintain a session in many ways such as using cookies or rewriting URLs.
 * <p>
 * This interface allows servlets to
 * <ul>
 * <li>View and manipulate information about a session, such as the session
 * identifier, creation time, and last accessed time
 * <li>Bind objects to sessions, allowing user information to persist across
 * multiple user connections
 * </ul>
 * <p>
 * When an application stores an object in or removes an object from a session,
 * the session checks whether the object implements
 * {@link HttpSessionBindingListener}. If it does, the servlet notifies the
 * object that it has been bound to or unbound from the session. Notifications
 * are sent after the binding methods complete. For session that are invalidated
 * or expire, notifications are sent after the session has been invalidated or
 * expired.
 * <p>
 * When container migrates a session between VMs in a distributed container
 * setting, all session attributes implementing the
 * {@link HttpSessionActivationListener} interface are notified.
 * <p>
 * A servlet should be able to handle cases in which the client does not choose
 * to join a session, such as when cookies are intentionally turned off. Until
 * the client joins the session, <code>isNew</code> returns <code>true</code>.
 * If the client chooses not to join the session, <code>getSession</code> will
 * return a different session on each request, and <code>isNew</code> will
 * always return <code>true</code>.
 * <p>
 * Session information is scoped only to the current web application (
 * <code>ServletContext</code>), so information stored in one context will not
 * be directly visible in another.
 *
 * @see HttpSessionBindingListener
 * servlet 规范中标准的 session 接口 而 tomcat 内部又使用了一个门面类来装饰它 这样 在内部就做到了解耦 没有让tomcat 直接解除到 httpSession
 * 什么是解耦  就是为了 使得tomcat中能直接访问到 httpSession的 数据尽可能集中到一起 所以使用 tomcat.session 这个门面接口 这样到时候只要修改 tomcat.session 的实现 就可以将改动范围最小化
 */
public interface HttpSession {

    /**
     * Returns the time when this session was created, measured in milliseconds
     * since midnight January 1, 1970 GMT.
     *
     * @return a <code>long</code> specifying when this session was created,
     *         expressed in milliseconds since 1/1/1970 GMT
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     *                获取session 创建时间
     */
    public long getCreationTime();

    /**
     * Returns a string containing the unique identifier assigned to this
     * session. The identifier is assigned by the servlet container and is
     * implementation dependent.
     *
     * @return a string specifying the identifier assigned to this session
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     *                获取该session 对应的id
     */
    public String getId();

    /**
     * Returns the last time the client sent a request associated with this
     * session, as the number of milliseconds since midnight January 1, 1970
     * GMT, and marked by the time the container received the request.
     * <p>
     * Actions that your application takes, such as getting or setting a value
     * associated with the session, do not affect the access time.
     *
     * @return a <code>long</code> representing the last time the client sent a
     *         request associated with this session, expressed in milliseconds
     *         since 1/1/1970 GMT
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     *                获取最后一次访问该session的时间戳
     */
    public long getLastAccessedTime();

    /**
     * Returns the ServletContext to which this session belongs.
     *
     * @return The ServletContext object for the web application
     * @since 2.3
     * 获取该session 绑定的上下文 这里使用的是遵循servlet 规范的 servletContext
     */
    public ServletContext getServletContext();

    /**
     * Specifies the time, in seconds, between client requests before the
     * servlet container will invalidate this session. A zero or negative time
     * indicates that the session should never timeout.
     *
     * @param interval
     *            An integer specifying the number of seconds
     *            设置最大失活时间
     */
    public void setMaxInactiveInterval(int interval);

    /**
     * Returns the maximum time interval, in seconds, that the servlet container
     * will keep this session open between client accesses. After this interval,
     * the servlet container will invalidate the session. The maximum time
     * interval can be set with the <code>setMaxInactiveInterval</code> method.
     * A zero or negative time indicates that the session should never timeout.
     *
     * @return an integer specifying the number of seconds this session remains
     *         open between client requests
     * @see #setMaxInactiveInterval
     * 获取最大失活时间
     */
    public int getMaxInactiveInterval();

    /**
     * Do not use.
     * @return A dummy implementation of HttpSessionContext
     * @deprecated As of Version 2.1, this method is deprecated and has no
     *             replacement. It will be removed in a future version of the
     *             Java Servlet API.
     */
    @SuppressWarnings("dep-ann")
    // Spec API does not use @Deprecated
    public HttpSessionContext getSessionContext();

    /**
     * Returns the object bound with the specified name in this session, or
     * <code>null</code> if no object is bound under the name.
     *
     * @param name
     *            a string specifying the name of the object
     * @return the object with the specified name
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     *                通过name 查询session 中某个属性
     */
    public Object getAttribute(String name);

    /**
     * @param name
     *            a string specifying the name of the object
     * @return the object with the specified name
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #getAttribute}.
     */
    @SuppressWarnings("dep-ann")
    // Spec API does not use @Deprecated
    public Object getValue(String name);

    /**
     * Returns an <code>Enumeration</code> of <code>String</code> objects
     * containing the names of all the objects bound to this session.
     *
     * @return an <code>Enumeration</code> of <code>String</code> objects
     *         specifying the names of all the objects bound to this session
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     *                获取当前session 下所有保存的属性
     */
    public Enumeration<String> getAttributeNames();

    /**
     * @return an array of <code>String</code> objects specifying the names of
     *         all the objects bound to this session
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #getAttributeNames}
     */
    @SuppressWarnings("dep-ann")
    // Spec API does not use @Deprecated
    public String[] getValueNames();

    /**
     * Binds an object to this session, using the name specified. If an object
     * of the same name is already bound to the session, the object is replaced.
     * <p>
     * After this method executes, and if the new object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>HttpSessionBindingListener.valueBound</code>. The container then
     * notifies any <code>HttpSessionAttributeListener</code>s in the web
     * application.
     * <p>
     * If an object was already bound to this session of this name that
     * implements <code>HttpSessionBindingListener</code>, its
     * <code>HttpSessionBindingListener.valueUnbound</code> method is called.
     * <p>
     * If the value passed in is null, this has the same effect as calling
     * <code>removeAttribute()</code>.
     *
     * @param name
     *            the name to which the object is bound; cannot be null
     * @param value
     *            the object to be bound
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     *                将某个键值对保存到 session 中
     */
    public void setAttribute(String name, Object value);

    /**
     * @param name
     *            the name to which the object is bound; cannot be null
     * @param value
     *            the object to be bound; cannot be null
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #setAttribute}
     */
    @SuppressWarnings("dep-ann")
    // Spec API does not use @Deprecated
    public void putValue(String name, Object value);

    /**
     * Removes the object bound with the specified name from this session. If
     * the session does not have an object bound with the specified name, this
     * method does nothing.
     * <p>
     * After this method executes, and if the object implements
     * <code>HttpSessionBindingListener</code>, the container calls
     * <code>HttpSessionBindingListener.valueUnbound</code>. The container then
     * notifies any <code>HttpSessionAttributeListener</code>s in the web
     * application.
     *
     * @param name
     *            the name of the object to remove from this session
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     *                从session 中移除掉某个键值对
     */
    public void removeAttribute(String name);

    /**
     * @param name
     *            the name of the object to remove from this session
     * @exception IllegalStateException
     *                if this method is called on an invalidated session
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #removeAttribute}
     */
    @SuppressWarnings("dep-ann")
    // Spec API does not use @Deprecated
    public void removeValue(String name);

    /**
     * Invalidates this session then unbinds any objects bound to it.
     *
     * @exception IllegalStateException
     *                if this method is called on an already invalidated session
     *                将该session 无效化
     */
    public void invalidate();

    /**
     * Returns <code>true</code> if the client does not yet know about the
     * session or if the client chooses not to join the session. For example, if
     * the server used only cookie-based sessions, and the client had disabled
     * the use of cookies, then a session would be new on each request.
     *
     * @return <code>true</code> if the server has created a session, but the
     *         client has not yet joined
     * @exception IllegalStateException
     *                if this method is called on an already invalidated session
     *                判断该session 是否是新建的
     */
    public boolean isNew();
}
