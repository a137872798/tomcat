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

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Thread safe non blocking selector pool
 *
 * @version 1.0
 * @since 6.0
 * <p>
 * nio 的选择器体系被封装成了一个 pool
 */
public class NioSelectorPool {

    public NioSelectorPool() {
    }

    private static final Log log = LogFactory.getLog(NioSelectorPool.class);

    /**
     * 选择器是否开启了共享  这样的话某个 selector 会被多个poller 共享
     */
    protected static final boolean SHARED =
            Boolean.parseBoolean(System.getProperty("org.apache.tomcat.util.net.NioSelectorShared", "true"));

    /**
     * 该对象内部包含一个 poller 封装了 轮询selector 的逻辑 同时可以直接对该对象进行读写
     */
    protected NioBlockingSelector blockingSelector;

    protected volatile Selector SHARED_SELECTOR;

    /**
     * 最大允许被创建的选择器数量为 200 个
     */
    protected int maxSelectors = 200;
    protected long sharedSelectorTimeout = 30000;
    protected int maxSpareSelectors = -1;
    protected boolean enabled = true;
    protected AtomicInteger active = new AtomicInteger(0);
    protected AtomicInteger spare = new AtomicInteger(0);

    /**
     * 是否 并发队列 维护选择器
     */
    protected ConcurrentLinkedQueue<Selector> selectors =
            new ConcurrentLinkedQueue<>();

    /**
     * 获取 共享选择器  默认情况下 选择器是被共享的 可能就是因为 IO 密集性即使增加选择器数量也不能显著提升性能
     * @return
     * @throws IOException
     */
    protected Selector getSharedSelector() throws IOException {
        // 创建选择器对象
        if (SHARED && SHARED_SELECTOR == null) {
            // double check 创建选择器对象
            synchronized (NioSelectorPool.class) {
                if (SHARED_SELECTOR == null) {
                    SHARED_SELECTOR = Selector.open();
                    log.info("Using a shared selector for servlet write/read");
                }
            }
        }
        return SHARED_SELECTOR;
    }

    /**
     * 获取选择器对象
     * @return
     * @throws IOException
     */
    public Selector get() throws IOException {
        if (SHARED) {
            return getSharedSelector();
        }
        // 走下面的逻辑 代表没有采用共享selector 的方式 实际上 增加selector 的数量并不能明显的提升性能 是否有准备好的事件 还是看底层操作系统是否准备好事件
        // 如果 pool 当前不可用 或者当前活跃数量 超过了选择器数量
        if ((!enabled) || active.incrementAndGet() >= maxSelectors) {
            if (enabled) active.decrementAndGet();
            // 返回null
            return null;
        }
        Selector s = null;
        try {
            // 从阻塞队列中返回一个 selector 对象
            s = selectors.size() > 0 ? selectors.poll() : null;
            if (s == null) {
                s = Selector.open();
            } else spare.decrementAndGet();

        } catch (NoSuchElementException x) {
            try {
                s = Selector.open();
            } catch (IOException iox) {
            }
        } finally {
            if (s == null) active.decrementAndGet();//we were unable to find a selector
        }
        return s;
    }


    /**
     * 将某个 selector 对象设置到阻塞队列中
     * @param s
     * @throws IOException
     */
    public void put(Selector s) throws IOException {
        // 如果是 共享某个selector 那么不需要做处理
        if (SHARED) return;
        // 减少活跃数量 当借出某个 selector 时 就要增加计数 反之减少计数
        if (enabled) active.decrementAndGet();
        // 当共享数量 小于 最大选择器数量 将选择器 设置到阻塞队列中
        if (enabled && (maxSpareSelectors == -1 || spare.get() < Math.min(maxSpareSelectors, maxSelectors))) {
            spare.incrementAndGet();
            selectors.offer(s);
        // 当设置的选择器 超过数量 那么就 关闭尝试加入的选择器
        } else s.close();
    }

    /**
     * 关闭 pool 对象
     * @throws IOException
     */
    public void close() throws IOException {
        enabled = false;
        Selector s;
        // 关闭阻塞队列中所有的 选择器对象
        while ((s = selectors.poll()) != null) s.close();
        spare.set(0);
        active.set(0);
        // 关闭 blockingSelector 内部的 poller 对象
        if (blockingSelector != null) {
            blockingSelector.close();
        }
        // 关闭 sharedSelector
        if (SHARED && getSharedSelector() != null) {
            getSharedSelector().close();
            SHARED_SELECTOR = null;
        }
    }

    /**
     * 当触发 endpoint.bind 后会开启选择池
     * @throws IOException
     */
    public void open() throws IOException {
        enabled = true;
        // 这里会初始化共享选择器对象
        getSharedSelector();
        if (SHARED) {
            blockingSelector = new NioBlockingSelector();
            blockingSelector.open(getSharedSelector());
        }

    }

    /**
     * Performs a write using the bytebuffer for data to be written and a
     * selector to block (if blocking is requested). If the
     * <code>selector</code> parameter is null, and blocking is requested then
     * it will perform a busy write that could take up a lot of CPU cycles.
     *
     * @param buf          The buffer containing the data, we will write as long as <code>(buf.hasRemaining()==true)</code>
     * @param socket       The socket to write data to
     * @param selector     The selector to use for blocking, if null then a busy write will be initiated
     * @param writeTimeout The timeout for this write operation in milliseconds, -1 means no timeout
     * @param block        <code>true</code> to perform a blocking write
     *                     otherwise a non-blocking write will be performed
     * @return int - returns the number of bytes written
     * @throws EOFException           if write returns -1
     * @throws SocketTimeoutException if the write times out
     * @throws IOException            if an IO Exception occurs in the underlying socket logic
     * 将 buf 中的数据写入到 NioChannel 中  如果从外部传入了 selector 那么就是 非共享模式
     */
    public int write(ByteBuffer buf, NioChannel socket, Selector selector,
                     long writeTimeout, boolean block) throws IOException {
        // 如果是在共享模式下 且开启阻塞模式 那么就会通过 读写栅栏实现 阻塞写入
        if (SHARED && block) {
            return blockingSelector.write(buf, socket, writeTimeout);
        }
        SelectionKey key = null;
        int written = 0;
        boolean timedout = false;
        int keycount = 1; //assume we can write
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            // 未超时 且 buf 中还有剩余数据
            while ((!timedout) && buf.hasRemaining()) {
                int cnt = 0;
                if (keycount > 0) { //only write if we were registered for a write
                    // 首先尝试直接往 jdk NioChannel 中写入数据
                    cnt = socket.write(buf); //write the data
                    if (cnt == -1) throw new EOFException();

                    written += cnt;
                    if (cnt > 0) {
                        time = System.currentTimeMillis(); //reset our timeout timer
                        continue; //we successfully wrote, try again without a selector
                    }
                    // 如果是以非阻塞方式写入 且 往nioChannel 写入失败了 那么直接退出循环 如果已经写入了部分数据 那么还是阻塞等待结果
                    if (cnt == 0 && (!block)) break; //don't block
                }
                // 如果外部传入了选择器对象
                if (selector != null) {
                    //register OP_WRITE to the selector
                    if (key == null) key = socket.getIOChannel().register(selector, SelectionKey.OP_WRITE);
                    else key.interestOps(SelectionKey.OP_WRITE);
                    if (writeTimeout == 0) {
                        timedout = buf.hasRemaining();
                    } else if (writeTimeout < 0) {
                        keycount = selector.select();
                    } else {
                        keycount = selector.select(writeTimeout);
                    }
                }
                if (writeTimeout > 0 && (selector == null || keycount == 0))
                    timedout = (System.currentTimeMillis() - time) >= writeTimeout;
            }//while
            if (timedout) throw new SocketTimeoutException();
        } finally {
            if (key != null) {
                key.cancel();
                if (selector != null) selector.selectNow();//removes the key from this selector
            }
        }
        return written;
    }

    /**
     * Performs a blocking read using the bytebuffer for data to be read and a selector to block.
     * If the <code>selector</code> parameter is null, then it will perform a busy read that could
     * take up a lot of CPU cycles.
     *
     * @param buf         ByteBuffer - the buffer containing the data, we will read as until we have read at least one byte or we timed out
     * @param socket      SocketChannel - the socket to write data to
     * @param selector    Selector - the selector to use for blocking, if null then a busy read will be initiated
     * @param readTimeout long - the timeout for this read operation in milliseconds, -1 means no timeout
     * @return int - returns the number of bytes read
     * @throws EOFException           if read returns -1
     * @throws SocketTimeoutException if the read times out
     * @throws IOException            if an IO Exception occurs in the underlying socket logic
     */
    public int read(ByteBuffer buf, NioChannel socket, Selector selector, long readTimeout) throws IOException {
        return read(buf, socket, selector, readTimeout, true);
    }

    /**
     * Performs a read using the bytebuffer for data to be read and a selector to register for events should
     * you have the block=true.
     * If the <code>selector</code> parameter is null, then it will perform a busy read that could
     * take up a lot of CPU cycles.
     *
     * @param buf         ByteBuffer - the buffer containing the data, we will read as until we have read at least one byte or we timed out
     * @param socket      SocketChannel - the socket to write data to
     * @param selector    Selector - the selector to use for blocking, if null then a busy read will be initiated
     * @param readTimeout long - the timeout for this read operation in milliseconds, -1 means no timeout
     * @param block       - true if you want to block until data becomes available or timeout time has been reached
     * @return int - returns the number of bytes read
     * @throws EOFException           if read returns -1
     * @throws SocketTimeoutException if the read times out
     * @throws IOException            if an IO Exception occurs in the underlying socket logic
     */
    public int read(ByteBuffer buf, NioChannel socket, Selector selector, long readTimeout, boolean block) throws IOException {
        // 如果是共享阻塞模式  使用 读栅栏 加 poller 的方式读取数据
        if (SHARED && block) {
            return blockingSelector.read(buf, socket, readTimeout);
        }
        // 下面的逻辑是处理非共享模式的socket 实际上就是通过 将事件注册到 selector 上 并轮询等待 当准备就绪时将数据写入到 buf中
        SelectionKey key = null;
        int read = 0;
        boolean timedout = false;
        int keycount = 1; //assume we can write
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            while ((!timedout)) {
                int cnt = 0;
                if (keycount > 0) { //only read if we were registered for a read
                    cnt = socket.read(buf);
                    if (cnt == -1) {
                        if (read == 0) {
                            read = -1;
                        }
                        break;
                    }
                    read += cnt;
                    if (cnt > 0) continue; //read some more
                    // 如果当前 jdk channel 无法写入 直接返回 避免阻塞
                    if (cnt == 0 && (read > 0 || (!block))) break; //we are done reading
                }
                if (selector != null) {//perform a blocking read
                    //register OP_WRITE to the selector
                    if (key == null) key = socket.getIOChannel().register(selector, SelectionKey.OP_READ);
                    else key.interestOps(SelectionKey.OP_READ);
                    if (readTimeout == 0) {
                        timedout = (read == 0);
                    } else if (readTimeout < 0) {
                        keycount = selector.select();
                    } else {
                        keycount = selector.select(readTimeout);
                    }
                }
                if (readTimeout > 0 && (selector == null || keycount == 0))
                    timedout = (System.currentTimeMillis() - time) >= readTimeout;
            }//while
            if (timedout) throw new SocketTimeoutException();
        } finally {
            // 当读取完成时 释放key
            if (key != null) {
                key.cancel();
                if (selector != null) selector.selectNow();//removes the key from this selector
            }
        }
        return read;
    }

    public void setMaxSelectors(int maxSelectors) {
        this.maxSelectors = maxSelectors;
    }

    public void setMaxSpareSelectors(int maxSpareSelectors) {
        this.maxSpareSelectors = maxSpareSelectors;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSharedSelectorTimeout(long sharedSelectorTimeout) {
        this.sharedSelectorTimeout = sharedSelectorTimeout;
    }

    public int getMaxSelectors() {
        return maxSelectors;
    }

    public int getMaxSpareSelectors() {
        return maxSpareSelectors;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getSharedSelectorTimeout() {
        return sharedSelectorTimeout;
    }

    public ConcurrentLinkedQueue<Selector> getSelectors() {
        return selectors;
    }

    public AtomicInteger getSpare() {
        return spare;
    }
}