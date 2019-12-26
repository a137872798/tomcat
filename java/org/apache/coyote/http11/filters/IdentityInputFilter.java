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

package org.apache.coyote.http11.filters;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.coyote.http11.InputFilter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.res.StringManager;

/**
 * Identity input filter.
 *
 * @author Remy Maucherat
 * 当请求头中携带 content-length 会添加该过滤器
 * 相当于 携带 该请求头的数据流 可以判断是否读取到末尾
 */
public class IdentityInputFilter implements InputFilter, ApplicationBufferHandler {

    private static final StringManager sm = StringManager.getManager(
            IdentityInputFilter.class.getPackage().getName());


    // -------------------------------------------------------------- Constants


    protected static final String ENCODING_NAME = "identity";
    protected static final ByteChunk ENCODING = new ByteChunk();


    // ----------------------------------------------------- Static Initializer


    static {
        // 指定编码模式为 ISO-8859-1
        ENCODING.setBytes(ENCODING_NAME.getBytes(StandardCharsets.ISO_8859_1),
                0, ENCODING_NAME.length());
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * Content length.
     */
    protected long contentLength = -1;


    /**
     * Remaining bytes.
     * 判断是否还有剩余数据未读取
     */
    protected long remaining = 0;


    /**
     * Next buffer in the pipeline.
     */
    protected InputBuffer buffer;


    /**
     * ByteBuffer used to read leftover bytes.
     */
    protected ByteBuffer tempRead;


    private final int maxSwallowSize;


    public IdentityInputFilter(int maxSwallowSize) {
        this.maxSwallowSize = maxSwallowSize;
    }


    // ---------------------------------------------------- InputBuffer Methods

    /**
     * @deprecated Unused. Will be removed in Tomcat 9. Use
     *             {@link #doRead(ApplicationBufferHandler)}
     */
    @Deprecated
    @Override
    public int doRead(ByteChunk chunk) throws IOException {

        int result = -1;

        if (contentLength >= 0) {
            if (remaining > 0) {
                int nRead = buffer.doRead(chunk);
                if (nRead > remaining) {
                    // The chunk is longer than the number of bytes remaining
                    // in the body; changing the chunk length to the number
                    // of bytes remaining
                    chunk.setBytes(chunk.getBytes(), chunk.getStart(),
                                   (int) remaining);
                    result = (int) remaining;
                } else {
                    result = nRead;
                }
                if (nRead > 0) {
                    remaining = remaining - nRead;
                }
            } else {
                // No more bytes left to be read : return -1 and clear the
                // buffer
                chunk.recycle();
                result = -1;
            }
        }

        return result;

    }

    @Override
    public int doRead(ApplicationBufferHandler handler) throws IOException {

        int result = -1;

        if (contentLength >= 0) {
            // 代表还有部分数据未读取
            if (remaining > 0) {
                /**
                 * 相当于通过装饰器模式 构建一个调用链 实际上 handler 就是 过滤链的尾节点 从后往前不断调用
                 * 最终到达 SocketInputBuffer 与 读取socket数据的buffer 交互
                 */
                int nRead = buffer.doRead(handler);
                if (nRead > remaining) {
                    // The chunk is longer than the number of bytes remaining
                    // in the body; changing the chunk length to the number
                    // of bytes remaining
                    // 如果读取的长度超过了 remaining 就强制返回这个大小
                    handler.getByteBuffer().limit(handler.getByteBuffer().position() + (int) remaining);
                    result = (int) remaining;
                } else {
                    // 如果数据还不足 那么直接返回即可
                    result = nRead;
                }
                // 更新 remaining 的值
                if (nRead > 0) {
                    remaining = remaining - nRead;
                }
            } else {
                // No more bytes left to be read : return -1 and clear the
                // buffer
                if (handler.getByteBuffer() != null) {
                    handler.getByteBuffer().position(0).limit(0);
                }
                result = -1;
            }
        }

        return result;

    }


    // ---------------------------------------------------- InputFilter Methods


    /**
     * Read the content length from the request.
     * 从req中获取相关信息来填充该过滤器
     */
    @Override
    public void setRequest(Request request) {
        contentLength = request.getContentLengthLong();
        remaining = contentLength;
    }


    /**
     * 判断是否读取到末尾了
     * @return
     * @throws IOException
     */
    @Override
    public long end() throws IOException {

        // 判断读取的数据长度是否超过了一个请求头的大小
        final boolean maxSwallowSizeExceeded = (maxSwallowSize > -1 && remaining > maxSwallowSize);
        long swallowed = 0;

        // Consume extra bytes.
        while (remaining > 0) {

            // 将数据拷贝到buffer 中  将数据往下传播
            int nread = buffer.doRead(this);
            tempRead = null;
            // 代表底层 socket 还有未读取完的数据
            if (nread > 0 ) {
                swallowed += nread;
                remaining = remaining - nread;
                // 读取的值超过了 一次吞吐的量
                if (maxSwallowSizeExceeded && swallowed > maxSwallowSize) {
                    // Note: We do not fail early so the client has a chance to
                    // read the response before the connection is closed. See:
                    // https://httpd.apache.org/docs/2.0/misc/fin_wait_2.html#appendix
                    throw new IOException(sm.getString("inputFilter.maxSwallow"));
                }
            } else { // errors are handled higher up.
                // 否则将 remaining 标记为 0 ???
                remaining = 0;
            }
        }

        // If too many bytes were read, return the amount.
        return -remaining;

    }


    /**
     * Amount of bytes still available in a buffer.
     */
    @Override
    public int available() {
        return 0;
    }


    /**
     * Set the next buffer in the filter pipeline.
     */
    @Override
    public void setBuffer(InputBuffer buffer) {
        this.buffer = buffer;
    }


    /**
     * Make the filter ready to process the next request.
     */
    @Override
    public void recycle() {
        contentLength = -1;
        remaining = 0;
    }


    /**
     * Return the name of the associated encoding; Here, the value is
     * "identity".
     */
    @Override
    public ByteChunk getEncodingName() {
        return ENCODING;
    }


    @Override
    public boolean isFinished() {
        // Only finished if a content length is defined and there is no data
        // remaining
        return contentLength > -1 && remaining <= 0;
    }


    @Override
    public void setByteBuffer(ByteBuffer buffer) {
        tempRead = buffer;
    }


    @Override
    public ByteBuffer getByteBuffer() {
        return tempRead;
    }


    @Override
    public void expand(int size) {
        // no-op
    }
}
