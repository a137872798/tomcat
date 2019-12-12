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
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.NioEndpoint.NioSocketWrapper;

/**
 * 内部包含一个 poller 对象 用于轮询selector 上注册的 key  该对象可能会存在于一个 pool 中
 */
public class NioBlockingSelector {

    private static final Log log = LogFactory.getLog(NioBlockingSelector.class);

    /**
     * 用于记录当前线程序号
     */
    private static AtomicInteger threadCounter = new AtomicInteger(0);

    /**
     * SynchronizedStack 是基于 synchronized 方法封装的数组对象
     * 内部存放了一组 selectionKey 对象
     */
    private final SynchronizedStack<KeyReference> keyReferenceStack =
            new SynchronizedStack<>();

    /**
     * 该选择器是被共享的
     */
    protected Selector sharedSelector;

    /**
     * 该对象负责 不断从 selector 中轮询准备好的key 对象
     */
    protected BlockPoller poller;

    public NioBlockingSelector() {

    }

    /**
     * 开启某个选择器对象
     * @param selector
     */
    public void open(Selector selector) {
        sharedSelector = selector;
        poller = new BlockPoller();
        poller.selector = sharedSelector;
        poller.setDaemon(true);
        poller.setName("NioBlockingSelector.BlockPoller-" + (threadCounter.getAndIncrement()));
        // 启动轮询对象
        poller.start();
    }

    /**
     * 关闭轮询对象
     */
    public void close() {
        if (poller != null) {
            poller.disable();
            // 设置该状态后 会将当前线程标记成 被打断的 那么 如果该线程就是 往 JDK channel 中写入数据的channel 如果通过 NioChannel 对象 调用.write() 方法 就会拒绝写入 并抛出异常
            poller.interrupt();
            poller = null;
        }
    }

    /**
     * Performs a blocking write using the bytebuffer for data to be written
     * If the <code>selector</code> parameter is null, then it will perform a busy write that could
     * take up a lot of CPU cycles.
     *
     * @param buf          ByteBuffer - the buffer containing the data, we will write as long as <code>(buf.hasRemaining()==true)</code>
     * @param socket       SocketChannel - the socket to write data to
     * @param writeTimeout long - the timeout for this write operation in milliseconds, -1 means no timeout
     * @return int - returns the number of bytes written
     * @throws EOFException           if write returns -1
     * @throws SocketTimeoutException if the write times out
     * @throws IOException            if an IO Exception occurs in the underlying socket logic
     *
     * 将buf 中的数据 以一组 阻塞的方式写入到channel 中
     */
    public int write(ByteBuffer buf, NioChannel socket, long writeTimeout)
            throws IOException {
        // 获取 socket 在 该selector 上的key 对象
        SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
        if (key == null) throw new IOException("Key no longer registered");
        // 从维护 key 的栈结构中弹出对象 如果不存在的话 新建一个 reference 对象  使用栈结构是为了避免该对象被 重复创建
        KeyReference reference = keyReferenceStack.pop();
        if (reference == null) {
            reference = new KeyReference();
        }
        // 获取 key 绑定的 socket 对象
        NioSocketWrapper att = (NioSocketWrapper) key.attachment();
        int written = 0;
        // 设置等待超时时间
        boolean timedout = false;
        int keycount = 1; //assume we can write
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            // 当发现还没有超时 且 buf 中还有剩余数据
            while ((!timedout) && buf.hasRemaining()) {
                if (keycount > 0) { //only write if we were registered for a write
                    // 将buffer 的数据写入到 jdk channel 中
                    int cnt = socket.write(buf); //write the data
                    if (cnt == -1)
                        throw new EOFException();
                    written += cnt;
                    // 写入超过0 应该就是代表当前准备好了写事件 那么立即进入下次循环
                    if (cnt > 0) {
                        time = System.currentTimeMillis(); //reset our timeout timer
                        continue; //we successfully wrote, try again without a selector
                    }
                }
                // 当buffer 中的数据都写完后  或者当前没有准备好写入事件 那么阻塞当前线程 等待超时
                try {
                    // 想要通过栅栏对象来实现阻塞功能
                    // 如果栅栏没有设置 或者 计数为0 那么新建一个 门票数为1的栅栏  (因为在poller 的 阻塞选择逻辑中会解除socket 对应的栅栏门票)
                    if (att.getWriteLatch() == null || att.getWriteLatch().getCount() == 0) att.startWriteLatch(1);
                    // 往 poller 中加入一个写事件
                    poller.add(att, SelectionKey.OP_WRITE, reference);
                    // 阻塞等待栅栏释放门票
                    if (writeTimeout < 0) {
                        att.awaitWriteLatch(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                    } else {
                        att.awaitWriteLatch(writeTimeout, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException ignore) {
                    // Ignore
                }
                // 这里是阻塞结束 但是不能确定是超时了 还是事件准备完成了 所以需要通过门票数量来判断
                if (att.getWriteLatch() != null && att.getWriteLatch().getCount() > 0) {
                    //we got interrupted, but we haven't received notification from the poller.
                    keycount = 0;
                } else {
                    //latch countdown has happened
                    keycount = 1;
                    att.resetWriteLatch();
                }

                // keycount = 1 代表写入事件准备完成 就不需要判断是否超时  可能会存在只能写入部分数据的情况 比如一开始只能写入一部分 写入channel返回0 然后开始阻塞写入 直到超时
                // 那么抛出异常 不过此时已经有部分数据写入到channel 中了
                if (writeTimeout > 0 && (keycount == 0))
                    timedout = (System.currentTimeMillis() - time) >= writeTimeout;
            } //while
            if (timedout)
                throw new SocketTimeoutException();
        } finally {
            // 当写入完成时 将该事件从key 中移除  如果key 只关注write 事件 那么在移除后也会关闭key
            poller.remove(att, SelectionKey.OP_WRITE);
            // 如果超时的情况 就将key关闭
            if (timedout && reference.key != null) {
                poller.cancelKey(reference.key);
            }
            reference.key = null;
            // 避免 reference 被回收 所以保存在一个 栈结构中
            keyReferenceStack.push(reference);
        }
        return written;
    }

    /**
     * Performs a blocking read using the bytebuffer for data to be read
     * If the <code>selector</code> parameter is null, then it will perform a busy read that could
     * take up a lot of CPU cycles.
     *
     * @param buf         ByteBuffer - the buffer containing the data, we will read as until we have read at least one byte or we timed out
     * @param socket      SocketChannel - the socket to write data to
     * @param readTimeout long - the timeout for this read operation in milliseconds, -1 means no timeout
     * @return int - returns the number of bytes read
     * @throws EOFException           if read returns -1
     * @throws SocketTimeoutException if the read times out
     * @throws IOException            if an IO Exception occurs in the underlying socket logic
     * 外部调用读事件 同样通过一个栅栏对象来阻塞  当poller 通过轮询selector 发现 读事件可用时 唤醒对应key 绑定的 栅栏对象并进行唤醒 就可以正常的执行读事件 在规定时间内没有完成
     * 则抛出异常
     */
    public int read(ByteBuffer buf, NioChannel socket, long readTimeout) throws IOException {
        // 获取channel 上关联的key 如果还没有注册就抛出异常
        SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
        if (key == null) throw new IOException("Key no longer registered");
        // 同样保存在栈结构中是为了 强引用 避免该对象被反复创建
        KeyReference reference = keyReferenceStack.pop();
        if (reference == null) {
            reference = new KeyReference();
        }
        // 获取关联的socket 对象 该对象内部维护了 读写栅栏
        NioSocketWrapper att = (NioSocketWrapper) key.attachment();
        int read = 0;
        boolean timedout = false;
        int keycount = 1; //assume we can read
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            while (!timedout) {
                // 首先尝试能否直接读取
                if (keycount > 0) { //only read if we were registered for a read
                    read = socket.read(buf);
                    // 读取成功直接退出循环
                    if (read != 0) {
                        break;
                    }
                }
                try {
                    if (att.getReadLatch() == null || att.getReadLatch().getCount() == 0) att.startReadLatch(1);
                    // 将一个读事件 设置到selector 上进行监听
                    poller.add(att, SelectionKey.OP_READ, reference);
                    if (readTimeout < 0) {
                        att.awaitReadLatch(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                    } else {
                        att.awaitReadLatch(readTimeout, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException ignore) {
                    // Ignore
                }
                if (att.getReadLatch() != null && att.getReadLatch().getCount() > 0) {
                    //we got interrupted, but we haven't received notification from the poller.
                    keycount = 0;
                } else {
                    //latch countdown has happened
                    keycount = 1;
                    att.resetReadLatch();
                }
                if (readTimeout >= 0 && (keycount == 0))
                    timedout = (System.currentTimeMillis() - time) >= readTimeout;
            } //while
            if (timedout)
                throw new SocketTimeoutException();
        } finally {
            // 当读取事件完成后 从 selector 上注销
            poller.remove(att, SelectionKey.OP_READ);
            if (timedout && reference.key != null) {
                poller.cancelKey(reference.key);
            }
            reference.key = null;
            keyReferenceStack.push(reference);
        }
        return read;
    }


    /**
     * blockPoller 本身是一个线程对象
     */
    protected static class BlockPoller extends Thread {
        /**
         * 该线程是否应该继续运行 通过对外开放该属性的入口 来做到关闭自旋任务
         */
        protected volatile boolean run = true;
        /**
         * poller 对象内部 轮询的 选择器
         */
        protected Selector selector = null;
        /**
         * 一个FIFO 队列   因为 selector 对象本身是阻塞的 所以想到用一个任务队列来解耦
         */
        protected final SynchronizedQueue<Runnable> events = new SynchronizedQueue<>();

        /**
         * 首先 立即唤醒selector 对象 解除阻塞状态 同时进入下次自旋时发现run == false 就可以正常退出自旋 回收线程
         */
        public void disable() {
            run = false;
            selector.wakeup();
        }

        /**
         * 从外部强制唤醒 selector 的次数
         */
        protected final AtomicInteger wakeupCounter = new AtomicInteger(0);

        /**
         * 将某个 selectionKey 包装成 关闭对象并保存到队列中
         * 每当某个 channel 往 selector 注册感兴趣事件时  会生成一个 selectionKey 对象 用于关联 channel 与selection
         *
         * @param key
         */
        public void cancelKey(final SelectionKey key) {
            Runnable r = new RunnableCancel(key);
            events.offer(r);
            wakeup();
        }

        public void wakeup() {
            // 只有从 -1 -> 0 才唤醒选择器  为了避免频繁调用 wakeup
            if (wakeupCounter.addAndGet(1) == 0) selector.wakeup();
        }

        /**
         * 关闭某个 selectorKey
         *
         * @param sk  被关闭的目标key
         * @param key socket 包装对象
         * @param ops 要取消的事件
         */
        public void cancel(SelectionKey sk, NioSocketWrapper key, int ops) {
            if (sk != null) {
                // 被取消的键 当selector 下一次找到它时 进行惰性移除
                sk.cancel();
                // 将关联属性也置空
                sk.attach(null);
                // 如果取消了某个 写入事件 而刚好有线程正堵塞准备写入 那么强制唤醒线程  这样 调用write() 的方法会进入下次循环 不过添加 add事件时
                // 发现 sk.isValid 为true 就无法正常注册事件了  但是还是不断在循环中 TODO 这是不是有问题???
                if (SelectionKey.OP_WRITE == (ops & SelectionKey.OP_WRITE)) countDown(key.getWriteLatch());
                if (SelectionKey.OP_READ == (ops & SelectionKey.OP_READ)) countDown(key.getReadLatch());
            }
        }

        /**
         * 为当前selector 增加一个 selectionKey
         *
         * @param key
         * @param ops 要绑定在channel 上的感兴趣事件
         * @param ref 该引用对象内部维护一个 selectionKey
         */
        public void add(final NioSocketWrapper key, final int ops, final KeyReference ref) {
            // socket 为空 不进行处理
            if (key == null) return;
            // 获取用于生成 selectionKey 的 socket 对象
            // NioChannel 是 tomcat 封装的
            NioChannel nch = key.getSocket();
            // 获取 JDK NioChannel
            final SocketChannel ch = nch.getIOChannel();
            if (ch == null) return;

            // 将一个注册channel的任务添加到队列中
            Runnable r = new RunnableAdd(ch, key, ops, ref);
            events.offer(r);
            // 尝试唤醒 selector
            wakeup();
        }

        /**
         * 针对 channel 对应的selectionKey 移除某些感兴趣的事件
         *
         * @param key
         * @param ops
         */
        public void remove(final NioSocketWrapper key, final int ops) {
            if (key == null) return;
            NioChannel nch = key.getSocket();
            final SocketChannel ch = nch.getIOChannel();
            if (ch == null) return;

            Runnable r = new RunnableRemove(ch, key, ops);
            events.offer(r);
            wakeup();
        }

        /**
         * 强制执行当前任务队列中所有的任务
         *
         * @return
         */
        public boolean events() {
            Runnable r = null;

            /* We only poll and run the runnable events when we start this
             * method. Further events added to the queue later will be delayed
             * to the next execution of this method.
             *
             * We do in this way, because running event from the events queue
             * may lead the working thread to add more events to the queue (for
             * example, the worker thread may add another RunnableAdd event when
             * waken up by a previous RunnableAdd event who got an invalid
             * SelectionKey). Trying to consume all the events in an increasing
             * queue till it's empty, will make the loop hard to be terminated,
             * which will kill a lot of time, and greatly affect performance of
             * the poller loop.
             */
            int size = events.size();
            for (int i = 0; i < size && (r = events.poll()) != null; i++) {
                r.run();
            }

            return (size > 0);
        }

        /**
         * poller 监听选择器逻辑
         */
        @Override
        public void run() {
            // 配合 run 变量起到安全退出任务的作用
            while (run) {
                try {
                    // 每次从selector 阻塞状态解除的时候 消耗一定时间来执行cpu 任务
                    events();
                    int keyCount = 0;
                    try {
                        // 在执行events 时 如果从外部线程触发过 wakeup 方法 那么立即唤醒选择器  因为此时可能刚好又提交了某个任务
                        // 这是为了避免某个 任务刚好赶在轮询选择器节点前 却不得不等待 所以选择取消阻塞 先执行任务
                        int i = wakeupCounter.get();
                        if (i > 0)
                            keyCount = selector.selectNow();
                        else {
                            // 这里将 计数器设置为-1  如果外部添加了任务 并调用wakeup 那么 从-1 变成0的时候会唤醒选择器
                            wakeupCounter.set(-1);
                            // 监听选择器感兴趣事件
                            keyCount = selector.select(1000);
                        }
                        // 标记成0 就不会调用 selector.wakeup() 了
                        wakeupCounter.set(0);
                        if (!run) break;
                    } catch (NullPointerException x) {
                        //sun bug 5076772 on windows JDK 1.5
                        if (selector == null) throw x;
                        if (log.isDebugEnabled())
                            log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5", x);
                        continue;
                    } catch (CancelledKeyException x) {
                        //sun bug 5076772 on windows JDK 1.5
                        if (log.isDebugEnabled())
                            log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5", x);
                        continue;
                    } catch (Throwable x) {
                        ExceptionUtils.handleThrowable(x);
                        log.error("", x);
                        continue;
                    }

                    // 拿到 本次所有感兴趣事件
                    Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;

                    // Walk through the collection of ready keys and dispatch
                    // any active event.
                    // 遍历处理本次获取到的所有 感兴趣事件
                    while (run && iterator != null && iterator.hasNext()) {
                        SelectionKey sk = iterator.next();
                        NioSocketWrapper attachment = (NioSocketWrapper) sk.attachment();
                        try {
                            iterator.remove();
                            // 将感兴趣事件修改成 未准备完成的其他事件 避免 某些已经在处理的事件又被监听
                            // 只有在 设置 RemoveEvent 时 才真正将某个key 从selector 上移除
                            sk.interestOps(sk.interestOps() & (~sk.readyOps()));
                            // 如果本次获取到了读事件  处理读相关的栅栏
                            if (sk.isReadable()) {
                                countDown(attachment.getReadLatch());
                            }
                            // 如果本次获取到写事件 处理写相关的栅栏
                            if (sk.isWritable()) {
                                countDown(attachment.getWriteLatch());
                            }
                        } catch (CancelledKeyException ckx) {
                            sk.cancel();
                            countDown(attachment.getReadLatch());
                            countDown(attachment.getWriteLatch());
                        }
                    }//while
                } catch (Throwable t) {
                    log.error("", t);
                }
            }

            // 当 poller 停止运行时  清除当前所有事件对象
            events.clear();
            // If using a shared selector, the NioSelectorPool will also try and
            // close the selector. Try and avoid the ClosedSelectorException
            // although because multiple threads are involved there is always
            // the possibility of an Exception here.
            // 如果选择器是被多线程共享的  NioEndpoint 内部包含一个NioSelectorPool 也就是会通过多个线程共同访问 selector 对象
            // 那么 如果某个线程在 close 中失败了 这里会继续判断 并尝试进行关闭
            if (selector.isOpen()) {
                try {
                    // Cancels all remaining keys
                    selector.selectNow();
                } catch (Exception ignore) {
                    if (log.isDebugEnabled()) log.debug("", ignore);
                }
            }
            try {
                selector.close();
            } catch (Exception ignore) {
                if (log.isDebugEnabled()) log.debug("", ignore);
            }
        }

        public void countDown(CountDownLatch latch) {
            if (latch == null) return;
            latch.countDown();
        }


        /**
         * 将某个channel 绑定到 selector 上
         */
        private class RunnableAdd implements Runnable {

            /**
             * JDK channel
             */
            private final SocketChannel ch;
            /**
             * tomcat 中socket 包装对象
             */
            private final NioSocketWrapper key;
            private final int ops;
            /**
             * 生成的 selectionKey 对象要设置到 ref 中
             */
            private final KeyReference ref;

            public RunnableAdd(SocketChannel ch, NioSocketWrapper key, int ops, KeyReference ref) {
                this.ch = ch;
                this.key = key;
                this.ops = ops;
                this.ref = ref;
            }

            @Override
            public void run() {
                // 将channel 绑定到 selector 上 并返回一个 key 对象
                SelectionKey sk = ch.keyFor(selector);
                try {
                    if (sk == null) {
                        // 注册感兴趣事件  同时将 key 作为 attach 绑定到ch 上
                        sk = ch.register(selector, ops, key);
                        ref.key = sk;
                        // 如果channel 已经无效 就关闭key 对象 同时注销对应的事件
                    } else if (!sk.isValid()) {
                        cancel(sk, key, ops);
                    } else {
                        // 如果 已经存在 key那么 更新感兴趣事件
                        sk.interestOps(sk.interestOps() | ops);
                    }
                } catch (CancelledKeyException cx) {
                    cancel(sk, key, ops);
                } catch (ClosedChannelException cx) {
                    cancel(null, key, ops);
                }
            }
        }


        /**
         * 对应将某个 key 从selector 中移除的任务
         */
        private class RunnableRemove implements Runnable {

            /**
             * JDK channel
             */
            private final SocketChannel ch;
            private final NioSocketWrapper key;
            private final int ops;

            public RunnableRemove(SocketChannel ch, NioSocketWrapper key, int ops) {
                this.ch = ch;
                this.key = key;
                this.ops = ops;
            }

            @Override
            public void run() {
                SelectionKey sk = ch.keyFor(selector);
                try {
                    // 如果 key 还没有注册 那么先唤醒 栅栏 同样可能存在 无限循环的情况
                    if (sk == null) {
                        if (SelectionKey.OP_WRITE == (ops & SelectionKey.OP_WRITE)) countDown(key.getWriteLatch());
                        if (SelectionKey.OP_READ == (ops & SelectionKey.OP_READ)) countDown(key.getReadLatch());
                    } else {
                        // 如果还是有效的情况  注销感兴趣事件
                        if (sk.isValid()) {
                            sk.interestOps(sk.interestOps() & (~ops));
                            // 打开栅栏
                            if (SelectionKey.OP_WRITE == (ops & SelectionKey.OP_WRITE)) countDown(key.getWriteLatch());
                            if (SelectionKey.OP_READ == (ops & SelectionKey.OP_READ)) countDown(key.getReadLatch());
                            // 如果该channel 上已经没有感兴趣事件了 那么就可以关闭
                            if (sk.interestOps() == 0) {
                                sk.cancel();
                                sk.attach(null);
                            }
                        } else {
                            sk.cancel();
                            sk.attach(null);
                        }
                    }
                } catch (CancelledKeyException cx) {
                    if (sk != null) {
                        sk.cancel();
                        sk.attach(null);
                    }
                }
            }

        }


        /**
         * 关闭某个 selectionKey 的任务
         */
        public static class RunnableCancel implements Runnable {

            private final SelectionKey key;

            public RunnableCancel(SelectionKey key) {
                this.key = key;
            }

            @Override
            public void run() {
                key.cancel();
            }
        }
    }


    /**
     * 每个 selectionKey 代表被注册在选择器上的一个事件
     */
    public static class KeyReference {
        SelectionKey key = null;

        // 实现了终结方法 在对象被回收时 关闭key
        @Override
        public void finalize() {
            if (key != null && key.isValid()) {
                log.warn("Possible key leak, cancelling key in the finalizer.");
                try {
                    key.cancel();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
