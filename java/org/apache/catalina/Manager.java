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
package org.apache.catalina;

import java.beans.PropertyChangeListener;
import java.io.IOException;

/**
 * A <b>Manager</b> manages the pool of Sessions that are associated with a
 * particular Context. Different Manager implementations may support
 * value-added features such as the persistent storage of session data,
 * as well as migrating sessions for distributable web applications.
 * <p>
 * In order for a <code>Manager</code> implementation to successfully operate
 * with a <code>Context</code> implementation that implements reloading, it
 * must obey the following constraints:
 * <ul>
 * <li>Must implement <code>Lifecycle</code> so that the Context can indicate
 *     that a restart is required.
 * <li>Must allow a call to <code>stop()</code> to be followed by a call to
 *     <code>start()</code> on the same <code>Manager</code> instance.
 * </ul>
 *
 * @author Craig R. McClanahan
 * 该对象将 session池化 并做统一管理
 */
public interface Manager {

    // ------------------------------------------------------------- Properties

    /**
     * Get the Context with which this Manager is associated.
     *
     * @return The associated Context
     * 获取该管理器绑定的上下文
     */
    public Context getContext();


    /**
     * Set the Context with which this Manager is associated. The Context must
     * be set to a non-null value before the Manager is first used. Multiple
     * calls to this method before first use are permitted. Once the Manager has
     * been used, this method may not be used to change the Context (including
     * setting a {@code null} value) that the Manager is associated with.
     *
     * @param context The newly associated Context
     *                设置该管理器所在的上下文
     */
    public void setContext(Context context);


    /**
     * @return the session id generator
     * 获取 sessionId 生成器
     */
    public SessionIdGenerator getSessionIdGenerator();


    /**
     * Sets the session id generator
     *
     * @param sessionIdGenerator The session id generator
     *                           设置sessionId 生成器
     */
    public void setSessionIdGenerator(SessionIdGenerator sessionIdGenerator);


    /**
     * Returns the total number of sessions created by this manager.
     *
     * @return Total number of sessions created by this manager.
     * 获取 session计数值 应该就是 manager对象管理的session数量
     */
    public long getSessionCounter();


    /**
     * Sets the total number of sessions created by this manager.
     *
     * @param sessionCounter Total number of sessions created by this manager.
     */
    public void setSessionCounter(long sessionCounter);


    /**
     * Gets the maximum number of sessions that have been active at the same
     * time.
     *
     * @return Maximum number of sessions that have been active at the same
     * time
     * 获取当前活跃的session 数量
     */
    public int getMaxActive();


    /**
     * (Re)sets the maximum number of sessions that have been active at the
     * same time.
     *
     * @param maxActive Maximum number of sessions that have been active at
     * the same time.
     *                  设置同一时间最多允许存在的session 数量
     */
    public void setMaxActive(int maxActive);


    /**
     * Gets the number of currently active sessions.
     *
     * @return Number of currently active sessions
     * 获取 当前活跃的session 数量
     */
    public int getActiveSessions();


    /**
     * Gets the number of sessions that have expired.
     *
     * @return Number of sessions that have expired
     * 获取过期的session 数量
     */
    public long getExpiredSessions();


    /**
     * Sets the number of sessions that have expired.
     *
     * @param expiredSessions Number of sessions that have expired
     *                        手动设置 已过期的 session数量  .. 该方法有意义吗
     */
    public void setExpiredSessions(long expiredSessions);


    /**
     * Gets the number of sessions that were not created because the maximum
     * number of active sessions was reached.
     *
     * @return Number of rejected sessions
     * 获取被拒绝的session 数量
     */
    public int getRejectedSessions();


    /**
     * Gets the longest time (in seconds) that an expired session had been
     * alive.
     *
     * @return Longest time (in seconds) that an expired session had been
     * alive.
     * 获取session的最大存活时间
     */
    public int getSessionMaxAliveTime();


    /**
     * Sets the longest time (in seconds) that an expired session had been
     * alive.
     *
     * @param sessionMaxAliveTime Longest time (in seconds) that an expired
     * session had been alive.
     *                            设置session的最大存活时间
     */
    public void setSessionMaxAliveTime(int sessionMaxAliveTime);


    /**
     * Gets the average time (in seconds) that expired sessions had been
     * alive. This may be based on sample data.
     *
     * @return Average time (in seconds) that expired sessions had been
     * alive.
     * 获取session 的平均存活时间
     */
    public int getSessionAverageAliveTime();


    /**
     * Gets the current rate of session creation (in session per minute). This
     * may be based on sample data.
     *
     * @return  The current rate (in sessions per minute) of session creation
     * 获取新建session的比率
     */
    public int getSessionCreateRate();


    /**
     * Gets the current rate of session expiration (in session per minute). This
     * may be based on sample data
     *
     * @return  The current rate (in sessions per minute) of session expiration
     * 获取过期session的比率
     */
    public int getSessionExpireRate();


    // --------------------------------------------------------- Public Methods

    /**
     * Add this Session to the set of active Sessions for this Manager.
     *
     * @param session Session to be added
     *                为manager 添加某个session
     */
    public void add(Session session);


    /**
     * Add a property change listener to this component.
     *
     * @param listener The listener to add
     *                 PropertyChangeListener 属于 java.beans 下的类 先忽略
     */
    public void addPropertyChangeListener(PropertyChangeListener listener);


    /**
     * Change the session ID of the current session to a new randomly generated
     * session ID.
     *
     * @param session   The session to change the session ID for
     *                  变更传入的session#sessionId
     */
    public void changeSessionId(Session session);


    /**
     * Change the session ID of the current session to a specified session ID.
     *
     * @param session   The session to change the session ID for
     * @param newId   new session ID
     *                指定要变更的session 同时还指定了新的 id
     */
    public void changeSessionId(Session session, String newId);


    /**
     * Get a session from the recycled ones or create a new empty one.
     * The PersistentManager manager does not need to create session data
     * because it reads it from the Store.
     *
     * @return An empty Session object
     * 创建一个 空的session 对象
     */
    public Session createEmptySession();


    /**
     * Construct and return a new session object, based on the default
     * settings specified by this Manager's properties.  The session
     * id specified will be used as the session id.
     * If a new session cannot be created for any reason, return
     * <code>null</code>.
     *
     * @param sessionId The session id which should be used to create the
     *  new session; if <code>null</code>, the session
     *  id will be assigned by this method, and available via the getId()
     *  method of the returned session.
     * @exception IllegalStateException if a new session cannot be
     *  instantiated for any reason
     *
     * @return An empty Session object with the given ID or a newly created
     *         session ID if none was specified
     *         根据指定的id 来创建session 对象
     */
    public Session createSession(String sessionId);


    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     *
     * @param id The session id for the session to be returned
     *
     * @exception IllegalStateException if a new session cannot be
     *  instantiated for any reason
     * @exception IOException if an input/output error occurs while
     *  processing this request
     *
     * @return the request session or {@code null} if a session with the
     *         requested ID could not be found
     *         通过 id 查询对应的session 对象
     */
    public Session findSession(String id) throws IOException;


    /**
     * Return the set of active Sessions associated with this Manager.
     * If this Manager has no active Sessions, a zero-length array is returned.
     *
     * @return All the currently active sessions managed by this manager
     * 返回manager 内部所有的 session 对象
     */
    public Session[] findSessions();


    /**
     * Load any currently active sessions that were previously unloaded
     * to the appropriate persistence mechanism, if any.  If persistence is not
     * supported, this method returns without doing anything.
     *
     * @exception ClassNotFoundException if a serialized class cannot be
     *  found during the reload
     * @exception IOException if an input/output error occurs
     * 尝试从某个持久化 对象中读取数据 如果不支持 持久化 那么不做任何操作
     */
    public void load() throws ClassNotFoundException, IOException;


    /**
     * Remove this Session from the active Sessions for this Manager.
     *
     * @param session Session to be removed
     *                将某个session 对象 从manager 中移除
     */
    public void remove(Session session);


    /**
     * Remove this Session from the active Sessions for this Manager.
     *
     * @param session   Session to be removed
     * @param update    Should the expiration statistics be updated  在移除时是否应该同时做更新操作
     *                  移除 某个session
     */
    public void remove(Session session, boolean update);


    /**
     * Remove a property change listener from this component.
     *
     * @param listener The listener to remove
     *                 将 propChangeListener 从 manager 中移除
     */
    public void removePropertyChangeListener(PropertyChangeListener listener);


    /**
     * Save any currently active sessions in the appropriate persistence
     * mechanism, if any.  If persistence is not supported, this method
     * returns without doing anything.
     *
     * @exception IOException if an input/output error occurs
     * 将当前session 数据持久化到某个地方
     */
    public void unload() throws IOException;


    /**
     * This method will be invoked by the context/container on a periodic
     * basis and allows the manager to implement
     * a method that executes periodic tasks, such as expiring sessions etc.
     */
    public void backgroundProcess();


    /**
     * Would the Manager distribute the given session attribute? Manager
     * implementations may provide additional configuration options to control
     * which attributes are distributable.
     *
     * @param name  The attribute name
     * @param value The attribute value
     *
     * @return {@code true} if the Manager would distribute the given attribute
     *         otherwise {@code false}
     *         指定的session 是否支持 分布式环境
     */
    public boolean willAttributeDistribute(String name, Object value);
}
