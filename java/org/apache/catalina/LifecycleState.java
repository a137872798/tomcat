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
package org.apache.catalina;

/**
 * The list of valid states for components that implement {@link Lifecycle}.
 * See {@link Lifecycle} for the state transition diagram.
 * 生命周期枚举
 */
public enum LifecycleState {

    // param1 代表当前组件是否可用  param2 代表处在当前阶段发出的事件

    /**
     * 对象刚创建 应该是默认的状态
     */
    NEW(false, null),
    /**
     * 代表正在执行 init
     */
    INITIALIZING(false, Lifecycle.BEFORE_INIT_EVENT),
    /**
     * 代表init 执行完成
     */
    INITIALIZED(false, Lifecycle.AFTER_INIT_EVENT),
    /**
     * 准备start 中
     */
    STARTING_PREP(false, Lifecycle.BEFORE_START_EVENT),
    /**
     * 正在启动
     */
    STARTING(true, Lifecycle.START_EVENT),
    /**
     * 启动完成
     */
    STARTED(true, Lifecycle.AFTER_START_EVENT),
    /**
     * 准备停止
     */
    STOPPING_PREP(true, Lifecycle.BEFORE_STOP_EVENT),
    /**
     * 停止中
     */
    STOPPING(false, Lifecycle.STOP_EVENT),
    /**
     * 已停止
     */
    STOPPED(false, Lifecycle.AFTER_STOP_EVENT),
    /**
     * 正在销毁
     */
    DESTROYING(false, Lifecycle.BEFORE_DESTROY_EVENT),
    /**
     * 已销毁
     */
    DESTROYED(false, Lifecycle.AFTER_DESTROY_EVENT),
    /**
     * 启动失败
     */
    FAILED(false, null);

    private final boolean available;
    private final String lifecycleEvent;

    private LifecycleState(boolean available, String lifecycleEvent) {
        this.available = available;
        this.lifecycleEvent = lifecycleEvent;
    }

    /**
     * May the public methods other than property getters/setters and lifecycle
     * methods be called for a component in this state? It returns
     * <code>true</code> for any component in any of the following states:
     * <ul>
     * <li>{@link #STARTING}</li>
     * <li>{@link #STARTED}</li>
     * <li>{@link #STOPPING_PREP}</li>
     * </ul>
     *
     * @return <code>true</code> if the component is available for use,
     *         otherwise <code>false</code>
     */
    public boolean isAvailable() {
        return available;
    }

    public String getLifecycleEvent() {
        return lifecycleEvent;
    }
}
