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
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import org.apache.tomcat.util.net.NioEndpoint.Poller;
import org.apache.tomcat.util.res.StringManager;

/**
 * Base class for a SocketChannel wrapper used by the endpoint.
 * This way, logic for an SSL socket channel remains the same as for
 * a non SSL, making sure we don't need to code for any exception cases.
 *
 * @version 1.0
 * tomcat 基于 JDK 对外开放的 接口 封装了 JDK channel
 */
public class NioChannel implements ByteChannel {

    protected static final StringManager sm = StringManager.getManager(NioChannel.class);

    /**
     * 一个空的 bytebuffer 对象
     */
    protected static final ByteBuffer emptyBuf = ByteBuffer.allocate(0);

    /**
     * JDK channel
     */
    protected SocketChannel sc = null;
    /**
     * 内部还维护了一个socket 对象
     */
    protected SocketWrapperBase<NioChannel> socketWrapper = null;

    /**
     * 该对象内部 划分了一个 writeBuffer 和一个 readBuffer
     */
    protected final SocketBufferHandler bufHandler;

    /**
     * 关联的 轮询selector 对象
     */
    protected Poller poller;

    public NioChannel(SocketChannel channel, SocketBufferHandler bufHandler) {
        this.sc = channel;
        this.bufHandler = bufHandler;
    }

    /**
     * Reset the channel
     *
     * @throws IOException If a problem was encountered resetting the channel
     * 重置内部的 buffer 对象
     */
    public void reset() throws IOException {
        bufHandler.reset();
    }


    void setSocketWrapper(SocketWrapperBase<NioChannel> socketWrapper) {
        this.socketWrapper = socketWrapper;
    }

    /**
     * Free the channel memory
     * 清理channel 占用的内存 如果是 DirectBuf 则会通过反射调用cleaner 清理内存
     */
    public void free() {
        bufHandler.free();
    }

    /**
     * Returns true if the network buffer has been flushed out and is empty.
     *
     * @param block     Unused. May be used when overridden
     * @param s         Unused. May be used when overridden
     * @param timeout   Unused. May be used when overridden
     * @return Always returns <code>true</code> since there is no network buffer
     *         in the regular channel
     *
     * @throws IOException Never for non-secure channel
     * 默认情况下 通过该channel 写入成功就是刷盘成功了 如果是 SecureNioChannel 就代表还需要SSL 成功握手之类的一系列流程
     */
    public boolean flush(boolean block, Selector s, long timeout)
            throws IOException {
        return true;
    }


    /**
     * Closes this channel.
     *
     * @throws IOException If an I/O error occurs
     * 关闭 socket 已经channel
     */
    @Override
    public void close() throws IOException {
        getIOChannel().socket().close();
        getIOChannel().close();
    }

    /**
     * Close the connection.
     *
     * @param force Should the underlying socket be forcibly closed?
     *
     * @throws IOException If closing the secure channel fails.
     */
    public void close(boolean force) throws IOException {
        if (isOpen() || force ) close();
    }

    /**
     * Tells whether or not this channel is open.
     *
     * @return <code>true</code> if, and only if, this channel is open
     */
    @Override
    public boolean isOpen() {
        return sc.isOpen();
    }

    /**
     * Writes a sequence of bytes to this channel from the given buffer.
     *
     * @param src The buffer from which bytes are to be retrieved
     * @return The number of bytes written, possibly zero
     * @throws IOException If some other I/O error occurs
     * 将buffer 中的数据写入到 channel 中
     */
    @Override
    public int write(ByteBuffer src) throws IOException {
        checkInterruptStatus();
        // 往 jdk channel 中写入数据
        return sc.write(src);
    }

    /**
     * Reads a sequence of bytes from this channel into the given buffer.
     *
     * @param dst The buffer into which bytes are to be transferred
     * @return The number of bytes read, possibly zero, or <code>-1</code> if
     *         the channel has reached end-of-stream
     * @throws IOException If some other I/O error occurs
     * 将 jdk channel 中的数据 读取到 dst中
     */
    @Override
    public int read(ByteBuffer dst) throws IOException {
        return sc.read(dst);
    }

    /**
     * 获取该channel 绑定到 poller 上返回的 key 上关联的 attach 对象 实际上就是 tomcat 封装的socket 对象
     * @return
     */
    public Object getAttachment() {
        Poller pol = getPoller();
        Selector sel = pol!=null?pol.getSelector():null;
        SelectionKey key = sel!=null?getIOChannel().keyFor(sel):null;
        Object att = key!=null?key.attachment():null;
        return att;
    }

    public SocketBufferHandler getBufHandler() {
        return bufHandler;
    }

    public Poller getPoller() {
        return poller;
    }

    public SocketChannel getIOChannel() {
        return sc;
    }

    /**
     * 默认情况 没有处于关闭状态
     * @return
     */
    public boolean isClosing() {
        return false;
    }

    /**
     * 默认情况已经处理完 握手流程
     * @return
     */
    public boolean isHandshakeComplete() {
        return true;
    }

    /**
     * Performs SSL handshake hence is a no-op for the non-secure
     * implementation.
     *
     * @param read  Unused in non-secure implementation
     * @param write Unused in non-secure implementation
     * @return Always returns zero
     * @throws IOException Never for non-secure channel
     */
    public int handshake(boolean read, boolean write) throws IOException {
        return 0;
    }

    public void setPoller(Poller poller) {
        this.poller = poller;
    }

    public void setIOChannel(SocketChannel IOChannel) {
        this.sc = IOChannel;
    }

    @Override
    public String toString() {
        return super.toString()+":"+this.sc.toString();
    }

    public int getOutboundRemaining() {
        return 0;
    }

    /**
     * Return true if the buffer wrote data. NO-OP for non-secure channel.
     *
     * @return Always returns {@code false} for non-secure channel
     *
     * @throws IOException Never for non-secure channel
     */
    public boolean flushOutbound() throws IOException {
        return false;
    }

    /**
     * This method should be used to check the interrupt status before
     * attempting a write.
     *
     * If a thread has been interrupted and the interrupt has not been cleared
     * then an attempt to write to the socket will fail. When this happens the
     * socket is removed from the poller without the socket being selected. This
     * results in a connection limit leak for NIO as the endpoint expects the
     * socket to be selected even in error conditions.
     * @throws IOException If the current thread was interrupted
     * 通过将当前线程 标记成 被打断状态 来拒绝针对该channel 的写入工作
     */
    protected void checkInterruptStatus() throws IOException {
        if (Thread.interrupted()) {
            throw new IOException(sm.getString("channel.nio.interrupted"));
        }
    }


    private ApplicationBufferHandler appReadBufHandler;
    public void setAppReadBufHandler(ApplicationBufferHandler handler) {
        this.appReadBufHandler = handler;
    }
    protected ApplicationBufferHandler getAppReadBufHandler() {
        return appReadBufHandler;
    }
}
