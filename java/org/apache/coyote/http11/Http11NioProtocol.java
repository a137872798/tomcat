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

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 * tomcat 默认使用的 protocolHandler是 NIO   而不是 NIO2   NIO2 是基于 jdk 异步io 实现的
 * 该对象只是包含了一些 NIO selector 专有的属性 比如 超时时间等
 */
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {

    private static final Log log = LogFactory.getLog(Http11NioProtocol.class);

    /**
     * 通过一个 Nio 端点对象进行初始化
     */
    public Http11NioProtocol() {
        super(new NioEndpoint());
    }


    @Override
    protected Log getLog() { return log; }


    // -------------------- Pool setup --------------------

    public void setPollerThreadCount(int count) {
        ((NioEndpoint)getEndpoint()).setPollerThreadCount(count);
    }

    public int getPollerThreadCount() {
        return ((NioEndpoint)getEndpoint()).getPollerThreadCount();
    }

    public void setSelectorTimeout(long timeout) {
        ((NioEndpoint)getEndpoint()).setSelectorTimeout(timeout);
    }

    public long getSelectorTimeout() {
        return ((NioEndpoint)getEndpoint()).getSelectorTimeout();
    }

    public void setPollerThreadPriority(int threadPriority) {
        ((NioEndpoint)getEndpoint()).setPollerThreadPriority(threadPriority);
    }

    public int getPollerThreadPriority() {
      return ((NioEndpoint)getEndpoint()).getPollerThreadPriority();
    }


    // ----------------------------------------------------- JMX related methods

    @Override
    protected String getNamePrefix() {
        if (isSSLEnabled()) {
            return ("https-" + getSslImplementationShortName()+ "-nio");
        } else {
            return ("http-nio");
        }
    }
}
