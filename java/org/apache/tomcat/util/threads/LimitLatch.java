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
package org.apache.tomcat.util.threads;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Shared latch that allows the latch to be acquired a limited number of times
 * after which all subsequent requests to acquire the latch will be placed in a
 * FIFO queue until one of the shares is returned.
 *
 * 类似信号量  在tomcat 中用来控制 某个 endpoint 的最大连接数
 */
public class LimitLatch {

    private static final Log log = LogFactory.getLog(LimitLatch.class);

    private class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1L;

        public Sync() {
        }

        /**
         * 每次调用acquire 就会增加一次 count 用于记录当前有效的连接数
         * 当调用 release 时 会往下传播 挨个判断该方法是否允许释放
         * @param ignored
         * @return
         */
        @Override
        protected int tryAcquireShared(int ignored) {
            long newCount = count.incrementAndGet();
            // 当前尝试获取的多批锁 超过了限制时 获取锁失败
            if (!released && newCount > limit) {
                // Limit exceeded
                count.decrementAndGet();
                return -1;
            } else {
                // released == true 的情况 总是能成功获取锁
                return 1;
            }
        }

        @Override
        protected boolean tryReleaseShared(int arg) {
            count.decrementAndGet();
            return true;
        }
    }

    private final Sync sync;

    /**
     * 应该是调用该对象的线程数量
     */
    private final AtomicLong count;
    /**
     * 该阀门最多允许执行的线程数 超过的部分会被拦截在 aqs 队列中
     */
    private volatile long limit;
    /**
     * 是否释放被阀门拦截的所有线程
     */
    private volatile boolean released = false;

    /**
     * Instantiates a LimitLatch object with an initial limit.
     * @param limit - maximum number of concurrent acquisitions of this latch
     *              初始化阀门对象
     */
    public LimitLatch(long limit) {
        this.limit = limit;
        this.count = new AtomicLong(0);
        this.sync = new Sync();
    }

    /**
     * Returns the current count for the latch
     * @return the current count for latch
     * 获取当前阀门拦截的数量
     */
    public long getCount() {
        return count.get();
    }

    /**
     * Obtain the current limit.
     * @return the limit
     * 获取门票数量
     */
    public long getLimit() {
        return limit;
    }


    /**
     * Sets a new limit. If the limit is decreased there may be a period where
     * more shares of the latch are acquired than the limit. In this case no
     * more shares of the latch will be issued until sufficient shares have been
     * returned to reduce the number of acquired shares of the latch to below
     * the new limit. If the limit is increased, threads currently in the queue
     * may not be issued one of the newly available shares until the next
     * request is made for a latch.
     *
     * @param limit The new limit
     */
    public void setLimit(long limit) {
        this.limit = limit;
    }


    /**
     * Acquires a shared latch if one is available or waits for one if no shared
     * latch is current available.
     * @throws InterruptedException If the current thread is interrupted
     * 尝试获取共享锁
     */
    public void countUpOrAwait() throws InterruptedException {
        if (log.isDebugEnabled()) {
            log.debug("Counting up["+Thread.currentThread().getName()+"] latch="+getCount());
        }
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * Releases a shared latch, making it available for another thread to use.
     * @return the previous counter value
     */
    public long countDown() {
        sync.releaseShared(0);
        long result = getCount();
        if (log.isDebugEnabled()) {
            log.debug("Counting down["+Thread.currentThread().getName()+"] latch="+result);
        }
        return result;
    }

    /**
     * Releases all waiting threads and causes the {@link #limit} to be ignored
     * until {@link #reset()} is called.
     * @return <code>true</code> if release was done
     * 代表释放之前被拦截住的所有线程
     */
    public boolean releaseAll() {
        released = true;
        return sync.releaseShared(0);
    }

    /**
     * Resets the latch and initializes the shared acquisition counter to zero.
     * @see #releaseAll()
     */
    public void reset() {
        this.count.set(0);
        released = false;
    }

    /**
     * Returns <code>true</code> if there is at least one thread waiting to
     * acquire the shared lock, otherwise returns <code>false</code>.
     * @return <code>true</code> if threads are waiting
     */
    public boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * Provide access to the list of threads waiting to acquire this limited
     * shared latch.
     * @return a collection of threads
     */
    public Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }
}
