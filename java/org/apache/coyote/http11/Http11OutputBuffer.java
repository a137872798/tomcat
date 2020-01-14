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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.ActionCode;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.HttpMessages;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * Provides buffering for the HTTP headers (allowing responses to be reset
 * before they have been committed) and the link to the Socket for writing the
 * headers (once committed) and the response body. Note that buffering of the
 * response body happens at a higher level.
 * 该对象用于将 http响应头 写入 到socket中 那么body 由谁来写入呢
 */
public class Http11OutputBuffer implements HttpOutputBuffer {

    // -------------------------------------------------------------- Variables

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(Http11OutputBuffer.class);


    // ----------------------------------------------------- Instance Variables

    /**
     * Associated Coyote response.
     * 内部关联一个 coyoteResponse  也就是在 tomcat内部 数据都是转换成 coyote 的
     * 而到了与 mvc 框架交互的时候 做了适配 变成了 HttpServletRequest HttpServletResponse
     */
    protected Response response;


    /**
     * Finished flag.
     * 代表该 res 是否已经处理完成
     */
    protected boolean responseFinished;


    /**
     * The buffer used for header composition.
     * 用于装载响应头的buffer
     */
    protected final ByteBuffer headerBuffer;


    /**
     * Filter library for processing the response body.
     * 一组输出流过滤器   设计上和 InputFilter 一致
     */
    protected OutputFilter[] filterLibrary;


    /**
     * Active filters for the current request.
     */
    protected OutputFilter[] activeFilters;


    /**
     * Index of the last active filter.
     */
    protected int lastActiveFilter;


    /**
     * Underlying output buffer.
     * 内部用于输出数据的buffer  为什么在tomcat 中有这么多的 数据拷贝呢
     */
    protected HttpOutputBuffer outputStreamOutputBuffer;


    /**
     * Wrapper for socket where data will be written to.
     * 将数据往该socket包装对象中输出
     */
    protected SocketWrapperBase<?> socketWrapper;


    /**
     * Bytes written to client for the current request
     * 记录当前已经写回到client 的byte 数量
     */
    protected long byteCount = 0;


    @Deprecated
    private boolean sendReasonPhrase = false;


    /**
     * 通过指定的长度 和 待写入的res 来初始化
     * @param response
     * @param headerBufferSize
     * @param sendReasonPhrase
     */
    protected Http11OutputBuffer(Response response, int headerBufferSize, boolean sendReasonPhrase) {

        this.response = response;
        this.sendReasonPhrase = sendReasonPhrase;

        headerBuffer = ByteBuffer.allocate(headerBufferSize);

        filterLibrary = new OutputFilter[0];
        activeFilters = new OutputFilter[0];
        lastActiveFilter = -1;

        responseFinished = false;

        outputStreamOutputBuffer = new SocketOutputBuffer();

        if (sendReasonPhrase) {
            // Cause loading of HttpMessages
            HttpMessages.getInstance(response.getLocale()).getMessage(200);
        }
    }


    // ------------------------------------------------------------- Properties

    /**
     * Add an output filter to the filter library. Note that calling this method
     * resets the currently active filters to none.
     *
     * @param filter The filter to add
     *               添加某个过滤器
     */
    public void addFilter(OutputFilter filter) {

        OutputFilter[] newFilterLibrary = new OutputFilter[filterLibrary.length + 1];
        for (int i = 0; i < filterLibrary.length; i++) {
            newFilterLibrary[i] = filterLibrary[i];
        }
        newFilterLibrary[filterLibrary.length] = filter;
        filterLibrary = newFilterLibrary;

        activeFilters = new OutputFilter[filterLibrary.length];
    }


    /**
     * Get filters.
     *
     * @return The current filter library containing all possible filters
     */
    public OutputFilter[] getFilters() {
        return filterLibrary;
    }


    /**
     * Add an output filter to the active filters for the current response.
     * <p>
     * The filter does not have to be present in {@link #getFilters()}.
     * <p>
     * A filter can only be added to a response once. If the filter has already
     * been added to this response then this method will be a NO-OP.
     *
     * @param filter The filter to add
     */
    public void addActiveFilter(OutputFilter filter) {

        if (lastActiveFilter == -1) {
            filter.setBuffer(outputStreamOutputBuffer);
        } else {
            for (int i = 0; i <= lastActiveFilter; i++) {
                if (activeFilters[i] == filter)
                    return;
            }
            filter.setBuffer(activeFilters[lastActiveFilter]);
        }

        activeFilters[++lastActiveFilter] = filter;

        filter.setResponse(response);
    }


    // --------------------------------------------------- OutputBuffer Methods

    /**
     * @deprecated Unused. Will be removed in Tomcat 9. Use
     *             {@link #doWrite(ByteBuffer)}
     */
    @Deprecated
    @Override
    public int doWrite(ByteChunk chunk) throws IOException {

        if (!response.isCommitted()) {
            // Send the connector a request for commit. The connector should
            // then validate the headers, send them (using sendHeaders) and
            // set the filters accordingly.
            response.action(ActionCode.COMMIT, null);
        }

        if (lastActiveFilter == -1) {
            return outputStreamOutputBuffer.doWrite(chunk);
        } else {
            return activeFilters[lastActiveFilter].doWrite(chunk);
        }
    }


    /**
     * 将数据写入到 ByteBuffer 中
     * @param chunk data to write
     *
     * @return
     * @throws IOException
     */
    @Override
    public int doWrite(ByteBuffer chunk) throws IOException {

        // 触发commit 事件 同时这里还没有真正提交数据
        if (!response.isCommitted()) {
            // Send the connector a request for commit. The connector should
            // then validate the headers, send them (using sendHeaders) and
            // set the filters accordingly.
            response.action(ActionCode.COMMIT, null);
        }

        // 通过 buffer 做真正的写入
        if (lastActiveFilter == -1) {
            return outputStreamOutputBuffer.doWrite(chunk);
        } else {
            return activeFilters[lastActiveFilter].doWrite(chunk);
        }
    }


    /**
     * 获取当前已经写入的长度
     * @return
     */
    @Override
    public long getBytesWritten() {
        if (lastActiveFilter == -1) {
            return outputStreamOutputBuffer.getBytesWritten();
        } else {
            return activeFilters[lastActiveFilter].getBytesWritten();
        }
    }


    // ----------------------------------------------- HttpOutputBuffer Methods

    /**
     * Flush the response.
     *
     * @throws IOException an underlying I/O error occurred
     * 将数据 flush
     */
    @Override
    public void flush() throws IOException {
        if (lastActiveFilter == -1) {
            outputStreamOutputBuffer.flush();
        } else {
            activeFilters[lastActiveFilter].flush();
        }
    }


    /**
     * 触发一系列的 过滤器 最终到达 socketOutputBuffer 将数据写到网络层
     * @throws IOException
     */
    @Override
    public void end() throws IOException {
        if (responseFinished) {
            return;
        }

        if (lastActiveFilter == -1) {
            outputStreamOutputBuffer.end();
        } else {
            activeFilters[lastActiveFilter].end();
        }

        responseFinished = true;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Reset the header buffer if an error occurs during the writing of the
     * headers so the error response can be written.
     */
    void resetHeaderBuffer() {
        headerBuffer.position(0).limit(headerBuffer.capacity());
    }


    /**
     * Recycle the output buffer. This should be called when closing the
     * connection.
     */
    public void recycle() {
        nextRequest();
        socketWrapper = null;
    }


    /**
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     * 开始处理下一个请求 也就是对当前对象做一些清理工作 便于复用
     */
    public void nextRequest() {
        // Recycle filters
        for (int i = 0; i <= lastActiveFilter; i++) {
            activeFilters[i].recycle();
        }
        // Recycle response object
        response.recycle();
        // Reset pointers
        headerBuffer.position(0).limit(headerBuffer.capacity());
        lastActiveFilter = -1;
        responseFinished = false;
        byteCount = 0;
    }


    /**
     * 通过 wrapper 对该对象进行初始化  当写入数据时 就是往该socket写入
     * @param socketWrapper
     */
    public void init(SocketWrapperBase<?> socketWrapper) {
        this.socketWrapper = socketWrapper;
    }


    /**
     * 发送ack 消息
     * @throws IOException
     */
    @SuppressWarnings("deprecation")
    public void sendAck() throws IOException {
        // 必须确保当前还没有提交
        if (!response.isCommitted()) {
            if (sendReasonPhrase) {
                socketWrapper.write(isBlocking(), Constants.ACK_BYTES_REASON, 0, Constants.ACK_BYTES_REASON.length);
            } else {
                socketWrapper.write(isBlocking(), Constants.ACK_BYTES, 0, Constants.ACK_BYTES.length);
            }
            // 对内部数据进行 flush  在阻塞模式下是不会返回true的 这里只是做了保险
            if (flushBuffer(true)) {
                throw new IOException(sm.getString("iob.failedwrite.ack"));
            }
        }
    }


    /**
     * Commit the response.
     *
     * @throws IOException an underlying I/O error occurred
     */
    protected void commit() throws IOException {
        response.setCommitted(true);

        if (headerBuffer.position() > 0) {
            // Sending the response header buffer
            headerBuffer.flip();
            try {
                socketWrapper.write(isBlocking(), headerBuffer);
            } finally {
                headerBuffer.position(0).limit(headerBuffer.capacity());
            }
        }
    }


    /**
     * Send the response status line.
     * 将状态信息写入到响应头中
     */
    @SuppressWarnings("deprecation")
    public void sendStatus() {
        // Write protocol name
        write(Constants.HTTP_11_BYTES);
        headerBuffer.put(Constants.SP);

        // Write status code
        int status = response.getStatus();
        switch (status) {
        case 200:
            write(Constants._200_BYTES);
            break;
        case 400:
            write(Constants._400_BYTES);
            break;
        case 404:
            write(Constants._404_BYTES);
            break;
        default:
            write(status);
        }

        headerBuffer.put(Constants.SP);

        if (sendReasonPhrase) {
            // Write message
            String message = null;
            if (org.apache.coyote.Constants.USE_CUSTOM_STATUS_MSG_IN_HEADER &&
                    HttpMessages.isSafeInHttpHeader(response.getMessage())) {
                message = response.getMessage();
            }
            if (message == null) {
                write(HttpMessages.getInstance(
                        response.getLocale()).getMessage(status));
            } else {
                write(message);
            }
        } else {
            // The reason phrase is optional but the space before it is not. Skip
            // sending the reason phrase. Clients should ignore it (RFC 7230) and it
            // just wastes bytes.
        }

        headerBuffer.put(Constants.CR).put(Constants.LF);
    }


    /**
     * Send a header.
     *
     * @param name Header name
     * @param value Header value
     */
    public void sendHeader(MessageBytes name, MessageBytes value) {
        write(name);
        headerBuffer.put(Constants.COLON).put(Constants.SP);
        write(value);
        headerBuffer.put(Constants.CR).put(Constants.LF);
    }


    /**
     * End the header block.
     */
    public void endHeaders() {
        headerBuffer.put(Constants.CR).put(Constants.LF);
    }


    /**
     * This method will write the contents of the specified message bytes
     * buffer to the output stream, without filtering. This method is meant to
     * be used to write the response header.
     *
     * @param mb data to be written
     */
    private void write(MessageBytes mb) {
        if (mb.getType() != MessageBytes.T_BYTES) {
            mb.toBytes();
            ByteChunk bc = mb.getByteChunk();
            // Need to filter out CTLs excluding TAB. ISO-8859-1 and UTF-8
            // values will be OK. Strings using other encodings may be
            // corrupted.
            byte[] buffer = bc.getBuffer();
            for (int i = bc.getOffset(); i < bc.getLength(); i++) {
                // byte values are signed i.e. -128 to 127
                // The values are used unsigned. 0 to 31 are CTLs so they are
                // filtered (apart from TAB which is 9). 127 is a control (DEL).
                // The values 128 to 255 are all OK. Converting those to signed
                // gives -128 to -1.
                if ((buffer[i] > -1 && buffer[i] <= 31 && buffer[i] != 9) ||
                        buffer[i] == 127) {
                    buffer[i] = ' ';
                }
            }
        }
        write(mb.getByteChunk());
    }


    /**
     * This method will write the contents of the specified byte chunk to the
     * output stream, without filtering. This method is meant to be used to
     * write the response header.
     *
     * @param bc data to be written
     *           实际上内部的写操作 都是将数据追加到 headerBuffer 中
     */
    private void write(ByteChunk bc) {
        // Writing the byte chunk to the output buffer
        int length = bc.getLength();
        checkLengthBeforeWrite(length);
        headerBuffer.put(bc.getBytes(), bc.getStart(), length);
    }


    /**
     * This method will write the contents of the specified byte
     * buffer to the output stream, without filtering. This method is meant to
     * be used to write the response header.
     *
     * @param b data to be written
     */
    public void write(byte[] b) {
        checkLengthBeforeWrite(b.length);

        // Writing the byte chunk to the output buffer
        headerBuffer.put(b);
    }


    /**
     * This method will write the contents of the specified String to the
     * output stream, without filtering. This method is meant to be used to
     * write the response header.
     *
     * @param s data to be written
     */
    private void write(String s) {
        if (s == null) {
            return;
        }

        // From the Tomcat 3.3 HTTP/1.0 connector
        int len = s.length();
        checkLengthBeforeWrite(len);
        for (int i = 0; i < len; i++) {
            char c = s.charAt (i);
            // Note: This is clearly incorrect for many strings,
            // but is the only consistent approach within the current
            // servlet framework. It must suffice until servlet output
            // streams properly encode their output.
            if (((c <= 31) && (c != 9)) || c == 127 || c > 255) {
                c = ' ';
            }
            headerBuffer.put((byte) c);
        }
    }


    /**
     * This method will write the specified integer to the output stream. This
     * method is meant to be used to write the response header.
     *
     * @param value data to be written
     */
    private void write(int value) {
        // From the Tomcat 3.3 HTTP/1.0 connector
        String s = Integer.toString(value);
        int len = s.length();
        checkLengthBeforeWrite(len);
        for (int i = 0; i < len; i++) {
            char c = s.charAt (i);
            headerBuffer.put((byte) c);
        }
    }


    /**
     * Checks to see if there is enough space in the buffer to write the
     * requested number of bytes.
     */
    private void checkLengthBeforeWrite(int length) {
        // "+ 4": BZ 57509. Reserve space for CR/LF/COLON/SP characters that
        // are put directly into the buffer following this write operation.
        if (headerBuffer.position() + length + 4 > headerBuffer.capacity()) {
            throw new HeadersTooLargeException(
                    sm.getString("iob.responseheadertoolarge.error"));
        }
    }


    //------------------------------------------------------ Non-blocking writes

    /**
     * Writes any remaining buffered data.
     *
     * @param block     Should this method block until the buffer is empty
     * @return  <code>true</code> if data remains in the buffer (which can only
     *          happen in non-blocking mode) else <code>false</code>.
     * @throws IOException Error writing data
     */
    protected boolean flushBuffer(boolean block) throws IOException  {
        return socketWrapper.flush(block);
    }


    /**
     * Is standard Servlet blocking IO being used for output?
     * @return <code>true</code> if this is blocking IO
     */
    protected final boolean isBlocking() {
        return response.getWriteListener() == null;
    }


    protected final boolean isReady() {
        boolean result = !hasDataToWrite();
        if (!result) {
            socketWrapper.registerWriteInterest();
        }
        return result;
    }


    public boolean hasDataToWrite() {
        return socketWrapper.hasDataToWrite();
    }


    public void registerWriteInterest() {
        socketWrapper.registerWriteInterest();
    }


    // ------------------------------------------ SocketOutputBuffer Inner Class

    /**
     * This class is an output buffer which will write data to a socket.
     */
    protected class SocketOutputBuffer implements HttpOutputBuffer {

        /**
         * Write chunk.
         *
         * @deprecated Unused. Will be removed in Tomcat 9. Use
         *             {@link #doWrite(ByteBuffer)}
         */
        @Deprecated
        @Override
        public int doWrite(ByteChunk chunk) throws IOException {
            int len = chunk.getLength();
            int start = chunk.getStart();
            byte[] b = chunk.getBuffer();
            socketWrapper.write(isBlocking(), b, start, len);
            byteCount += len;
            return len;
        }

        /**
         * Write chunk.
         * 将数据写入到 socket
         */
        @Override
        public int doWrite(ByteBuffer chunk) throws IOException {
            try {
                int len = chunk.remaining();
                // 如果 writeListener 为 null 那么以阻塞方式 写入数据
                socketWrapper.write(isBlocking(), chunk);
                len -= chunk.remaining();
                byteCount += len;
                return len;
            } catch (IOException ioe) {
                response.action(ActionCode.CLOSE_NOW, ioe);
                // Re-throw
                throw ioe;
            }
        }

        @Override
        public long getBytesWritten() {
            return byteCount;
        }

        @Override
        public void end() throws IOException {
            socketWrapper.flush(true);
        }

        @Override
        public void flush() throws IOException {
            socketWrapper.flush(isBlocking());
        }
    }
}
