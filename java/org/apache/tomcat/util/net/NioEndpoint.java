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
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 * <p>
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 * 基于 java5 nio 的 端点对象  还有一个改良版 nio2Endpoint  使用的socket 类型是 NioChannel
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    /**
     * 注册感兴趣的事件
     */
    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    /**
     * 使用该对象 维护 selector 内部还有一个 poller 对象 会通过轮询选择器
     */
    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     * 服务端socket 实际上就是 对应的 "端点"
     */
    private volatile ServerSocketChannel serverSock = null;

    /**
     * 用于 阻塞请求停止的线程
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     * 缓存 pollerEvent 对象 避免被GC 回收
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     * 保存 NioChannel 对象   该对象内部封装了 JDK channel
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     * 将属性设置到 endpoint 中
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            // 如果属性是 有关于 selectorPool 的 通过反射设置到 pool 中
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        } catch (Exception x) {
            log.error("Unable to set attribute \"" + name + "\" to \"" + value + "\"", x);
            return false;
        }
    }


    /**
     * Use System.inheritableChannel to obtain channel from stdin/stdout.
     * 是否使用 jvm 返回的 channel
     */
    private boolean useInheritedChannel = false;

    public void setUseInheritedChannel(boolean useInheritedChannel) {
        this.useInheritedChannel = useInheritedChannel;
    }

    public boolean getUseInheritedChannel() {
        return useInheritedChannel;
    }

    /**
     * Priority of the poller threads.
     * poller 对象对应的线程优先级
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;

    public void setPollerThreadPriority(int pollerThreadPriority) {
        this.pollerThreadPriority = pollerThreadPriority;
    }

    public int getPollerThreadPriority() {
        return pollerThreadPriority;
    }


    /**
     * Poller thread count.
     * poller 的线程数量
     */
    private int pollerThreadCount = Math.min(2, Runtime.getRuntime().availableProcessors());

    public void setPollerThreadCount(int pollerThreadCount) {
        this.pollerThreadCount = pollerThreadCount;
    }

    public int getPollerThreadCount() {
        return pollerThreadCount;
    }

    /**
     * selector 轮询等待事件超时时间
     */
    private long selectorTimeout = 1000;

    public void setSelectorTimeout(long timeout) {
        this.selectorTimeout = timeout;
    }

    public long getSelectorTimeout() {
        return this.selectorTimeout;
    }

    /**
     * The socket poller.
     * 内部存放一组 poller 对象
     */
    private Poller[] pollers = null;
    /**
     * 旋转次数 作为负载算法的 计数器
     */
    private AtomicInteger pollerRotater = new AtomicInteger(0);

    /**
     * Return an available poller in true round robin fashion.
     *
     * @return The next poller in sequence
     * 通过 轮询算法 返回某个 poller 对象
     */
    public Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    /**
     * Is deferAccept supported?
     * 是否支持 延迟accpet 为false
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     * for the next request to be received on the socket
     * 获取当前 保持活跃的连接数
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            // 将所有 poller 的 key 的总和 作为 活跃数
            for (int i = 0; i < pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     * 初始化 端点对象
     */
    @Override
    public void bind() throws Exception {

        // 如果没有 使用从 jvm 处获得的channel 那么就需要创建一个新的channel
        if (!getUseInheritedChannel()) {
            serverSock = ServerSocketChannel.open();
            // 将socket 设置到 prop 中
            socketProperties.setProperties(serverSock.socket());
            // 从server.xml 中读取 地址信息 并生成对应的 InetSocketAddress 对象
            InetSocketAddress addr = (getAddress() != null ? new InetSocketAddress(getAddress(), getPort()) : new InetSocketAddress(getPort()));
            // 将socket 绑定到目标地址 同时指定了最大连接数
            serverSock.socket().bind(addr, getAcceptCount());
        } else {
            // Retrieve the channel provided by the OS
            // 否则复用 OS 的 channel
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        }
        // 将模式修改成 阻塞模式
        serverSock.configureBlocking(true); //mimic APR behavior

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        // 也就是 stop 必须等待所有 poller 都暂停 才能真正执行停止逻辑
        setStopLatch(new CountDownLatch(pollerThreadCount));

        // Initialize SSL if needed   ssl 相关先不看
        initialiseSsl();

        // 开启内部 池化对象  注意如果 pool 对象内部没有 共享selector 那么 不会创建 BlockPoller
        selectorPool.open();
    }

    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     * 当启动 nio 端点时触发
     */
    @Override
    public void startInternal() throws Exception {

        // 修改成运行状态 以及 非暂停
        if (!running) {
            running = true;
            paused = false;

            // 根据 默认允许缓存的数量来初始化栈
            processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getProcessorCache());
            eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getEventCache());
            nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getBufferPool());

            // Create worker collection
            // 尝试创建线程池  默认使用 tomcat 内部定制的 线程池 当context关闭时 会回收所有线程 并拒绝新任务 直到一定延时后 重新创建线程
            if (getExecutor() == null) {
                createExecutor();
            }

            // 初始化连接栅栏  就是 当超过限定数量 就会被阻塞在AQS 的队列中
            initializeConnectionLatch();

            // Start poller threads
            // 根据线程数生成对应的 poller
            pollers = new Poller[getPollerThreadCount()];
            for (int i = 0; i < pollers.length; i++) {
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-" + i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }

            // 开启 acceptor 线程  内部会调用 createAcceptor()  方法 不同的 endpoint 实现 有不同的acceptor
            startAcceptorThreads();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     * 终止 endpoint
     */
    @Override
    public void stopInternal() {
        // 唤醒所有之前尝试连接的线程 并 清除 ConnectionLatch 引用
        releaseConnectionLatch();
        // 进入暂停状态 拒绝处理新的请求
        if (!paused) {
            // TODO 这里要 unlock 所有 acceptor 对象 暂时还不明确是在干嘛
            // 同时 pause 会被转发到 endpoint 内部的handler
            pause();
        }
        if (running) {
            running = false;
            // 这里为什么有调用了一次 unlock ???
            unlockAccept();
            // 终止所有的 poller 对象
            for (int i = 0; pollers != null && i < pollers.length; i++) {
                if (pollers[i] == null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                // 这里等待 stop的事件 略微大于 selectorTimeout
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            // 调用 executor.shutdownNow 关闭所有任务
            shutdownExecutor();
            // 清空相关引用 帮助 GC 进行回收
            eventCache.clear();
            nioChannels.clear();
            processorCache.clear();
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     * 关闭服务端套接字
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for " + new InetSocketAddress(getAddress(), getPort()));
        }
        // 如果当前还在运行状态 就停止endpoint
        if (running) {
            stop();
        }
        // 关闭套接字对象
        doCloseServerSocket();
        // 关闭ssl 先不看
        destroySsl();
        // 也是 ssl 相关的
        super.unbind();
        // 触发 handler.recycle() 方法
        if (getHandler() != null) {
            getHandler().recycle();
        }
        // 关闭pool 对象  就是关闭 pool内部维护的 selector 对象
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for " + new InetSocketAddress(getAddress(), getPort()));
        }
    }


    /**
     * 关闭 jdk.serverSocket
     *
     * @throws IOException
     */
    @Override
    protected void doCloseServerSocket() throws IOException {
        if (!getUseInheritedChannel() && serverSock != null) {
            // Close server socket
            serverSock.socket().close();
            serverSock.close();
        }
        serverSock = null;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * 获取写缓冲区的大小
     *
     * @return
     */
    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    /**
     * 初始化 acceptor 对象
     *
     * @return
     */
    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     * Process the specified connection.
     *
     * @param socket The socket channel
     * @return <code>true</code> if the socket was correctly configured
     * and processing may continue, <code>false</code> if the socket needs to be
     * close immediately
     * 使用 socketProp 对象 配置传入的channel
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            // 首先设置成 非阻塞模式    native 方法
            socket.configureBlocking(false);
            // 通过 channel 获取远端套接字 对象
            Socket sock = socket.socket();
            // 进行装配
            socketProperties.setProperties(sock);

            // 从缓存的栈结构中弹出一个channel
            NioChannel channel = nioChannels.pop();
            if (channel == null) {
                // 将 channel 对象包装成一个 bufferHandler
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),
                        socketProperties.getAppWriteBufSize(),
                        socketProperties.getDirectBuffer());
                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                // 这里选择复用之前的对象  那么 什么时候会需要用到这种缓存策略呢  在并发量超大的情况 避免针对每个请求不同的 创建回收对象
                channel.setIOChannel(socket);
                channel.reset();
            }
            // 通过负载策略获取到一个 poller 对象 并将channel 注册上去
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("", t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    // --------------------------------------------------- Acceptor Inner Class

    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     * endpoint 内部的 acceptor 对象  该对象用于接入tcp/ip连接 之后将 channel 绑定在 poller 上 监听相关事件
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                // 如果发现当前处在 暂停状态 就不断自旋 直到退出暂停状态
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                // 如果当前已经终止 则退出自旋
                if (!running) {
                    break;
                }
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    // 尝试获取一个 连接 如果达到最大数 那么会被latch 拦截  如果下面创建/初始化 socket 失败 则关闭连接 并唤醒被这里阻塞住的连接
                    countUpOrAwaitConnection();

                    SocketChannel socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket
                        // 通过socket 对象接收一个新的连接  在 非阻塞模式下 如果没有连接 会立即返回null
                        // 如果是阻塞模式 那么会阻塞直到接收到一个新的连接
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        // We didn't get a socket  唤醒下一个 尝试连接的线程
                        countDownConnection();
                        if (running) {
                            // Introduce delay if necessary  生成处理异常的时间间隔  在内部 时间每次都倍增  直到达到最大值
                            errorDelay = handleExceptionWithDelay(errorDelay);
                            // re-throw
                            throw ioe;
                        } else {
                            break;
                        }
                    }
                    // Successful accept, reset the error delay
                    // 当正常接收到 socket 时 才会进入到这里
                    errorDelay = 0;

                    // Configure the socket
                    if (running && !paused) {
                        // setSocketOptions() will hand the socket off to
                        // an appropriate processor if successful
                        // 通过 serverSocket 接受到的请求 会绑定到一个 poller 对象上 这里通过使用多个poller 以及 负载算法 分担压力
                        if (!setSocketOptions(socket)) {
                            closeSocket(socket);
                        }
                    } else {
                        // 如果当前 不是运行状态 或者 处在暂停状态 那么关闭socket
                        closeSocket(socket);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }

            // 当终止running 时 切换成 ended 状态
            state = AcceptorState.ENDED;
        }


        /**
         * 关闭 socketChannel 对象
         *
         * @param socket
         */
        private void closeSocket(SocketChannel socket) {
            // 减少当前连接数 同时 唤醒下个尝试连接的线程
            countDownConnection();
            try {
                // 调用 jdk底层api 进行关闭
                socket.socket().close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
            try {
                // 关闭管道对象
                socket.close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
        }
    }


    /**
     * 通过 socket 对象 和 event 封装成一个 processor  对象 处理逻辑在内部
     *
     * @param socketWrapper
     * @param event
     * @return
     */
    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }


    /**
     * 关闭 socket  比如某个短连接 那么在处理完后 就要关闭
     *
     * @param socket
     * @param key
     */
    private void close(NioChannel socket, SelectionKey key) {
        try {
            // 通过 该管道上关联的 poller 关闭 key 对象
            if (socket.getPoller().cancelledKey(key) != null) {
                // SocketWrapper (attachment) was removed from the
                // key - recycle the key. This can only happen once
                // per attempted closure so it is used to determine
                // whether or not to return the key to the cache.
                // We do NOT want to do this more than once - see BZ
                // 57340 / 57943.
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + socket + "] closed");
                }
                // 将包装对象设置到 nioChannel 中
                if (running && !paused) {
                    // 当没有成功 设置到缓存栈中 进行释放 (主要是针对内部的 buffer ， 如果使用的是堆外内存 则使用cleaner 进行内存释放)
                    if (!nioChannels.push(socket)) {
                        socket.free();
                    }
                }
            }
        } catch (Exception x) {
            log.error("", x);
        }
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     * PollerEvent, cacheable object for poller events to avoid GC
     * 外部针对 poller 的请求会被封装成 event 并维护到任务队列中 每当poller 轮询完一边selector 后就会处理队列中的任务
     */
    public static class PollerEvent implements Runnable {

        /**
         * 该事件是由 哪个 channel 发出的 NioChannel 是 tomcat 封装的 内部包含了 jdk niochannel
         */
        private NioChannel socket;
        /**
         * 感兴趣的事件 可能 多于当前  或者少于当前
         */
        private int interestOps;
        /**
         * 关联的 socket 对象
         */
        private NioSocketWrapper socketWrapper;

        public PollerEvent(NioChannel ch, NioSocketWrapper w, int intOps) {
            reset(ch, w, intOps);
        }

        public void reset(NioChannel ch, NioSocketWrapper w, int intOps) {
            socket = ch;
            interestOps = intOps;
            socketWrapper = w;
        }

        public void reset() {
            reset(null, null, 0);
        }

        /**
         * 执行任务的逻辑
         */
        @Override
        public void run() {
            // 如果当前 关注注册事件  (第一次都应该是这个事件)
            if (interestOps == OP_REGISTER) {
                try {
                    // 首次设置 读事件  wrapper 对象是用来绑定在key上
                    socket.getIOChannel().register(
                            socket.getPoller().getSelector(), SelectionKey.OP_READ, socketWrapper);
                } catch (Exception x) {
                    log.error(sm.getString("endpoint.nio.registerFail"), x);
                }
            } else {
                // 通过 channel 和selector 获取 对应的key 对象
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    // 如果key 已经消失了  代表本连接应该已经被关闭了
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        // 减少连接数 也就是tomcat 是可以设置最大连接数的??? 或者说这个 最大 指的是同一时间 每当 socket 处理完相关事件后就会释放连接(推测)
                        socket.socketWrapper.getEndpoint().countDownConnection();
                        // 标记本socket 对象已经被关闭
                        ((NioSocketWrapper) socket.socketWrapper).closed = true;
                    } else {
                        // 将感兴趣事件注册上去
                        final NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper != null) {
                            //we are registering the key to start with, reset the fairness counter.
                            // 全量更改 interestOps
                            int ops = key.interestOps() | interestOps;
                            socketWrapper.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            // 如果 socketWrapper 已经不在了 就关闭key
                            socket.getPoller().cancelledKey(key);
                        }
                    }
                } catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key);
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socketWrapper +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller class.
     * 该对象也是用于 轮询选择器的 ， 那么和 pool 内部的 blockPoller 对象有什么区别呢
     * 每个对象在创建时 内部会创建一个新的选择器对象 而 pool内部的 blockPoller 是使用 共享的selector
     */
    public class Poller implements Runnable {

        /**
         * 内部包含的选择器 对象
         */
        private Selector selector;
        /**
         * 通过 该队列 暂存其他线程 传入的任务(事件)
         */
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();

        /**
         * 当前是否被关闭
         */
        private volatile boolean close = false;
        /**
         * 下一个超时处理时间
         */
        private long nextExpiration = 0;//optimize expiration handling

        /**
         * 被唤醒的次数
         */
        private AtomicLong wakeupCounter = new AtomicLong(0);

        /**
         * 记录 key 的数量
         */
        private volatile int keyCount = 0;

        /**
         * 当 触发 start 时  会开启 acceptor(用于接收请求)  以及 poller (处理channel 感兴趣的事件)
         *
         * @throws IOException
         */
        public Poller() throws IOException {
            this.selector = Selector.open();
        }

        public int getKeyCount() {
            return keyCount;
        }

        public Selector getSelector() {
            return selector;
        }

        /**
         * Destroy the poller.
         * 销毁该对象
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        /**
         * 将某个事件 传入到 队列中
         *
         * @param event
         */
        private void addEvent(PollerEvent event) {
            events.offer(event);
            if (wakeupCounter.incrementAndGet() == 0) selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket      to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(final NioChannel socket, final int interestOps) {
            // 将 事件通过 event 对象包装起来  该对象 通过实现终结方法 来关闭socket
            PollerEvent r = eventCache.pop();
            if (r == null) r = new PollerEvent(socket, null, interestOps);
            else r.reset(socket, null, interestOps);
            addEvent(r);
            // 如果当前已经停止
            if (close) {
                NioEndpoint.NioSocketWrapper ka = (NioEndpoint.NioSocketWrapper) socket.getAttachment();
                // 使用一个stop 事件处理  处理逻辑 由具体子类实现
                processSocket(ka, SocketEvent.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         * <code>false</code> if queue was empty
         * 从event 中取出所有事件并执行
         */
        public boolean events() {
            boolean result = false;

            // 为了避免对象被频繁回收 这里的 思路跟  ringBuffer 一致
            PollerEvent pe = null;
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++) {
                result = true;
                try {
                    pe.run();
                    pe.reset();
                    if (running && !paused) {
                        eventCache.push(pe);
                    }
                } catch (Throwable x) {
                    log.error("", x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         * 当 acceptor 接收到一个新的 socket 对象后 会通过 socketProp 进行配置 之后包装成 NioChannel 对象 并注册到 poller 上
         *
         * @param socket The newly created socket
         */
        public void register(final NioChannel socket) {
            socket.setPoller(this);
            // 生成一个 wrapper 对象  内部包含获取 本地/远端 端口ip信息 以及 操作读写buffer
            NioSocketWrapper ka = new NioSocketWrapper(socket, NioEndpoint.this);
            // 将 包装对象设置到 socket 中
            socket.setSocketWrapper(ka);
            // 为 包装对象设置相关属性
            ka.setPoller(this);
            ka.setReadTimeout(getSocketProperties().getSoTimeout());
            ka.setWriteTimeout(getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            ka.setReadTimeout(getConnectionTimeout());
            ka.setWriteTimeout(getConnectionTimeout());
            PollerEvent r = eventCache.pop();
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            if (r == null) r = new PollerEvent(socket, ka, OP_REGISTER);
            else r.reset(socket, ka, OP_REGISTER);
            // 将任务添加到队列中
            addEvent(r);
        }

        /**
         * 关闭某个 key
         *
         * @param key
         * @return
         */
        public NioSocketWrapper cancelledKey(SelectionKey key) {
            NioSocketWrapper ka = null;
            try {
                if (key == null) return null;//nothing to do
                // 置空 key 关联的对象
                ka = (NioSocketWrapper) key.attach(null);
                if (ka != null) {
                    // If attachment is non-null then there may be a current
                    // connection with an associated processor.
                    // 释放 ka  TODO 内部怎么做 ???
                    getHandler().release(ka);
                }
                if (key.isValid()) key.cancel();
                // If it is available, close the NioChannel first which should
                // in turn close the underlying SocketChannel. The NioChannel
                // needs to be closed first, if available, to ensure that TLS
                // connections are shut down cleanly.
                if (ka != null) {
                    try {
                        // 关闭 jdk nioChannel 对象
                        ka.getSocket().close(true);
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.socketCloseFail"), e);
                        }
                    }
                }
                // The SocketChannel is also available via the SelectionKey. If
                // it hasn't been closed in the block above, close it now.
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    // 如果 包装对象上携带了 sendFile 数据 且 channel 还是打开状态 那么关闭channel
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                // 释放一个连接数
                if (ka != null) {
                    countDownConnection();
                    ka.closed = true;
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("", e);
            }
            return ka;
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         * poller 对象的核心逻辑就是 轮询选择器 并处理key 上感兴趣的事件
         */
        @Override
        public void run() {
            // Loop until destroy() is called   自旋直到整个 endpoint 被关闭
            while (true) {

                boolean hasEvents = false;

                try {
                    if (!close) {
                        // 处理任务队列中 所有任务   这里的逻辑跟 blockPoller 一样
                        hasEvents = events();
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            //if we are here, means we have other stuff to do
                            //do a non blocking select
                            keyCount = selector.selectNow();
                        } else {
                            keyCount = selector.select(selectorTimeout);
                        }
                        wakeupCounter.set(0);
                    }
                    // 当检测到被关闭时
                    if (close) {
                        // 处理当前堆积的任务
                        events();
                        // 触发一次超时 之后关闭选择器
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error("", x);
                    continue;
                }
                //either we timed out or we woke up, process events first
                // 每当 selector 超时唤醒 或者轮询到事件唤醒时  判断key 的数量 如果为0 代表本次没有获取到准备好的事件 那么就需要判断 任务队列中是否有任务
                if (keyCount == 0) hasEvents = (hasEvents | events());

                Iterator<SelectionKey> iterator =
                        keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                // 处理准备好的key
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    NioSocketWrapper attachment = (NioSocketWrapper) sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    if (attachment == null) {
                        iterator.remove();
                    } else {
                        // 处理获取到的事件
                        iterator.remove();
                        processKey(sk, attachment);
                    }
                }//while

                //process timeouts  每次select阻塞结束后 都会执行已经准备完成的事件
                // 之后 会遍历当前selector 上绑定的所有事件 如果发现某些事件已经超时了 那么就以异常方式触发
                timeout(keyCount, hasEvents);
            }//while

            // 当某个poller 退出自旋后 代表该poller 已经结束流程 当poller[] 中所有元素都退出自旋时 就可以 stop endpoint
            getStopLatch().countDown();
        }

        /**
         * 使用 socket 对象 处理key事件
         *
         * @param sk
         * @param attachment
         */
        protected void processKey(SelectionKey sk, NioSocketWrapper attachment) {
            try {
                // 如果当前应用已经终止 就关闭该key
                if (close) {
                    cancelledKey(sk);
                } else if (sk.isValid() && attachment != null) {
                    // 必须是 读事件或者写事件才处理
                    if (sk.isReadable() || sk.isWritable()) {
                        // 如果需要作为文件发送的数据不为空 处理发送文件逻辑
                        if (attachment.getSendfileData() != null) {
                            processSendfile(sk, attachment, false);
                        } else {
                            // 将已经准备完的op 解除
                            unreg(sk, attachment, sk.readyOps());
                            boolean closeSocket = false;
                            // Read goes before write  如果当前读事件准备完成
                            if (sk.isReadable()) {
                                // 处理失败 则关闭socket   dispatch 为 true 代表不占用io 线程 而是使用额外的线程池处理
                                if (!processSocket(attachment, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (!closeSocket && sk.isWritable()) {
                                if (!processSocket(attachment, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (closeSocket) {
                                cancelledKey(sk);
                            }
                        }
                    }
                } else {
                    //invalid key
                    cancelledKey(sk);
                }
            } catch (CancelledKeyException ckx) {
                cancelledKey(sk);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("", t);
            }
        }

        /**
         * 处理发送文件请求
         *
         * @param sk
         * @param socketWrapper
         * @param calledByProcessor
         * @return
         */
        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                                             boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                // 取消当前感兴趣的事件
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                // 如果 FileChannel 对象还没有初始化 使用 fileName 进行初始化
                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                            FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining() > 0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                    // 如果是 NioChannel 默认走这里
                } else {
                    // 将文件中的数据写入到 wc 中
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        // 更新最后写入时间
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                // 代表EOF
                if (sd.length <= 0 && sc.getOutboundRemaining() <= 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: " + sd.fileName);
                    }
                    // 将文件置空
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.  如果不是交由 processor 来处理 那么就在这里进行处理
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                            // 如果本次请求不需要保持长连接 那么直接关闭socket
                            case NONE: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Send file connection is being closed");
                                }
                                close(sc, sk);
                                break;
                            }
                            // 如果使用管道模式
                            case PIPELINED: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Connection is keep alive, processing pipe-lined data");
                                }
                                // 使用processor 来处理 如果处理失败  则关闭连接
                                if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                    close(sc, sk);
                                }
                                break;
                            }
                            // 保持长连接 注册 read事件 后 继续通过poller 等待准备完成事件
                            case OPEN: {
                                if (log.isDebugEnabled()) {
                                    log.debug("Connection is keep alive, registering back for OP_READ");
                                }
                                reg(sk, socketWrapper, SelectionKey.OP_READ);
                                break;
                            }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    // 如果 文件没有写完 要继续注册写事件  如果是在 processor 中处理 实际上就是添加一个pollerEvent 事件保存到 队列中 在poller的自旋中会进行处理
                    if (calledByProcessor) {
                        add(socketWrapper.getSocket(), SelectionKey.OP_WRITE);
                    } else {
                        reg(sk, socketWrapper, SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException x) {
                if (log.isDebugEnabled()) log.debug("Unable to complete sendfile request:", x);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error("", t);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            }
        }

        protected void unreg(SelectionKey sk, NioSocketWrapper attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk, attachment, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        /**
         * 每当poller 结束一次selector的阻塞后 就会判断读写是否超时 如果超时则以error方式处理socket  当发现 close 为true 时 会以 keyCount = 0 hasEvents = false 来触发该方法
         * @param keyCount  当前准备好的key 数量
         * @param hasEvents  events 中是否有事件
         */
        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            // 在 poller 的每次循环中 都会触发该方法
            // 如果下次允许处理超时时间大于0 且 本次准备好的事件大于0或者有待处理的event 且 当前时间小于下次允许处理超时时间 且未关闭 就忽略此次判断
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            int keycount = 0;
            try {
                // 遍历当前所有准备好的key 对象
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        // 获取key 绑定的socket
                        NioSocketWrapper ka = (NioSocketWrapper) key.attachment();
                        if (ka == null) {
                            // 如果key 已经被置空 那么就关闭该key 对象
                            cancelledKey(key); //we don't support any keys without attachments
                        } else if (close) {
                            // 如果已经被关闭那么 取消所有感兴趣事件
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            // 将 key与socket 对象封装成processor 对象 并处理相关逻辑
                            processKey(key, ka);
                            // 如果当前包含 读写事件
                        } else if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean isTimedOut = false;
                            // Check for read timeout
                            // 如果包含读事件 检测是否已经超时
                            if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - ka.getLastRead();
                                long timeout = ka.getReadTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            // Check for write timeout
                            // 检测写是否已经超时
                            if (!isTimedOut && (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - ka.getLastWrite();
                                long timeout = ka.getWriteTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            // 如果已经超时
                            if (isTimedOut) {
                                // 取消所有感兴趣事件
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate timeout calls
                                // 同时设置超时异常
                                ka.setError(new SocketTimeoutException());
                                // 如果发现读写超时了 以异常方式 处理 本次事件
                                if (!processSocket(ka, SocketEvent.ERROR, true)) {
                                    cancelledKey(key);
                                }
                            }
                        }
                    } catch (CancelledKeyException ckx) {
                        cancelledKey(key);
                    }
                }//for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));
            }

        }
    }

    // ---------------------------------------------------- Key Attachment Class

    /**
     * 该对象内部 封装了 读写操作 以及 获取 本机/远端 端口ip 信息的方法
     */
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        /**
         * 该对象内部包含一个 池化对象
         */
        private final NioSelectorPool pool;

        /**
         * 轮询器
         */
        private Poller poller = null;
        /**
         * 当前socket 感兴趣的事件
         */
        private int interestOps = 0;
        // 用于阻塞读写操作的锁
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        // 如果该字段不为空 那么当事件准备完成时 会触发 processSendFileData()
        private volatile SendfileData sendfileData = null;
        // 记录最后的读写时间
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;
        private volatile boolean closed = false;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            pool = endpoint.getSelectorPool();
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() {
            return poller;
        }

        public void setPoller(Poller poller) {
            this.poller = poller;
        }

        public int interestOps() {
            return interestOps;
        }

        public int interestOps(int ops) {
            this.interestOps = ops;
            return ops;
        }

        public CountDownLatch getReadLatch() {
            return readLatch;
        }

        public CountDownLatch getWriteLatch() {
            return writeLatch;
        }

        // 重置栅栏

        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if (latch == null || latch.getCount() == 0) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }

        public void resetReadLatch() {
            readLatch = resetLatch(readLatch);
        }

        public void resetWriteLatch() {
            writeLatch = resetLatch(writeLatch);
        }

        /**
         * 根据指定的门票数 创建栅栏对象
         * @param latch
         * @param cnt
         * @return
         */
        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if (latch == null || latch.getCount() == 0) {
                return new CountDownLatch(cnt);
            } else throw new IllegalStateException("Latch must be at count 0 or null.");
        }

        public void startReadLatch(int cnt) {
            readLatch = startLatch(readLatch, cnt);
        }

        public void startWriteLatch(int cnt) {
            writeLatch = startLatch(writeLatch, cnt);
        }

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if (latch == null) throw new IllegalStateException("Latch cannot be null");
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout, unit);
        }

        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(readLatch, timeout, unit);
        }

        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(writeLatch, timeout, unit);
        }

        /**
         * 设置sendfileData 对象
         * @param sf
         */
        public void setSendfileData(SendfileData sf) {
            this.sendfileData = sf;
        }

        public SendfileData getSendfileData() {
            return this.sendfileData;
        }

        public void updateLastWrite() {
            lastWrite = System.currentTimeMillis();
        }

        public long getLastWrite() {
            return lastWrite;
        }

        public void updateLastRead() {
            lastRead = System.currentTimeMillis();
        }

        public long getLastRead() {
            return lastRead;
        }


        /**
         * 当前是否已经完成准备读事件
         * @return
         * @throws IOException
         */
        @Override
        public boolean isReadyForRead() throws IOException {
            // 将 readBuffer 的状态变成读状态
            socketBufferHandler.configureReadBufferForRead();

            // 如果buffer 中有数据 就代表 读事件准备完成
            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            // 如果 读buffer中没有数据 那么轮询选择器 直到 缓冲区填充数据  如果是非阻塞模式 此时IO缓冲区中没有数据会立即返回
            fillReadBuffer(false);

            // 根据当前指针 判断是否有读取到数据
            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        /**
         * 根据是否阻塞的方式 尝试从 内核态 IO 缓冲区中读取数据
         * @param block  是否阻塞
         * @param b    存储数据的 数组
         * @param off   数组起始偏移量
         * @param len  尝试读取的长度
         * @return
         * @throws IOException
         */
        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            // 从 readBuffer 中 将数据转移到数组中
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.  代表readBuffer 此时没有数据 那么需要尝试去 IO 缓冲区拉取数据  这里会将 readBuffer 切换成 write模式
            nRead = fillReadBuffer(block);
            // 更新最后读取时间
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read  代表成功从内核态拉取到数据时 重新切换成read 模式
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                // 将数据读取到数组中
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }

        /**
         * 将 readBuffer 的数据转移到 to 中
         * @param block
         * @param to
         * @return
         * @throws IOException
         */
        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            // 直接从readBufffer 中读取数据
            int nRead = populateReadBuffer(to);
            // 成功读取 直接返回
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            // 代表 容量超过了 readBuffer 的大小
            if (to.remaining() >= limit) {
                // 缩小容量
                to.limit(to.position() + limit);
                // 从 内核态将数据直接转移到 readBuffer 中
                nRead = fillReadBuffer(block, to);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
                }
                // 更新读取时间
                updateLastRead();
            } else {
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
                }
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                // 成功填充数据到 readBuffer 中时 将数据从readBuffer 移动到to 中
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        public void close() throws IOException {
            getSocket().close();
        }


        @Override
        public boolean isClosed() {
            return closed;
        }


        /**
         * 填充 读buffer
         * @param block  是否以阻塞方式 填充
         * @return
         * @throws IOException
         */
        private int fillReadBuffer(boolean block) throws IOException {
            // 将buffer 切换成写模式
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }


        /**
         * 填充 readBuffer
         * @param block
         * @param to
         * @return
         * @throws IOException
         */
        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            // 获取 NioChannel 对象 该对象内部包含 jdk niochannel
            NioChannel channel = getSocket();
            // 如果是阻塞模式
            if (block) {
                Selector selector = null;
                try {
                    // 从池中获取一个 selector 对象
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    // 获取 socketWrapper 对象
                    NioEndpoint.NioSocketWrapper att = (NioEndpoint.NioSocketWrapper) channel
                            .getAttachment();
                    if (att == null) {
                        throw new IOException("Key must be cancelled.");
                    }
                    // 使用指定的选择器 以阻塞方式将数据读取到buffer中  也就对应到 往 BlockPoller 中增加一个写事件 并轮询选择器 直到准备完成 如果超时 则返回超时异常
                    nRead = pool.read(to, channel, selector, att.getReadTimeout());
                } finally {
                    // 将选择器归还
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                // 如果是非阻塞模式 直接进行读取 并返回读取到的数量
                nRead = channel.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }


        /**
         * 从 from 将数据写入到 jdk nioChannel
         * @param block Should the write be blocking or not?
         * @param from the ByteBuffer containing the data to be written
         *
         * @throws IOException
         */
        @Override
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            // 获取等待写入超时的时间  该值用来阻塞选择器
            long writeTimeout = getWriteTimeout();
            Selector selector = null;
            try {
                selector = pool.get();
            } catch (IOException x) {
                // Ignore
            }
            try {
                // 如果是共享selector 模式 会使用一个 blockPoller 轮询选择器  等待事件后进行处理 这期间会用一个latch对象 阻塞当前线程
                // 如果是非共享selector 模式 则会单独针对这个selector 进行阻塞  不过 IO 密集型事件是不能通过 增加selector数量来显著提高效率的
                pool.write(from, getSocket(), selector, writeTimeout, block);
                // 如果是阻塞模式下 那么此时应该已经将 from 的数据写入到 IO 缓冲区了
                if (block) {
                    // Make sure we are flushed
                    do {
                        // NioChannel flush 总是返回true
                        if (getSocket().flush(true, selector, writeTimeout)) {
                            break;
                        }
                    } while (true);
                }
                // 更新写入时间
                updateLastWrite();
            } finally {
                if (selector != null) {
                    pool.put(selector);
                }
            }
            // If there is data left in the buffer the socket will be registered for
            // write further up the stack. This is to ensure the socket is only
            // registered for write once as both container and user code can trigger
            // write registration.
        }


        @Override
        public void registerReadInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerRead", this));
            }
            // 将读事件注册到 poller 上
            getPoller().add(getSocket(), SelectionKey.OP_READ);
        }


        /**
         * 将写事件注册上去  那还要那个池干嘛???
         */
        @Override
        public void registerWriteInterest() {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.registerWrite", this));
            }
            getPoller().add(getSocket(), SelectionKey.OP_WRITE);
        }


        /**
         * 当selector轮询到事件时 如果发现存在 sendFileDataBase对象 那么先处理
         * @param filename
         * @param pos
         * @param length
         * @return
         */
        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        /**
         * 这里将 文件中的数据转移到 buf 中
         * @param sendfileData Data representing the file to send
         *
         * @return
         */
        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(
                    getSocket().getPoller().getSelector());
            // Might as well do the first write on this thread
            return getSocket().getPoller().processSendfile(key, this, true);
        }


        /**
         * 设置远端地址
         */
        @Override
        protected void populateRemoteAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateRemoteHost() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteHost = inetAddr.getHostName();
                if (remoteAddr == null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            remotePort = getSocket().getIOChannel().socket().getPort();
        }


        @Override
        protected void populateLocalName() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localName = inetAddr.getHostName();
            }
        }


        @Override
        protected void populateLocalAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateLocalPort() {
            localPort = getSocket().getIOChannel().socket().getLocalPort();
        }


        /**
         * {@inheritDoc}
         *
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLEngine sslEngine = ch.getSslEngine();
                if (sslEngine != null) {
                    SSLSession session = sslEngine.getSession();
                    return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
                }
            }
            return null;
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }


        /**
         * 设置 应用上下文buf
         * @param handler
         */
        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }
    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     * 该对象封装了 socket 以及被触发的事件
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {

        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        /**
         * 当该对象被触发时 调用下面的方法
         */
        @Override
        protected void doRun() {
            NioChannel socket = socketWrapper.getSocket();
            SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());

            try {
                int handshake = -1;

                try {
                    // 首先确保 key 存在
                    if (key != null) {
                        // 是否已经完成握手  NioChannel 默认已经完成
                        if (socket.isHandshakeComplete()) {
                            // No TLS handshaking required. Let the handler
                            // process this socket / event combination.
                            handshake = 0;
                        } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                                event == SocketEvent.ERROR) {
                            // Unable to complete the TLS handshake. Treat it as
                            // if the handshake failed.
                            handshake = -1;
                        } else {
                            handshake = socket.handshake(key.isReadable(), key.isWritable());
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            event = SocketEvent.OPEN_READ;
                        }
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake", x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                // 处理事件   handler 的默认实现就是 ConnectionHandler
                if (handshake == 0) {
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    if (event == null) {
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        state = getHandler().process(socketWrapper, event);
                    }
                    if (state == SocketState.CLOSED) {
                        close(socket, key);
                    }
                } else if (handshake == -1) {
                    getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
                    close(socket, key);
                } else if (handshake == SelectionKey.OP_READ) {
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE) {
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error("", t);
                socket.getPoller().cancelledKey(key);
            } finally {
                socketWrapper = null;
                event = null;
                //return to cache
                if (running && !paused) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class

    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
