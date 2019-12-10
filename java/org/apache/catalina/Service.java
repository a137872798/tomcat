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

import org.apache.catalina.connector.Connector;
import org.apache.catalina.mapper.Mapper;

/**
 * A <strong>Service</strong> is a group of one or more
 * <strong>Connectors</strong> that share a single <strong>Container</strong>
 * to process their incoming requests.  This arrangement allows, for example,
 * a non-SSL and SSL connector to share the same population of web apps.
 * <p>
 * A given JVM can contain any number of Service instances; however, they are
 * completely independent of each other and share only the basic JVM facilities
 * and classes on the system class path.
 *
 * service 属于 server 下的容器 同样实现了 lifecycle 接口
 * @author Craig R. McClanahan
 */
public interface Service extends Lifecycle {

    // ------------------------------------------------------------- Properties

    /**
     * @return the <code>Engine</code> that handles requests for all
     * <code>Connectors</code> associated with this Service.
     * 每个service 对应一个engine  再往下是 host 和 context
     */
    public Engine getContainer();

    /**
     * Set the <code>Engine</code> that handles requests for all
     * <code>Connectors</code> associated with this Service.
     *
     * 为 该service 设置指定的引擎来处理 connector 接收到的请求
     * @param engine The new Engine
     */
    public void setContainer(Engine engine);

    /**
     * 获取本service 对应的名字
     * @return the name of this Service.
     */
    public String getName();

    /**
     * Set the name of this Service.
     *
     * @param name The new service name
     */
    public void setName(String name);

    /**
     * @return the <code>Server</code> with which we are associated (if any).
     * 获取本service 关联的server 对象
     */
    public Server getServer();

    /**
     * Set the <code>Server</code> with which we are associated (if any).
     *
     * @param server The server that owns this Service
     */
    public void setServer(Server server);

    /**
     * @return the parent class loader for this component. If not set, return
     * {@link #getServer()} {@link Server#getParentClassLoader()}. If no server
     * has been set, return the system class loader.
     * 实际上就是 commonClassLoader
     */
    public ClassLoader getParentClassLoader();

    /**
     * Set the parent class loader for this service.
     *
     * @param parent The new parent class loader
     */
    public void setParentClassLoader(ClassLoader parent);

    /**
     * @return the domain under which this container will be / has been
     * registered.
     * 获取对应的域名
     */
    public String getDomain();


    // --------------------------------------------------------- Public Methods

    /**
     * Add a new Connector to the set of defined Connectors, and associate it
     * with this Service's Container.
     * 为该服务设置 connector (该对象负责处理 http请求 并将数据包封装成req 和 res 对象)
     *
     * @param connector The Connector to be added
     */
    public void addConnector(Connector connector);

    /**
     * Find and return the set of Connectors associated with this Service.
     * 获取本service 所有连接器
     * @return the set of associated Connectors
     */
    public Connector[] findConnectors();

    /**
     * Remove the specified Connector from the set associated from this
     * Service.  The removed Connector will also be disassociated from our
     * Container.
     *
     * @param connector The Connector to be removed
     */
    public void removeConnector(Connector connector);

    /**
     * Adds a named executor to the service
     * 设置指定的线程池
     * @param ex Executor
     */
    public void addExecutor(Executor ex);

    /**
     * Retrieves all executors
     * @return Executor[]
     */
    public Executor[] findExecutors();

    /**
     * Retrieves executor by name, null if not found
     * @param name String
     * @return Executor
     */
    public Executor getExecutor(String name);

    /**
     * Removes an executor from the service
     * @param ex Executor
     */
    public void removeExecutor(Executor ex);

    /**
     * @return the mapper associated with this Service.
     */
    Mapper getMapper();
}
