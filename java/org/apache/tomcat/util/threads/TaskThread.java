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
package org.apache.tomcat.util.threads;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * A Thread implementation that records the time at which it was created.
 *
 * tomcat 内置的工作线程 会记录自身本创建的时间
 */
public class TaskThread extends Thread {

    private static final Log log = LogFactory.getLog(TaskThread.class);
    /**
     * 当前线程创建时间
     */
    private final long creationTime;

    /**
     * 该对象内部的 runnable 都会被包装成 WrappingRunnable
     * @param group
     * @param target
     * @param name
     */
    public TaskThread(ThreadGroup group, Runnable target, String name) {
        super(group, new WrappingRunnable(target), name);
        this.creationTime = System.currentTimeMillis();
    }

    public TaskThread(ThreadGroup group, Runnable target, String name,
            long stackSize) {
        super(group, new WrappingRunnable(target), name, stackSize);
        this.creationTime = System.currentTimeMillis();
    }

    /**
     * @return the time (in ms) at which this thread was created
     */
    public final long getCreationTime() {
        return creationTime;
    }

    /**
     * Wraps a {@link Runnable} to swallow any {@link StopPooledThreadException}
     * instead of letting it go and potentially trigger a break in a debugger.
     *
     * 在 tomcat 定制的线程池中在执行完任务后可能会关闭该线程 同时抛出一个异常 这里就是捕获该异常并打印日志
     */
    private static class WrappingRunnable implements Runnable {
        private Runnable wrappedRunnable;
        WrappingRunnable(Runnable wrappedRunnable) {
            this.wrappedRunnable = wrappedRunnable;
        }
        @Override
        public void run() {
            try {
                wrappedRunnable.run();
            } catch(StopPooledThreadException exc) {
                //expected : we just swallow the exception to avoid disturbing
                //debuggers like eclipse's
                log.debug("Thread exiting on purpose", exc);
            }
        }

    }

}
