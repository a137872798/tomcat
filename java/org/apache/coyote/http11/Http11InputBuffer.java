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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * InputBuffer for HTTP that provides request header parsing as well as transfer
 * encoding.
 * http 11 指定的 输入buffer
 * 该对象 应该就是直接跟 网络Buffer 交互的吧 这里读取数据并抽象成 req 对象
 */
public class Http11InputBuffer implements InputBuffer, ApplicationBufferHandler {

    // -------------------------------------------------------------- Constants

    private static final Log log = LogFactory.getLog(Http11InputBuffer.class);

    /**
     * The string manager for this package.
     */
    private static final StringManager sm = StringManager.getManager(Http11InputBuffer.class);


    /**
     * 看来写入的任何数据 都会以这个作为开头
     */
    private static final byte[] CLIENT_PREFACE_START =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Associated Coyote request.
     * 内部关联一个 coyoteRequest 对象
     */
    private final Request request;


    /**
     * Headers of the associated request.
     * 该请求对象关联的 元数据头
     */
    private final MimeHeaders headers;


    /**
     * 是否要拒绝非法的请求头
     */
    private final boolean rejectIllegalHeaderName;

    /**
     * State.
     * 是否已经解析完请求头
     */
    private boolean parsingHeader;


    /**
     * Swallow input ? (in the case of an expectation)
     * 是否允许 吞吐输入流
     */
    private boolean swallowInput;


    /**
     * The read buffer.
     * 会从该buffer 中读取数据
     */
    private ByteBuffer byteBuffer;


    /**
     * Pos of the end of the header in the buffer, which is also the
     * start of the body.
     * request header 的尾指针
     */
    private int end;


    /**
     * Wrapper that provides access to the underlying socket.
     * socket 包装对象
     */
    private SocketWrapperBase<?> wrapper;


    /**
     * Underlying input buffer.
     * 该对象会设置到过滤链中
     */
    private InputBuffer inputStreamInputBuffer;


    /**
     * Filter library.
     * Note: Filter[Constants.CHUNKED_FILTER] is always the "chunked" filter.
     * 一组 过滤器对象  inputFilter 实际上也继承了 inputBuffer 接口
     */
    private InputFilter[] filterLibrary;


    /**
     * Active filters (in order).
     * 当前活跃的过滤器
     */
    private InputFilter[] activeFilters;


    /**
     * Index of the last active filter.
     * 获取最后一个 活跃的过滤器的下标
     */
    private int lastActiveFilter;


    /**
     * Parsing state - used for non blocking parsing so that
     * when more data arrives, we can pick up where we left off.
     */
    private boolean parsingRequestLine;
    private int parsingRequestLinePhase = 0;
    /**
     * 代表解析到末尾了
     */
    private boolean parsingRequestLineEol = false;
    private int parsingRequestLineStart = 0;
    /**
     * 代表解析到 url 中 ? 的起始位置
     */
    private int parsingRequestLineQPos = -1;
    /**
     * 记录当前解析请求头到什么位置
     */
    private HeaderParsePosition headerParsePos;
    /**
     * 解析后的数据对象
     */
    private final HeaderParseData headerData = new HeaderParseData();
    /**
     * 该对象负责将 数据流变成req 对象  内部方法比较细  不详细看了
     */
    private final HttpParser httpParser;

    /**
     * Maximum allowed size of the HTTP request line plus headers plus any
     * leading blank lines.
     * 代表请求头 最大长度
     */
    private final int headerBufferSize;

    /**
     * Known size of the NioChannel read buffer.
     * 读取数据的 buffer 的大小
     */
    private int socketReadBufferSize;


    // ----------------------------------------------------------- Constructors

    /**
     * 通过 req 对象来初始化  应该就是将数据流 解析并填充到 req 中
     * @param request
     * @param headerBufferSize
     * @param rejectIllegalHeaderName  非法请求名
     * @param httpParser    http协议解析器对象
     */
    public Http11InputBuffer(Request request, int headerBufferSize,
            boolean rejectIllegalHeaderName, HttpParser httpParser) {

        this.request = request;
        headers = request.getMimeHeaders();

        this.headerBufferSize = headerBufferSize;
        this.rejectIllegalHeaderName = rejectIllegalHeaderName;
        this.httpParser = httpParser;

        filterLibrary = new InputFilter[0];
        activeFilters = new InputFilter[0];
        lastActiveFilter = -1;

        // 初始化时 默认正处在 parsing 中
        parsingHeader = true;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        // 代表当前处在起点的位置
        headerParsePos = HeaderParsePosition.HEADER_START;
        swallowInput = true;

        inputStreamInputBuffer = new SocketInputBuffer();
    }


    // ------------------------------------------------------------- Properties

    /**
     * Add an input filter to the filter library.
     *
     * @throws NullPointerException if the supplied filter is null
     * 增加一个过滤器
     */
    void addFilter(InputFilter filter) {

        if (filter == null) {
            throw new NullPointerException(sm.getString("iib.filter.npe"));
        }

        // 这里每次只扩容一个单位  避免无谓的内存开销
        InputFilter[] newFilterLibrary = new InputFilter[filterLibrary.length + 1];
        for (int i = 0; i < filterLibrary.length; i++) {
            newFilterLibrary[i] = filterLibrary[i];
        }
        newFilterLibrary[filterLibrary.length] = filter;
        filterLibrary = newFilterLibrary;

        // 这不是空数组吗  可能代表着每次 添加一个 新的过滤器后 活跃过滤器当前就是空的  每当有需要时应该会在转移到这里面
        activeFilters = new InputFilter[filterLibrary.length];
    }


    /**
     * Get filters.  默认返回的是 library
     */
    InputFilter[] getFilters() {
        return filterLibrary;
    }


    /**
     * Add an input filter to the filter library.
     * 将某个过滤器 添加到 活跃过滤器中
     */
    void addActiveFilter(InputFilter filter) {

        // lastActiveFilter 记录了active数组的长度
        if (lastActiveFilter == -1) {
            // 将内部的buffer 设置到过滤器中
            filter.setBuffer(inputStreamInputBuffer);
        } else {
            // 如果在 active 过滤器中已经存在 这里不进行添加
            for (int i = 0; i <= lastActiveFilter; i++) {
                if (activeFilters[i] == filter)
                    return;
            }
            // 否则把过滤器作为 buffer 设置到 新传入的过滤器中   这相当于是一个处理链了 每个filter作为buffer 设置到下一个 filter中
            // 而最上层的 filter 内部设置的是 inputStreamInputBuffer  每次过滤器处理后 内部数据已经发生变化 再由下个过滤器进行处理
            filter.setBuffer(activeFilters[lastActiveFilter]);
        }

        activeFilters[++lastActiveFilter] = filter;

        // 每个过滤器都连接到相同的req 上
        filter.setRequest(request);
    }


    /**
     * Set the swallow input flag.
     * 设置是否允许 吞吐数据
     */
    void setSwallowInput(boolean swallowInput) {
        this.swallowInput = swallowInput;
    }


    // ---------------------------------------------------- InputBuffer Methods

    /**
     * @deprecated Unused. Will be removed in Tomcat 9. Use
     *             {@link #doRead(ApplicationBufferHandler)}
     */
    @Deprecated
    @Override
    public int doRead(ByteChunk chunk) throws IOException {

        if (lastActiveFilter == -1)
            return inputStreamInputBuffer.doRead(chunk);
        else
            return activeFilters[lastActiveFilter].doRead(chunk);

    }

    /**
     * 将 byteBuffer 的数据 转移到 handler 中
     * @param handler ApplicationBufferHandler that provides the buffer to read
     *                data into.
     *
     * @return
     * @throws IOException
     */
    @Override
    public int doRead(ApplicationBufferHandler handler) throws IOException {

        // 如果不存在过滤链的话 直接使用 内部真正的buffer 对象做数据转移就可以了
        if (lastActiveFilter == -1)
            return inputStreamInputBuffer.doRead(handler);
        else
            // 从最后一个过滤链开始处理 每个过滤链本身又是buffer 会将数据传递下去
            return activeFilters[lastActiveFilter].doRead(handler);

    }


    // ------------------------------------------------------- Protected Methods

    /**
     * Recycle the input buffer. This should be called when closing the
     * connection.
     */
    void recycle() {
        wrapper = null;
        request.recycle();

        for (int i = 0; i <= lastActiveFilter; i++) {
            activeFilters[i].recycle();
        }

        // 将buffer 指针还原
        // TODO 这些类的方法为什么可以是非线程安全的  它处在一个什么样的线程模型中被使用
        byteBuffer.limit(0).position(0);
        lastActiveFilter = -1;
        parsingHeader = true;
        swallowInput = true;

        headerParsePos = HeaderParsePosition.HEADER_START;
        parsingRequestLine = true;
        // 解析阶段重置到0
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerData.recycle();
    }


    /**
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     * 这里只是做一个清理工作
     */
    void nextRequest() {
        // 先重置当前 req 对象
        request.recycle();

        // 看来数据就是从 byteBuffer 中读取出来的 然后生成req 对象  有几条线程在这样做 又是怎么处理线程安全的???
        if (byteBuffer.position() > 0) {
            if (byteBuffer.remaining() > 0) {
                // Copy leftover bytes to the beginning of the buffer
                // 该方法时将 0~ position() 之间的数据清除
                byteBuffer.compact();
                byteBuffer.flip();
            } else {
                // Reset position and limit to 0
                // 如果没有剩余数据 那么直接将指针置空
                byteBuffer.position(0).limit(0);
            }
        }

        // Recycle filters  挨个回收过滤器
        for (int i = 0; i <= lastActiveFilter; i++) {
            activeFilters[i].recycle();
        }

        // Reset pointers
        lastActiveFilter = -1;
        parsingHeader = true;
        swallowInput = true;

        headerParsePos = HeaderParsePosition.HEADER_START;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerData.recycle();
    }


    /**
     * Read the request line. This function is meant to be used during the
     * HTTP request header parsing. Do NOT attempt to read the request body
     * using it.
     *
     * @throws IOException If an exception occurs during the underlying socket
     * read operations, or if the given buffer is not big enough to accommodate
     * the whole line.
     * @return true if data is properly fed; false if no data is available
     * immediately and thread should be freed
     * 该方法是用来读取 请求行数据的
     * 比如 : GET /home/msg/data/personalcontent?num=8 HTTP/1.1
     * @param keptAlive  代表是否试图保持连接
     */
    boolean parseRequestLine(boolean keptAlive) throws IOException {

        // check state  代表已经读取完毕了
        if (!parsingRequestLine) {
            return true;
        }
        //
        // Skipping blank lines
        // 当前处在哪个 解析阶段 如果是小于2 的阶段
        if (parsingRequestLinePhase < 2) {
            byte chr = 0;
            do {

                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (keptAlive) {
                        // Haven't read any request data yet so use the keep-alive
                        // timeout.
                        // 设置更长的超时时间 这样就能保证连接不被切断
                        wrapper.setReadTimeout(wrapper.getEndpoint().getKeepAliveTimeout());
                    }
                    // 代表当前 io 层没有数据可以读取
                    if (!fill(false)) {
                        // A read is pending, so no longer in initial state
                        // 此时进入1 阶段 也就是开始读取数据 且阻塞在网络层 等待 网络缓冲区写入数据
                        parsingRequestLinePhase = 1;
                        return false;
                    }
                    // At least one byte of the request has been received.
                    // Switch to the socket timeout.
                    // 代表成功读取到数据  将读取超时时间设置成连接超时时长
                    // 如果等待一个连接的时间还没有读取到新数据 那么该连接就可以断开了
                    wrapper.setReadTimeout(wrapper.getEndpoint().getConnectionTimeout());
                }
                // 这里已经成功读取到了数据 开始于 标记http请求的 数据头做匹配  limit() >= 也就是对请求头长度的最小要求
                if (!keptAlive && byteBuffer.position() == 0 && byteBuffer.limit() >= CLIENT_PREFACE_START.length - 1) {
                    boolean prefaceMatch = true;
                    for (int i = 0; i < CLIENT_PREFACE_START.length && prefaceMatch; i++) {
                        if (CLIENT_PREFACE_START[i] != byteBuffer.get(i)) {
                            prefaceMatch = false;
                        }
                    }
                    // 当头部 匹配成功的时候
                    if (prefaceMatch) {
                        // HTTP/2 preface matched  将阶段标记成-1
                        parsingRequestLinePhase = -1;
                        return false;
                    }
                }
                // Set the start time once we start reading data (even if it is
                // just skipping blank lines)
                // 设置启动时间 代表开始解析请求了
                if (request.getStartTime() < 0) {
                    request.setStartTime(System.currentTimeMillis());
                }
                chr = byteBuffer.get();
                // 在解析到换行符前 都会不断读取新数据  记得在 http协议中 请求头数据每次都要换行
            } while ((chr == Constants.CR) || (chr == Constants.LF));
            // 可用范围减小
            byteBuffer.position(byteBuffer.position() - 1);

            parsingRequestLineStart = byteBuffer.position();
            // 将状态标记成2  代表此时已经收到了完整的一行
            parsingRequestLinePhase = 2;
            if (log.isDebugEnabled()) {
                log.debug("Received ["
                        + new String(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining(), StandardCharsets.ISO_8859_1) + "]");
            }
        }
        // 这时  byteBuffer 内部已经有足够的数据了
        if (parsingRequestLinePhase == 2) {
            //
            // Reading the method name
            // Method name is a token
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed  代表还有空间 尝试读取数据
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    // 没有从 fill方法中读取到数据 返回false
                    if (!fill(false)) // request line parsing
                        return false;
                }
                // Spec says method name is a token followed by a single SP but
                // also be tolerant of multiple SP and/or HT.
                int pos = byteBuffer.position();
                byte chr = byteBuffer.get();
                // 如果解析到了 空格
                if (chr == Constants.SP || chr == Constants.HT) {
                    space = true;
                    // 从开始读取截止到 空格 读取到了一个 HTTP Method
                    request.method().setBytes(byteBuffer.array(), parsingRequestLineStart,
                            pos - parsingRequestLineStart);
                // 如果解析到的不是token
                } else if (!HttpParser.isToken(chr)) {
                    // 这里倒退一位 并抛出异常
                    byteBuffer.position(byteBuffer.position() - 1);
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                    throw new IllegalArgumentException(sm.getString("iib.invalidmethod"));
                }
            }
            // 代表解析完请求方式了
            parsingRequestLinePhase = 3;
        }
        // 进入下一阶段  这里就是为了跳过空格
        if (parsingRequestLinePhase == 3) {
            // Spec says single SP but also be tolerant of multiple SP and/or HT
            boolean space = true;
            while (space) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                byte chr = byteBuffer.get();
                if (!(chr == Constants.SP || chr == Constants.HT)) {
                    space = false;
                    byteBuffer.position(byteBuffer.position() - 1);
                }
            }
            parsingRequestLineStart = byteBuffer.position();
            parsingRequestLinePhase = 4;
        }
        // 进入第四阶段
        if (parsingRequestLinePhase == 4) {
            // Mark the current buffer position

            int end = 0;
            //
            // Reading the URI  开始读取 url了
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed
                // 如果无法再读取到数据直接返回
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                int pos = byteBuffer.position();
                byte chr = byteBuffer.get();
                // 代表读取到末尾 记录当前位置
                if (chr == Constants.SP || chr == Constants.HT) {
                    space = true;
                    end = pos;
                // 如果读取到了换行符  这里是兼容 HTTP/0.9的  先不看
                } else if (chr == Constants.CR || chr == Constants.LF) {
                    // HTTP/0.9 style request
                    parsingRequestLineEol = true;
                    space = true;
                    end = pos;
                // 如果解析到参数部分 即 url中   xxxxx?k1=v1&k2=v2  的部分
                } else if (chr == Constants.QUESTION && parsingRequestLineQPos == -1) {
                    parsingRequestLineQPos = pos;
                // 使用的 查询参数不被允许
                } else if (parsingRequestLineQPos != -1 && !httpParser.isQueryRelaxed(chr)) {
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                    // %nn decoding will be checked at the point of decoding
                    throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget"));
                    // 如果是不被允许的字符 也抛出异常
                } else if (httpParser.isNotRequestTargetRelaxed(chr)) {
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                    // This is a general check that aims to catch problems early
                    // Detailed checking of each part of the request target will
                    // happen in Http11Processor#prepareRequest()
                    throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget"));
                }
            }
            // 如果检测到?  那么从parsingRequestLineQPos 开始的部分就是 查询参数了
            if (parsingRequestLineQPos >= 0) {
                request.queryString().setBytes(byteBuffer.array(), parsingRequestLineQPos + 1,
                        end - parsingRequestLineQPos - 1);
                // 从开始到  parsingRequestLineQPos 的部分是 url
                request.requestURI().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        parsingRequestLineQPos - parsingRequestLineStart);
            } else {
                // 代表本次语句没有 ? 只需要设置 url 就可以
                request.requestURI().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        end - parsingRequestLineStart);
            }
            parsingRequestLinePhase = 5;
        }
        // 进入第五阶段  同样是去除空格的部分
        if (parsingRequestLinePhase == 5) {
            // Spec says single SP but also be tolerant of multiple and/or HT
            boolean space = true;
            while (space) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                byte chr = byteBuffer.get();
                if (!(chr == Constants.SP || chr == Constants.HT)) {
                    space = false;
                    byteBuffer.position(byteBuffer.position() - 1);
                }
            }
            parsingRequestLineStart = byteBuffer.position();
            parsingRequestLinePhase = 6;

            // Mark the current buffer position
            end = 0;
        }
        // 开始读取协议信息  也就是 HTTP/ + 数字
        if (parsingRequestLinePhase == 6) {
            //
            // Reading the protocol
            // Protocol is always "HTTP/" DIGIT "." DIGIT
            //
            while (!parsingRequestLineEol) {
                // Read new bytes if needed
                // 无法继续填充数据了就直接返回
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }

                int pos = byteBuffer.position();
                byte chr = byteBuffer.get();
                if (chr == Constants.CR) {
                    end = pos;
                } else if (chr == Constants.LF) {
                    if (end == 0) {
                        end = pos;
                    }
                    // 代表解析完成
                    parsingRequestLineEol = true;
                } else if (!HttpParser.isHttpProtocol(chr)) {
                    throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol"));
                }
            }

            // 本次截取出来的部分就是 protocol
            if ((end - parsingRequestLineStart) > 0) {
                request.protocol().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        end - parsingRequestLineStart);
            } else {
                // 如果没有读取到对应部分 代表本次数据没有 协议信息
                request.protocol().setString("");
            }
            parsingRequestLine = false;
            parsingRequestLinePhase = 0;
            parsingRequestLineEol = false;
            parsingRequestLineStart = 0;
            return true;
        }
        throw new IllegalStateException(
                "Invalid request line parse phase:" + parsingRequestLinePhase);
    }


    /**
     * Parse the HTTP headers.
     * 解析请求头
     */
    boolean parseHeaders() throws IOException {
        if (!parsingHeader) {
            throw new IllegalStateException(sm.getString("iib.parseheaders.ise.error"));
        }

        HeaderParseStatus status = HeaderParseStatus.HAVE_MORE_HEADERS;

        do {
            // parseHeader 中会解析一个请求头的键值对 这里不断循环直到请求头中所有键值对都被读取完
            status = parseHeader();
            // Checking that
            // (1) Headers plus request line size does not exceed its limit
            // (2) There are enough bytes to avoid expanding the buffer when
            // reading body
            // Technically, (2) is technical limitation, (1) is logical
            // limitation to enforce the meaning of headerBufferSize
            // From the way how buf is allocated and how blank lines are being
            // read, it should be enough to check (1) only.
            // 如果读取的大小 超过了一个请求头 应当具备的大小 或者   当前剩余容量小于请求体大小 都会抛出异常
            if (byteBuffer.position() > headerBufferSize || byteBuffer.capacity() - byteBuffer.position() < socketReadBufferSize) {
                throw new IllegalArgumentException(sm.getString("iib.requestheadertoolarge.error"));
            }
        } while (status == HeaderParseStatus.HAVE_MORE_HEADERS);
        // 代表处理完毕了 此时 parsingHeader 设置为false
        if (status == HeaderParseStatus.DONE) {
            parsingHeader = false;
            end = byteBuffer.position();
            return true;
        } else {
            return false;
        }
    }

    /**
     * 获取当前解析req请求行的 阶段
     * @return
     */
    int getParsingRequestLinePhase() {
        return parsingRequestLinePhase;
    }


    /**
     * End request (consumes leftover bytes).
     *
     * @throws IOException an underlying I/O error occurred
     * 代表某个请求的数据已经被完全处理完了
     */
    void endRequest() throws IOException {

        if (swallowInput && (lastActiveFilter != -1)) {
            // 消费掉 buffer 内部的数据  这里有可能返回负数 应该是代表 还需要从byteBuffer  中读取更多数据才是一个完整的req 吧
            int extraBytes = (int) activeFilters[lastActiveFilter].end();
            byteBuffer.position(byteBuffer.position() - extraBytes);
        }
    }


    /**
     * Available bytes in the buffers (note that due to encoding, this may not
     * correspond).
     * 返回当前可用的 尺寸
     */
    int available(boolean read) {
        int available = byteBuffer.remaining();
        if ((available == 0) && (lastActiveFilter >= 0)) {
            for (int i = 0; (available == 0) && (i <= lastActiveFilter); i++) {
                available = activeFilters[i].available();
            }
        }
        // 如果是判断有多少可写入 那么现在就可以将 剩余空间返回了
        if (available > 0 || !read) {
            return available;
        }

        try {
            // HTTP11 总是返回 true
            // 如果是判断有多少数据可以读  那么将wrapper 中剩余的数据读取出来 之后再获取剩余空间
            if (wrapper.hasDataToRead()) {
                fill(false);
                available = byteBuffer.remaining();
            }
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("iib.available.readFail"), ioe);
            }
            // Not ideal. This will indicate that data is available which should
            // trigger a read which in turn will trigger another IOException and
            // that one can be thrown.
            available = 1;
        }
        return available;
    }


    /**
     * Has all of the request body been read? There are subtle differences
     * between this and available() &gt; 0 primarily because of having to handle
     * faking non-blocking reads with the blocking IO connector.
     * 是否已经处理完请求体中所有的数据了
     */
    boolean isFinished() {
        if (byteBuffer.limit() > byteBuffer.position()) {
            // Data to read in the buffer so not finished
            return false;
        }

        /*
         * Don't use fill(false) here because in the following circumstances
         * BIO will block - possibly indefinitely
         * - client is using keep-alive and connection is still open
         * - client has sent the complete request
         * - client has not sent any of the next request (i.e. no pipelining)
         * - application has read the complete request
         */

        // Check the InputFilters

        // 如果包含过滤器 通过访问过滤器 判断是否处理完成
        if (lastActiveFilter >= 0) {
            return activeFilters[lastActiveFilter].isFinished();
        } else {
            // No filters. Assume request is not finished. EOF will signal end of
            // request.
            return false;
        }
    }

    // 返回剩下可用的部分
    ByteBuffer getLeftover() {
        int available = byteBuffer.remaining();
        if (available > 0) {
            return ByteBuffer.wrap(byteBuffer.array(), byteBuffer.position(), available);
        } else {
            return null;
        }
    }


    /**
     * 初始化inputBuffer 对象  该对象负责跟 scoketWrapper 交互 从网络IO 中读取数据流并解析成 Request 对象
     * @param socketWrapper
     */
    void init(SocketWrapperBase<?> socketWrapper) {

        wrapper = socketWrapper;
        // 将自身作为 应用级buffer 设置到 socketWrapper 中 这里要好好研究它们之间的关系
        wrapper.setAppReadBufHandler(this);

        // 计算合适大小 并开始初始化容器
        int bufLength = headerBufferSize +
                wrapper.getSocketBufferHandler().getReadBuffer().capacity();
        if (byteBuffer == null || byteBuffer.capacity() < bufLength) {
            byteBuffer = ByteBuffer.allocate(bufLength);
            byteBuffer.position(0).limit(0);
        }
    }



    // --------------------------------------------------------- Private Methods

    /**
     * Attempts to read some data into the input buffer.
     *
     * @return <code>true</code> if more data was added to the input buffer
     *         otherwise <code>false</code>   如果成功添加进了数据 返回true
     *         尝试将数据读取到byteBuffer 中
     */
    private boolean fill(boolean block) throws IOException {

        // 如果正处在解析请求头的阶段
        if (parsingHeader) {
            if (byteBuffer.limit() >= headerBufferSize) {
                if (parsingRequestLine) {
                    // Avoid unknown protocol triggering an additional error
                    request.protocol().setString(Constants.HTTP_11);
                }
                throw new IllegalArgumentException(sm.getString("iib.requestheadertoolarge.error"));
            }
        } else {
            // 将指针都设置到header 末尾
            byteBuffer.limit(end).position(end);
        }

        byteBuffer.mark();
        // 将position 和 limit 同步到末尾
        if (byteBuffer.position() < byteBuffer.limit()) {
            byteBuffer.position(byteBuffer.limit());
        }
        byteBuffer.limit(byteBuffer.capacity());
        // 以阻塞  or 非阻塞方式 继续往 byteBuffer 中填充数据   这里会连接到 NioEndpoint 的数据读取 也就是从内核态的网络缓冲区读取数据
        // 也就是真正与底层交互的地方
        int nRead = wrapper.read(block, byteBuffer);
        // 将position 还原到 mark的位置
        byteBuffer.limit(byteBuffer.position()).reset();
        // 这里代表从底层读取到数据
        if (nRead > 0) {
            return true;
        } else if (nRead == -1) {
            throw new EOFException(sm.getString("iib.eof.error"));
        } else {
            return false;
        }

    }


    /**
     * Parse an HTTP header.
     *
     * @return false after reading a blank line (which indicates that the
     * HTTP header parsing is done
     * 开始解析请求头   这里只是每次解析一个键值对
     */
    private HeaderParseStatus parseHeader() throws IOException {

        //
        // Check for blank line
        //

        byte chr = 0;
        while (headerParsePos == HeaderParsePosition.HEADER_START) {

            // Read new bytes if needed
            if (byteBuffer.position() >= byteBuffer.limit()) {
                if (!fill(false)) {// parse header
                    headerParsePos = HeaderParsePosition.HEADER_START;
                    // 返回 代表当前还需要读取更多数据  (因为缓冲区满导致本次无法处理完全部数据 需要继续读取才好完成任务)
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            chr = byteBuffer.get();

            // 忽略读取到的换行
            if (chr == Constants.CR) {
                // Skip
            } else if (chr == Constants.LF) {
                // 代表读取完某行
                return HeaderParseStatus.DONE;
            } else {
                byteBuffer.position(byteBuffer.position() - 1);
                break;
            }

        }

        // 将状态切换成 读取headerName
        if (headerParsePos == HeaderParsePosition.HEADER_START) {
            // Mark the current buffer position
            headerData.start = byteBuffer.position();
            headerParsePos = HeaderParsePosition.HEADER_NAME;
        }

        //
        // Reading the header name
        // Header name is always US-ASCII
        //

        // 开始不断读取 Header 的键值对
        while (headerParsePos == HeaderParsePosition.HEADER_NAME) {

            // Read new bytes if needed   这里代表需要读取数据 一旦成功调用 fill 后该分支就不会进入了 之后的while
            // 不断处理之前读取到的数据就可以
            if (byteBuffer.position() >= byteBuffer.limit()) {
                if (!fill(false)) { // parse header
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            // 开始读取数据
            int pos = byteBuffer.position();
            chr = byteBuffer.get();
            // 如果解析到冒号 那么 冒号前 的就是 name 后面的是value
            if (chr == Constants.COLON) {
                // 代表当前要解析到 value 了
                headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
                // pos - start 的是name  在MimeHeaders 中添加一个键值对 设置name 的同时返回 对应的value 此时value 还没有设置 还需要继续读取
                // 这里将 headerData.headerValue 和 headers.value 指向同一对象 在后面会将 name 对应的value 解析出来并设置到headerValue 中
                headerData.headerValue = headers.addValue(byteBuffer.array(), headerData.start,
                        pos - headerData.start);
                pos = byteBuffer.position();
                // Mark the current buffer position
                headerData.start = pos;
                headerData.realPos = pos;
                headerData.lastSignificantChar = pos;
                break;
                // 在解析 键值对的时候如果发现了非符号部分
            } else if (!HttpParser.isToken(chr)) {
                // Non-token characters are illegal in header names
                // Parsing continues so the error can be reported in context
                headerData.lastSignificantChar = pos;
                // 将指针退一位
                byteBuffer.position(byteBuffer.position() - 1);
                // skipLine() will handle the error
                // 因为本行的数据已经异常了 选择抛出异常还是 打印错误日志  同时跳过本行
                // 并返回 NEED_MORE_DATA
                return skipLine();
            }

            // chr is next byte of header name. Convert to lowercase.
            // 将字符转换成小写保存 并覆盖当前位置的数据
            if ((chr >= Constants.A) && (chr <= Constants.Z)) {
                byteBuffer.put(pos, (byte) (chr - Constants.LC_OFFSET));
            }
        }

        // Skip the line and ignore the header
        if (headerParsePos == HeaderParsePosition.HEADER_SKIPLINE) {
            return skipLine();
        }

        //
        // Reading the header value (which can be spanned over multiple lines)
        //

        while (headerParsePos == HeaderParsePosition.HEADER_VALUE_START ||
               headerParsePos == HeaderParsePosition.HEADER_VALUE ||
               headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE) {

            // 代表开始读取 header 中的 某个键值
            if (headerParsePos == HeaderParsePosition.HEADER_VALUE_START) {
                // Skipping spaces
                while (true) {
                    // Read new bytes if needed
                    if (byteBuffer.position() >= byteBuffer.limit()) {
                        if (!fill(false)) {// parse header
                            // HEADER_VALUE_START
                            return HeaderParseStatus.NEED_MORE_DATA;
                        }
                    }

                    chr = byteBuffer.get();
                    if (!(chr == Constants.SP || chr == Constants.HT)) {
                        // 只要不是 SP 和HT 就代表正式开始读取 HeaderValue
                        headerParsePos = HeaderParsePosition.HEADER_VALUE;
                        // 本次读取到的值还要被使用 所以 回退一位
                        byteBuffer.position(byteBuffer.position() - 1);
                        break;
                    }
                }
            }
            if (headerParsePos == HeaderParsePosition.HEADER_VALUE) {

                // Reading bytes until the end of the line
                boolean eol = false;
                while (!eol) {

                    // Read new bytes if needed
                    if (byteBuffer.position() >= byteBuffer.limit()) {
                        if (!fill(false)) {// parse header
                            // HEADER_VALUE
                            return HeaderParseStatus.NEED_MORE_DATA;
                        }
                    }

                    // 这里在进行预读取  记录 从value开始的位置 到  LF 的位置
                    chr = byteBuffer.get();
                    if (chr == Constants.CR) {
                        // Skip
                    } else if (chr == Constants.LF) {
                        eol = true;
                    } else if (chr == Constants.SP || chr == Constants.HT) {
                        byteBuffer.put(headerData.realPos, chr);
                        headerData.realPos++;
                    } else {
                        byteBuffer.put(headerData.realPos, chr);
                        headerData.realPos++;
                        headerData.lastSignificantChar = headerData.realPos;
                    }
                }

                // Ignore whitespaces at the end of the line
                headerData.realPos = headerData.lastSignificantChar;

                // Checking the first character of the new line. If the character
                // is a LWS, then it's a multiline header
                headerParsePos = HeaderParsePosition.HEADER_MULTI_LINE;
            }
            // Read new bytes if needed
            if (byteBuffer.position() >= byteBuffer.limit()) {
                if (!fill(false)) {// parse header
                    // HEADER_MULTI_LINE
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            chr = byteBuffer.get(byteBuffer.position());
            if (headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE) {
                if ((chr != Constants.SP) && (chr != Constants.HT)) {
                    headerParsePos = HeaderParsePosition.HEADER_START;
                    break;
                } else {
                    // Copying one extra space in the buffer (since there must
                    // be at least one space inserted between the lines)
                    byteBuffer.put(headerData.realPos, chr);
                    headerData.realPos++;
                    headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
                }
            }
        }
        // Set the header value   将解析后的value 设置到 headerData 中
        headerData.headerValue.setBytes(byteBuffer.array(), headerData.start,
                headerData.lastSignificantChar - headerData.start);
        headerData.recycle();
        return HeaderParseStatus.HAVE_MORE_HEADERS;
    }


    /**
     * 处理异常情况
     * @return
     * @throws IOException
     */
    private HeaderParseStatus skipLine() throws IOException {
        // 代表需要跳过某行
        headerParsePos = HeaderParsePosition.HEADER_SKIPLINE;
        boolean eol = false;

        // Reading bytes until the end of the line
        while (!eol) {

            // Read new bytes if needed
            if (byteBuffer.position() >= byteBuffer.limit()) {
                if (!fill(false)) {
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            int pos = byteBuffer.position();
            byte chr = byteBuffer.get();
            if (chr == Constants.CR) {
                // Skip
                // 将本行的数据读取完 直到获取到 换行符
            } else if (chr == Constants.LF) {
                eol = true;
            } else {
                headerData.lastSignificantChar = pos;
            }
        }
        // 将错误的完整信息 输出出来  rejectIllegalHeaderName 为false 只是打印日志 并跳过本header 的解析 否则要抛出异常
        if (rejectIllegalHeaderName || log.isDebugEnabled()) {
            String message = sm.getString("iib.invalidheader",
                    new String(byteBuffer.array(), headerData.start,
                            headerData.lastSignificantChar - headerData.start + 1,
                            StandardCharsets.ISO_8859_1));
            if (rejectIllegalHeaderName) {
                throw new IllegalArgumentException(message);
            }
            log.debug(message);
        }

        headerParsePos = HeaderParsePosition.HEADER_START;
        return HeaderParseStatus.HAVE_MORE_HEADERS;
    }


    // ----------------------------------------------------------- Inner classes

    private enum HeaderParseStatus {
        DONE, HAVE_MORE_HEADERS, NEED_MORE_DATA
    }


    private enum HeaderParsePosition {
        /**
         * Start of a new header. A CRLF here means that there are no more
         * headers. Any other character starts a header name.
         */
        HEADER_START,
        /**
         * Reading a header name. All characters of header are HTTP_TOKEN_CHAR.
         * Header name is followed by ':'. No whitespace is allowed.<br>
         * Any non-HTTP_TOKEN_CHAR (this includes any whitespace) encountered
         * before ':' will result in the whole line being ignored.
         */
        HEADER_NAME,
        /**
         * Skipping whitespace before text of header value starts, either on the
         * first line of header value (just after ':') or on subsequent lines
         * when it is known that subsequent line starts with SP or HT.
         */
        HEADER_VALUE_START,
        /**
         * Reading the header value. We are inside the value. Either on the
         * first line or on any subsequent line. We come into this state from
         * HEADER_VALUE_START after the first non-SP/non-HT byte is encountered
         * on the line.
         */
        HEADER_VALUE,
        /**
         * Before reading a new line of a header. Once the next byte is peeked,
         * the state changes without advancing our position. The state becomes
         * either HEADER_VALUE_START (if that first byte is SP or HT), or
         * HEADER_START (otherwise).
         */
        HEADER_MULTI_LINE,
        /**
         * Reading all bytes until the next CRLF. The line is being ignored.
         */
        HEADER_SKIPLINE
    }


    private static class HeaderParseData {
        /**
         * When parsing header name: first character of the header.<br>
         * When skipping broken header line: first character of the header.<br>
         * When parsing header value: first character after ':'.
         * 记录解析请求头的起始指针
         */
        int start = 0;
        /**
         * When parsing header name: not used (stays as 0).<br>
         * When skipping broken header line: not used (stays as 0).<br>
         * When parsing header value: starts as the first character after ':'.
         * Then is increased as far as more bytes of the header are harvested.
         * Bytes from buf[pos] are copied to buf[realPos]. Thus the string from
         * [start] to [realPos-1] is the prepared value of the header, with
         * whitespaces removed as needed.<br>
         */
        int realPos = 0;
        /**
         * When parsing header name: not used (stays as 0).<br>
         * When skipping broken header line: last non-CR/non-LF character.<br>
         * When parsing header value: position after the last not-LWS character.<br>
         */
        int lastSignificantChar = 0;
        /**
         * MB that will store the value of the header. It is null while parsing
         * header name and is created after the name has been parsed.
         */
        MessageBytes headerValue = null;
        public void recycle() {
            start = 0;
            realPos = 0;
            lastSignificantChar = 0;
            headerValue = null;
        }
    }


    // ------------------------------------- InputStreamInputBuffer Inner Class

    /**
     * This class is an input buffer which will read its data from an input
     * stream.
     * 数据从 socket中读取到这里 之后 经过多个filter 后 完成对数据的过滤和加工
     */
    private class SocketInputBuffer implements InputBuffer {

        /**
         *
         * @deprecated Unused. Will be removed in Tomcat 9. Use
         *             {@link #doRead(ApplicationBufferHandler)}
         */
        @Deprecated
        @Override
        public int doRead(ByteChunk chunk) throws IOException {

            if (byteBuffer.position() >= byteBuffer.limit()) {
                // The application is reading the HTTP request body which is
                // always a blocking operation.
                if (!fill(true))
                    return -1;
            }

            int length = byteBuffer.remaining();
            chunk.setBytes(byteBuffer.array(), byteBuffer.position(), length);
            byteBuffer.position(byteBuffer.limit());

            return length;
        }

        /**
         * 装饰器链的 终点  装饰器的末尾 通过调用doRead 经过各个环节最后到达这里
         * @param handler ApplicationBufferHandler that provides the buffer to read
         *                data into.
         *
         * @return
         * @throws IOException
         */
        @Override
        public int doRead(ApplicationBufferHandler handler) throws IOException {
            if (byteBuffer.position() >= byteBuffer.limit()) {
                // The application is reading the HTTP request body which is
                // always a blocking operation.
                if (!fill(true))
                    return -1;
            }

            int length = byteBuffer.remaining();
            handler.setByteBuffer(byteBuffer.duplicate());
            byteBuffer.position(byteBuffer.limit());

            return length;
        }
    }


    @Override
    public void setByteBuffer(ByteBuffer buffer) {
        byteBuffer = buffer;
    }


    @Override
    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }


    @Override
    public void expand(int size) {
        if (byteBuffer.capacity() >= size) {
            byteBuffer.limit(size);
        }
        ByteBuffer temp = ByteBuffer.allocate(size);
        temp.put(byteBuffer);
        byteBuffer = temp;
        byteBuffer.mark();
        temp = null;
    }
}
