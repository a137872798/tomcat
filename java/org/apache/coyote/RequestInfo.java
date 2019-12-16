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

import javax.management.ObjectName;


/**
 * Structure holding the Request and Response objects. It also holds statistical
 * informations about request processing and provide management informations
 * about the requests being processed.
 *
 * Each thread uses a Request/Response pair that is recycled on each request.
 * This object provides a place to collect global low-level statistics - without
 * having to deal with synchronization ( since each thread will have it's own
 * RequestProcessorMX ).
 * 请求对象的描述信息   这里大多数方法都是借助访问 req 的 可能req本身是不直接对外暴露的? 所以需要该对象作为门面对象来访问
 * @author Costin Manolache
 */
public class RequestInfo  {
    /**
     * 该信息关联的全局信息
     */
    private RequestGroupInfo global=null;

    // ----------------------------------------------------------- Constructors

    public RequestInfo( Request req) {
        this.req=req;
    }

    public RequestGroupInfo getGlobalProcessor() {
        return global;
    }

    /**
     * 将该info 关联到某个group上
     * @param global
     */
    public void setGlobalProcessor(RequestGroupInfo global) {
        if( global != null) {
            this.global=global;
            // 将自身设置进去
            global.addRequestProcessor( this );
        } else {
            // 当传入的 global 为 null 的时候代表要清理掉 该对象内部包含的 global 字段
            if (this.global != null) {
                this.global.removeRequestProcessor( this );
                this.global = null;
            }
        }
    }


    // ----------------------------------------------------- Instance Variables
    /**
     * 代表该 info 对应的 req 对象
     */
    private final Request req;
    /**
     * 初始化时 stage 为new
     */
    private int stage = Constants.STAGE_NEW;
    /**
     * 工作线程名字
     */
    private String workerThreadName;
    private ObjectName rpName;

    // -------------------- Information about the current request  -----------
    // This is useful for long-running requests only

    /**
     * 获取描述 该req 使用的请求方法 request 对象是怎么生成的???
     * @return
     */
    public String getMethod() {
        return req.method().toString();
    }

    /**
     * 获取请求的 url
     * @return
     */
    public String getCurrentUri() {
        return req.requestURI().toString();
    }

    /**
     * 获取 查询语句 就是  xxx=yyy&xxx2=yyy2  也就是 ? 后面的东西
     * @return
     */
    public String getCurrentQueryString() {
        return req.queryString().toString();
    }

    /**
     * 获取使用的协议
     * @return
     */
    public String getProtocol() {
        return req.protocol().toString();
    }

    /**
     * 获取虚拟主机
     * @return
     */
    public String getVirtualHost() {
        return req.serverName().toString();
    }

    /**
     * 获取服务端口号
     * @return
     */
    public int getServerPort() {
        return req.getServerPort();
    }

    /**
     * 获取远端地址
     * @return
     */
    public String getRemoteAddr() {
        // 这里触发了一个 action 是为什么
        req.action(ActionCode.REQ_HOST_ADDR_ATTRIBUTE, null);
        return req.remoteAddr().toString();
    }

    /**
     * Obtain the remote address for this connection as reported by an
     * intermediate proxy (if any).
     *
     * @return The remote address for the this connection
     */
    public String getRemoteAddrForwarded() {
        // 获取远端地址 如果存在 直接返回 如果不存在先获取
        String remoteAddrProxy = (String) req.getAttribute(Constants.REMOTE_ADDR_ATTRIBUTE);
        if (remoteAddrProxy == null) {
            return getRemoteAddr();
        }
        return remoteAddrProxy;
    }

    public int getContentLength() {
        return req.getContentLength();
    }

    public long getRequestBytesReceived() {
        return req.getBytesRead();
    }

    public long getRequestBytesSent() {
        return req.getResponse().getContentWritten();
    }

    /**
     * 获取该请求对象的处理事件
     * @return
     */
    public long getRequestProcessingTime() {
        // Not perfect, but good enough to avoid returning strange values due to
        // concurrent updates.
        long startTime = req.getStartTime();
        // 如果当前已经处理完毕 则返回 0
        if (getStage() == org.apache.coyote.Constants.STAGE_ENDED || startTime < 0) {
            return 0;
        } else {
            return System.currentTimeMillis() - startTime;
        }
    }

    // -------------------- Statistical data  --------------------
    // Collected at the end of each request.   已发送
    private long bytesSent;
    /**
     * 已接收
     */
    private long bytesReceived;

    // Total time = divide by requestCount to get average. 该req 的处理时间
    private long processingTime;
    // The longest response time for a request   针对一个请求 约定的 最长处理时间 也就是不能小于这个值
    private long maxTime;
    // URI of the request that took maxTime    请求url 的最长值
    private String maxRequestUri;

    /**
     * 请求数量???
     */
    private int requestCount;
    // number of response codes >= 400  本次请求错误码
    private int errorCount;

    //the time of the last request   最后一次处理某个req 的时间 看来一个info 内部的req 会不断的更换
    private long lastRequestProcessingTime = 0;


    /** Called by the processor before recycling the request. It'll collect
     * statistic information.
     * 当循环处理某个新的req 之前要做一些数据累加工作
     */
    void updateCounters() {
        // 累加当前一共收到的请求数据量
        bytesReceived+=req.getBytesRead();
        // 累加当前已经返回的res数据量
        bytesSent+=req.getResponse().getContentWritten();

        // 增加接收的请求量
        requestCount++;
        // 如果上一个请求对象的响应结果超过了 400 代表出现了异常  (这里也包含了500及以上)
        if( req.getResponse().getStatus() >=400 )
            errorCount++;
        // 获取处理上个请求的开始时间
        long t0=req.getStartTime();
        long t1=System.currentTimeMillis();
        long time=t1-t0;
        // 增加处理时间  以及设置上个请求的处理时间
        this.lastRequestProcessingTime = time;
        processingTime+=time;
        // 这里尝试更新 处理某个请求的最大时间
        if( maxTime < time ) {
            maxTime=time;
            // 同时记录处理的req 对应的url 属性
            maxRequestUri=req.requestURI().toString();
        }
    }

    public int getStage() {
        return stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public void setBytesSent(long bytesSent) {
        this.bytesSent = bytesSent;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public void setBytesReceived(long bytesReceived) {
        this.bytesReceived = bytesReceived;
    }

    public long getProcessingTime() {
        return processingTime;
    }

    public void setProcessingTime(long processingTime) {
        this.processingTime = processingTime;
    }

    public long getMaxTime() {
        return maxTime;
    }

    public void setMaxTime(long maxTime) {
        this.maxTime = maxTime;
    }

    public String getMaxRequestUri() {
        return maxRequestUri;
    }

    public void setMaxRequestUri(String maxRequestUri) {
        this.maxRequestUri = maxRequestUri;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(int requestCount) {
        this.requestCount = requestCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public String getWorkerThreadName() {
        return workerThreadName;
    }

    public ObjectName getRpName() {
        return rpName;
    }

    public long getLastRequestProcessingTime() {
        return lastRequestProcessingTime;
    }

    public void setWorkerThreadName(String workerThreadName) {
        this.workerThreadName = workerThreadName;
    }

    public void setRpName(ObjectName rpName) {
        this.rpName = rpName;
    }

    public void setLastRequestProcessingTime(long lastRequestProcessingTime) {
        this.lastRequestProcessingTime = lastRequestProcessingTime;
    }
}
