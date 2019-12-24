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

package org.apache.coyote;

import javax.servlet.http.HttpUpgradeHandler;

import org.apache.tomcat.ContextBind;
import org.apache.tomcat.InstanceManager;

/**
 * Token used during the upgrade process.
 * 升级token 对象
 */
public final class UpgradeToken {

    /**
     * 该对象是用来切换当前类加载器的
     */
    private final ContextBind contextBind;
    /**
     * 升级处理器
     */
    private final HttpUpgradeHandler httpUpgradeHandler;
    /**
     * 实例管理器
     */
    private final InstanceManager instanceManager;

    public UpgradeToken(HttpUpgradeHandler httpUpgradeHandler,
            ContextBind contextBind, InstanceManager instanceManager) {
        this.contextBind = contextBind;
        this.httpUpgradeHandler = httpUpgradeHandler;
        this.instanceManager = instanceManager;
    }

    public final ContextBind getContextBind() {
        return contextBind;
    }

    public final HttpUpgradeHandler getHttpUpgradeHandler() {
        return httpUpgradeHandler;
    }

    public final InstanceManager getInstanceManager() {
        return instanceManager;
    }

}
