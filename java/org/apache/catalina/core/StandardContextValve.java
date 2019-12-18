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
package org.apache.catalina.core;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.res.StringManager;

/**
 * Valve that implements the default basic behavior for the
 * <code>StandardContext</code> container implementation.
 * <p>
 * <b>USAGE CONSTRAINT</b>:  This implementation is likely to be useful only
 * when processing HTTP requests.
 *
 * @author Craig R. McClanahan
 * context 级别的标准阀门对象
 */
final class StandardContextValve extends ValveBase {

    private static final StringManager sm = StringManager.getManager(StandardContextValve.class);

    /**
     * context 标准阀门是 支持处理 异步 req 的
     */
    public StandardContextValve() {
        super(true);
    }


    /**
     * Select the appropriate child Wrapper to process this request,
     * based on the specified request URI.  If no matching Wrapper can
     * be found, return an appropriate HTTP error.
     *
     * @param request Request to be processed
     * @param response Response to be produced
     *
     * @exception IOException if an input/output error occurred
     * @exception ServletException if a servlet error occurred
     * 在context 级别 处理 req/res
     */
    @Override
    public final void invoke(Request request, Response response)
        throws IOException, ServletException {

        // Disallow any direct access to resources under WEB-INF or META-INF
        // 如果尝试访问的请求路径 是 WEB-INF 或者 META-INF 下 这是不允许的  直接返回 NOT_FOUND
        MessageBytes requestPathMB = request.getRequestPathMB();
        if ((requestPathMB.startsWithIgnoreCase("/META-INF/", 0))
                || (requestPathMB.equalsIgnoreCase("/META-INF"))
                || (requestPathMB.startsWithIgnoreCase("/WEB-INF/", 0))
                || (requestPathMB.equalsIgnoreCase("/WEB-INF"))) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Select the Wrapper to be used for this Request  获取 wrapper 级别的容器 如果wrapper不存在 返回NOT_FOUND
        Wrapper wrapper = request.getWrapper();
        if (wrapper == null || wrapper.isUnavailable()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Acknowledge the request  应该是代表该req 被正常处理了
        try {
            response.sendAcknowledgement();
        } catch (IOException ioe) {
            container.getLogger().error(sm.getString(
                    "standardContextValve.acknowledgeException"), ioe);
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, ioe);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // 如果wrapper 本身不支持 异步 那么 req 也要设置成相关的状态
        if (request.isAsyncSupported()) {
            request.setAsyncSupported(wrapper.getPipeline().isAsyncSupported());
        }
        // 交由下层的 wrapper 来处理 看来 他们之间的关系是这样 context -> wrapper
        wrapper.getPipeline().getFirst().invoke(request, response);
    }
}
