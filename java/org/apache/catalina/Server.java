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

import java.io.File;

import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.startup.Catalina;

/**
 * A <code>Server</code> element represents the entire Catalina
 * servlet container.  Its attributes represent the characteristics of
 * the servlet container as a whole.  A <code>Server</code> may contain
 * one or more <code>Services</code>, and the top level set of naming
 * resources.
 * <p>
 * Normally, an implementation of this interface will also implement
 * <code>Lifecycle</code>, such that when the <code>start()</code> and
 * <code>stop()</code> methods are called, all of the defined
 * <code>Services</code> are also started or stopped.
 * <p>
 * In between, the implementation must open a server socket on the port number
 * specified by the <code>port</code> property.  When a connection is accepted,
 * the first line is read and compared with the specified shutdown command.
 * If the command matches, shutdown of the server is initiated.
 * <p>
 * <strong>NOTE</strong> - The concrete implementation of this class should
 * register the (singleton) instance with the <code>ServerFactory</code>
 * class in its constructor(s).
 *
 * server 对应 catalina 也就是最高级别的容器 内部属性是全局性的 一个server下允许存在多个service
 * @author Craig R. McClanahan
 */
public interface Server extends Lifecycle {

    // ------------------------------------------------------------- Properties

    /**
     * @return the global naming resources.
     * 获取Naming 服务
     */
    public NamingResourcesImpl getGlobalNamingResources();


    /**
     * Set the global naming resources.
     *
     * @param globalNamingResources The new global naming resources
     */
    public void setGlobalNamingResources
        (NamingResourcesImpl globalNamingResources);


    /**
     * @return the global naming resources context.
     * 获取 naming对应的上下文对象
     */
    public javax.naming.Context getGlobalNamingContext();


    /**
     * @return the port number we listen to for shutdown commands.
     * 获取当前server 绑定的端口   tomcat 绑定接口看来是以 server 为级别的
     */
    public int getPort();


    /**
     * Set the port number we listen to for shutdown commands.
     *
     * @param port The new port number
     */
    public void setPort(int port);


    /**
     * @return the address on which we listen to for shutdown commands.
     * 绑定的ip地址
     */
    public String getAddress();


    /**
     * Set the address on which we listen to for shutdown commands.
     *
     * @param address The new address
     */
    public void setAddress(String address);


    /**
     * @return the shutdown command string we are waiting for.
     */
    public String getShutdown();


    /**
     * Set the shutdown command we are waiting for.
     *
     * @param shutdown The new shutdown command
     */
    public void setShutdown(String shutdown);


    /**
     * @return the parent class loader for this component. If not set, return
     * {@link #getCatalina()} {@link Catalina#getParentClassLoader()}. If
     * catalina has not been set, return the system class loader.
     * 获取父类加载器
     */
    public ClassLoader getParentClassLoader();


    /**
     * Set the parent class loader for this server.
     *
     * @param parent The new parent class loader
     */
    public void setParentClassLoader(ClassLoader parent);


    /**
     * @return the outer Catalina startup/shutdown component if present.
     * 获取该server 绑定的 catalina 实例
     */
    public Catalina getCatalina();

    /**
     * Set the outer Catalina startup/shutdown component if present.
     *
     * @param catalina the outer Catalina component
     */
    public void setCatalina(Catalina catalina);


    /**
     * @return the configured base (instance) directory. Note that home and base
     * may be the same (and are by default). If this is not set the value
     * returned by {@link #getCatalinaHome()} will be used.
     * 获取 base路径文件
     */
    public File getCatalinaBase();

    /**
     * Set the configured base (instance) directory. Note that home and base
     * may be the same (and are by default).
     *
     * @param catalinaBase the configured base directory
     */
    public void setCatalinaBase(File catalinaBase);


    /**
     * @return the configured home (binary) directory. Note that home and base
     * may be the same (and are by default).
     * 获取home 路径文件
     */
    public File getCatalinaHome();

    /**
     * Set the configured home (binary) directory. Note that home and base
     * may be the same (and are by default).
     *
     * @param catalinaHome the configured home directory
     */
    public void setCatalinaHome(File catalinaHome);


    // --------------------------------------------------------- Public Methods


    /**
     * Add a new Service to the set of defined Services.
     *
     * @param service The Service to be added
     *                为server 增加一个 service
     */
    public void addService(Service service);


    /**
     * Wait until a proper shutdown command is received, then return.
     * 阻塞接收请求  直到收到 shutdown 命令
     */
    public void await();


    /**
     * Find the specified Service
     * 从server 中查找对应的 service
     * @param name Name of the Service to be returned
     * @return the specified Service, or <code>null</code> if none exists.
     */
    public Service findService(String name);


    /**
     * 查找server下所有的 service
     * @return the set of Services defined within this Server.
     */
    public Service[] findServices();


    /**
     * Remove the specified Service from the set associated from this
     * Server.
     *
     * @param service The Service to be removed
     */
    public void removeService(Service service);


    /**
     * @return the token necessary for operations on the associated JNDI naming
     * context.
     * JNDI(naming服务) 先不看
     */
    public Object getNamingToken();
}
