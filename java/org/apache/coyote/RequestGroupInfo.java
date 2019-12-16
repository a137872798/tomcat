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

import java.util.ArrayList;

/**
 * This can be moved to top level ( eventually with a better name ).
 * It is currently used only as a JMX artifact, to aggregate the data
 * collected from each RequestProcessor thread.
 * 该请求组关联的信息   基本方法就是针对下面所有的 processor 做处理
 */
public class RequestGroupInfo {
    /**
     * 内部维护了一组 reqInfo 对象  而每个info 对象都会不断替换内部的 req 对象 并统计一共处理过的 请求时长 结果 等信息
     */
    private final ArrayList<RequestInfo> processors = new ArrayList<>();
    /**
     * dead 最大时间???   这些dead 是代表已经处理过的req 吗
     */
    private long deadMaxTime = 0;
    private long deadProcessingTime = 0;
    /**
     * 请求量
     */
    private int deadRequestCount = 0;
    private int deadErrorCount = 0;
    private long deadBytesReceived = 0;
    private long deadBytesSent = 0;

    /**
     * 为该group 增加一个 处理器对象
     *
     * @param rp
     */
    public synchronized void addRequestProcessor(RequestInfo rp) {
        processors.add(rp);
    }

    /**
     * 将某个请求处理器 从 group中移除  这时会将数据累加到 dead中
     *
     * @param rp
     */
    public synchronized void removeRequestProcessor(RequestInfo rp) {
        if (rp != null) {
            if (deadMaxTime < rp.getMaxTime())
                deadMaxTime = rp.getMaxTime();
            deadProcessingTime += rp.getProcessingTime();
            deadRequestCount += rp.getRequestCount();
            deadErrorCount += rp.getErrorCount();
            deadBytesReceived += rp.getBytesReceived();
            deadBytesSent += rp.getBytesSent();

            processors.remove(rp);
        }
    }

    /**
     * 比较并获得最大的处理时间
     * @return
     */
    public synchronized long getMaxTime() {
        long maxTime = deadMaxTime;
        for (RequestInfo rp : processors) {
            if (maxTime < rp.getMaxTime()) {
                maxTime = rp.getMaxTime();
            }
        }
        return maxTime;
    }

    // Used to reset the times   针对每个processor 设置最大处理时间
    public synchronized void setMaxTime(long maxTime) {
        deadMaxTime = maxTime;
        for (RequestInfo rp : processors) {
            rp.setMaxTime(maxTime);
        }
    }

    /**
     * 获取 处理时长   是每个 info 对象处理过的所有req 的时长总和
     * @return
     */
    public synchronized long getProcessingTime() {
        long time = deadProcessingTime;
        for (RequestInfo rp : processors) {
            time += rp.getProcessingTime();
        }
        return time;
    }

    /**
     * 设置处理时长  为什么这个字段需要设置啊??? 不应该是看每个 info 的实际处理情况吗
     * @param totalTime
     */
    public synchronized void setProcessingTime(long totalTime) {
        deadProcessingTime = totalTime;
        for (RequestInfo rp : processors) {
            rp.setProcessingTime(totalTime);
        }
    }

    /**
     * 获取请求数量
     * @return
     */
    public synchronized int getRequestCount() {
        int requestCount = deadRequestCount;
        for (RequestInfo rp : processors) {
            requestCount += rp.getRequestCount();
        }
        return requestCount;
    }

    public synchronized void setRequestCount(int requestCount) {
        deadRequestCount = requestCount;
        for (RequestInfo rp : processors) {
            rp.setRequestCount(requestCount);
        }
    }

    public synchronized int getErrorCount() {
        int requestCount = deadErrorCount;
        for (RequestInfo rp : processors) {
            requestCount += rp.getErrorCount();
        }
        return requestCount;
    }

    public synchronized void setErrorCount(int errorCount) {
        deadErrorCount = errorCount;
        for (RequestInfo rp : processors) {
            rp.setErrorCount(errorCount);
        }
    }

    public synchronized long getBytesReceived() {
        long bytes = deadBytesReceived;
        for (RequestInfo rp : processors) {
            bytes += rp.getBytesReceived();
        }
        return bytes;
    }

    public synchronized void setBytesReceived(long bytesReceived) {
        deadBytesReceived = bytesReceived;
        for (RequestInfo rp : processors) {
            rp.setBytesReceived(bytesReceived);
        }
    }

    public synchronized long getBytesSent() {
        long bytes = deadBytesSent;
        for (RequestInfo rp : processors) {
            bytes += rp.getBytesSent();
        }
        return bytes;
    }

    public synchronized void setBytesSent(long bytesSent) {
        deadBytesSent = bytesSent;
        for (RequestInfo rp : processors) {
            rp.setBytesSent(bytesSent);
        }
    }

    public void resetCounters() {
        this.setBytesReceived(0);
        this.setBytesSent(0);
        this.setRequestCount(0);
        this.setProcessingTime(0);
        this.setMaxTime(0);
        this.setErrorCount(0);
    }
}
