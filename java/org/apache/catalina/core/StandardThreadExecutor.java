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

package org.apache.catalina.core;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.catalina.Executor;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.tomcat.util.threads.ResizableExecutor;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

/**
 * tomcat 推荐使用的线程池  内部组合了 TaskQueue TaskThread  和 tomcat 增强的 ThreadPoolExecutor 这样在 context 被关闭时会关闭当前线程 重新创建一批线程，这样旧线程维护的强引用对象就能得到释放
 */
public class StandardThreadExecutor extends LifecycleMBeanBase
        implements Executor, ResizableExecutor {

    // ---------------------------------------------- Properties
    /**
     * Default thread priority  内部线程默认的优先级
     */
    protected int threadPriority = Thread.NORM_PRIORITY;

    /**
     * Run threads in daemon or non-daemon state
     * 创建的线程默认是守护线程
     */
    protected boolean daemon = true;

    /**
     * Default name prefix for the thread name  线程对应的前缀
     */
    protected String namePrefix = "tomcat-exec-";

    // 如果是作为一个io密集型的任务 线程数确实有点多了

    /**
     * max number of threads  默认的最大线程数为200
     */
    protected int maxThreads = 200;

    /**
     * min number of threads   最小线程数为25
     */
    protected int minSpareThreads = 25;

    /**
     * idle time in milliseconds  最大等待时间为 1分钟
     */
    protected int maxIdleTime = 60000;

    /**
     * The executor we use for this component  被包装的线程池对象 内部有特殊的任务队列和 线程(当context发生切换时自动关闭线程并创建新线程)
     */
    protected ThreadPoolExecutor executor = null;

    /**
     * the name of this thread pool   线程池的名称
     */
    protected String name;

    /**
     * prestart threads?  是否在预启动时创建线程?
     */
    protected boolean prestartminSpareThreads = false;

    /**
     * The maximum number of elements that can queue up before we reject them  阻塞队列长度
     */
    protected int maxQueueSize = Integer.MAX_VALUE;

    /**
     * After a context is stopped, threads in the pool are renewed. To avoid
     * renewing all threads at the same time, this delay is observed between 2
     * threads being renewed.    2次线程更新间的时间间隔
     */
    protected long threadRenewalDelay =
        org.apache.tomcat.util.threads.Constants.DEFAULT_THREAD_RENEWAL_DELAY;

    /**
     * 任务队列
     */
    private TaskQueue taskqueue = null;
    // ---------------------------------------------- Constructors
    public StandardThreadExecutor() {
        //empty constructor for the digester
    }


    // ---------------------------------------------- Public Methods

    /**
     * 当触发service.init() 时 会触发该方法  实际上在init时 没有做任何初始化操作
     * @throws LifecycleException
     */
    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal();
    }


    /**
     * Start the component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     *  真正启动 service 时  开始创建线程池
     */
    @Override
    protected void startInternal() throws LifecycleException {

        // 创建无界的任务队列
        taskqueue = new TaskQueue(maxQueueSize);
        // 创建对应的线程工厂
        TaskThreadFactory tf = new TaskThreadFactory(namePrefix,daemon,getThreadPriority());
        // 使用这些参数生成 tomcat 定制的线程池
        executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), maxIdleTime, TimeUnit.MILLISECONDS,taskqueue, tf);
        // 设置终止线程的间隔时间
        executor.setThreadRenewalDelay(threadRenewalDelay);

        // 实际上在 初始化 ThreadPoolExecutor 时 coreThread 就已经创建完成了 这里没什么意义吧
        if (prestartminSpareThreads) {
            executor.prestartAllCoreThreads();
        }

        // 建立 任务队列 于 线程池的关联关系
        taskqueue.setParent(executor);

        // 标记当前状态为 启动中
        setState(LifecycleState.STARTING);
    }


    /**
     * Stop the component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that needs to be reported
     *  当终止线程池时
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        setState(LifecycleState.STOPPING);
        if ( executor != null ) executor.shutdownNow();
        executor = null;
        taskqueue = null;
    }


    @Override
    protected void destroyInternal() throws LifecycleException {
        super.destroyInternal();
    }


    @Override
    public void execute(Runnable command, long timeout, TimeUnit unit) {
        if ( executor != null ) {
            executor.execute(command,timeout,unit);
        } else {
            throw new IllegalStateException("StandardThreadExecutor not started.");
        }
    }


    @Override
    public void execute(Runnable command) {
        if ( executor != null ) {
            try {
                executor.execute(command);
            } catch (RejectedExecutionException rx) {
                //there could have been contention around the queue
                if ( !( (TaskQueue) executor.getQueue()).force(command) ) throw new RejectedExecutionException("Work queue full.");
            }
        } else throw new IllegalStateException("StandardThreadPool not started.");
    }

    public void contextStopping() {
        if (executor != null) {
            executor.contextStopping();
        }
    }

    public int getThreadPriority() {
        return threadPriority;
    }

    public boolean isDaemon() {

        return daemon;
    }

    public String getNamePrefix() {
        return namePrefix;
    }

    public int getMaxIdleTime() {
        return maxIdleTime;
    }

    @Override
    public int getMaxThreads() {
        return maxThreads;
    }

    public int getMinSpareThreads() {
        return minSpareThreads;
    }

    @Override
    public String getName() {
        return name;
    }

    public boolean isPrestartminSpareThreads() {

        return prestartminSpareThreads;
    }
    public void setThreadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public void setNamePrefix(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    public void setMaxIdleTime(int maxIdleTime) {
        this.maxIdleTime = maxIdleTime;
        if (executor != null) {
            executor.setKeepAliveTime(maxIdleTime, TimeUnit.MILLISECONDS);
        }
    }

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        if (executor != null) {
            executor.setMaximumPoolSize(maxThreads);
        }
    }

    public void setMinSpareThreads(int minSpareThreads) {
        this.minSpareThreads = minSpareThreads;
        if (executor != null) {
            executor.setCorePoolSize(minSpareThreads);
        }
    }

    public void setPrestartminSpareThreads(boolean prestartminSpareThreads) {
        this.prestartminSpareThreads = prestartminSpareThreads;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setMaxQueueSize(int size) {
        this.maxQueueSize = size;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public long getThreadRenewalDelay() {
        return threadRenewalDelay;
    }

    public void setThreadRenewalDelay(long threadRenewalDelay) {
        this.threadRenewalDelay = threadRenewalDelay;
        if (executor != null) {
            executor.setThreadRenewalDelay(threadRenewalDelay);
        }
    }

    // Statistics from the thread pool
    @Override
    public int getActiveCount() {
        return (executor != null) ? executor.getActiveCount() : 0;
    }

    public long getCompletedTaskCount() {
        return (executor != null) ? executor.getCompletedTaskCount() : 0;
    }

    public int getCorePoolSize() {
        return (executor != null) ? executor.getCorePoolSize() : 0;
    }

    public int getLargestPoolSize() {
        return (executor != null) ? executor.getLargestPoolSize() : 0;
    }

    @Override
    public int getPoolSize() {
        return (executor != null) ? executor.getPoolSize() : 0;
    }

    public int getQueueSize() {
        return (executor != null) ? executor.getQueue().size() : -1;
    }


    @Override
    public boolean resizePool(int corePoolSize, int maximumPoolSize) {
        if (executor == null)
            return false;

        executor.setCorePoolSize(corePoolSize);
        executor.setMaximumPoolSize(maximumPoolSize);
        return true;
    }


    @Override
    public boolean resizeQueue(int capacity) {
        return false;
    }


    @Override
    protected String getDomainInternal() {
        // No way to navigate to Engine. Needs to have domain set.
        return null;
    }

    @Override
    protected String getObjectNameKeyProperties() {
        StringBuilder name = new StringBuilder("type=Executor,name=");
        name.append(getName());
        return name.toString();
    }
}
