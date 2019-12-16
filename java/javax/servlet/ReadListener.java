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
package javax.servlet;

import java.io.IOException;

/**
 * Receives notification of read events when using non-blocking IO.
 *
 * @since Servlet 3.1
 * read 监听器是 servlet3.1 规范中出现的东西
 */
public interface ReadListener extends java.util.EventListener{

    /**
     * Invoked when data is available to read. The container will invoke this
     * method the first time for a request as soon as there is data to read.
     * Subsequent invocations will only occur if a call to
     * {@link ServletInputStream#isReady()} has returned false and data has
     * subsequently become available to read.
     *
     * @throws IOException id an I/O error occurs while processing the event
     * 当数据可以被读取时触发
     */
    public abstract void onDataAvailable() throws IOException;

    /**
     * Invoked when the request body has been fully read.
     *
     * @throws IOException id an I/O error occurs while processing the event
     * 当所有数据体都被读取完成后触发
     */
    public abstract void onAllDataRead() throws IOException;

    /**
     * Invoked if an error occurs while reading the request body.
     *
     * @param throwable The exception that occurred
     *                  当读取过程中出现异常时
     */
    public abstract void onError(java.lang.Throwable throwable);
}
