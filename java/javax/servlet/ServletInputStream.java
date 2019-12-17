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
import java.io.InputStream;

/**
 * Provides an input stream for reading binary data from a client request,
 * including an efficient <code>readLine</code> method for reading data one line
 * at a time. With some protocols, such as HTTP POST and PUT, a
 * <code>ServletInputStream</code> object can be used to read data sent from the
 * client.
 * <p>
 * A <code>ServletInputStream</code> object is normally retrieved via the
 * {@link ServletRequest#getInputStream} method.
 * <p>
 * This is an abstract class that a servlet container implements. Subclasses of
 * this class must implement the <code>java.io.InputStream.read()</code> method.
 *
 * @see ServletRequest
 * servlet 规范中 servletRequest 会携带一个 getInputStream() 方法 该方法可以返回一个 serveltInputStream
 */
public abstract class ServletInputStream extends InputStream {

    /**
     * Does nothing, because this is an abstract class.
     */
    protected ServletInputStream() {
        // NOOP
    }

    /**
     * Reads the input stream, one line at a time. Starting at an offset, reads
     * bytes into an array, until it reads a certain number of bytes or reaches
     * a newline character, which it reads into the array as well.
     * <p>
     * This method returns -1 if it reaches the end of the input stream before
     * reading the maximum number of bytes.
     *
     * @param b
     *            an array of bytes into which data is read
     * @param off
     *            an integer specifying the character at which this method
     *            begins reading
     * @param len
     *            an integer specifying the maximum number of bytes to read
     * @return an integer specifying the actual number of bytes read, or -1 if
     *         the end of the stream is reached
     * @exception IOException
     *                if an input or output exception has occurred
     *                从输入流中读取一行数据 并写入到 byte[] 中
     */
    public int readLine(byte[] b, int off, int len) throws IOException {

        // 如果读取长度本身为0 本次不做任何处理
        if (len <= 0) {
            return 0;
        }
        int count = 0, c;

        // read()由子类实现
        while ((c = read()) != -1) {
            b[off++] = (byte) c;
            count++;
            if (c == '\n' || count == len) {
                break;
            }
        }
        return count > 0 ? count : -1;
    }

    /**
     * Has the end of this InputStream been reached?
     *
     * @return <code>true</code> if all the data has been read from the stream,
     * else <code>false</code>
     *
     * @since Servlet 3.1
     * 判断该输入流的数据是否全部被读取
     */
    public abstract boolean isFinished();

    /**
     * Can data be read from this InputStream without blocking?
     * Returns  If this method is called and returns false, the container will
     * invoke {@link ReadListener#onDataAvailable()} when data is available.
     *
     * @return <code>true</code> if data can be read without blocking, else
     * <code>false</code>
     *
     * @since Servlet 3.1
     * 是否已经准备好读取本数据 这样就不需要阻塞了
     */
    public abstract boolean isReady();

    /**
     * Sets the {@link ReadListener} for this {@link ServletInputStream} and
     * thereby switches to non-blocking IO. It is only valid to switch to
     * non-blocking IO within async processing or HTTP upgrade processing.
     *
     * @param listener  The non-blocking IO read listener
     *
     * @throws IllegalStateException    If this method is called if neither
     *                                  async nor HTTP upgrade is in progress or
     *                                  if the {@link ReadListener} has already
     *                                  been set
     * @throws NullPointerException     If listener is null
     *
     * @since Servlet 3.1
     * 设置读监听器
     */
    public abstract void setReadListener(ReadListener listener);
}
