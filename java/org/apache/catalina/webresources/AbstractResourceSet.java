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
package org.apache.catalina.webresources;

import java.util.jar.Manifest;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.WebResourceSet;
import org.apache.catalina.util.LifecycleBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * 该对象代表一组资源的容器
 */
public abstract class AbstractResourceSet extends LifecycleBase
        implements WebResourceSet {

    /**
     * 该组资源对应的 root
     */
    private WebResourceRoot root;
    private String base;
    private String internalPath = "";
    /**
     * 应用山
     */
    private String webAppMount;

    /**
     * 是否只允许通过类加载器进行加载
     */
    private boolean classLoaderOnly;
    /**
     * 只允许使用静态资源进行加载
     */
    private boolean staticOnly;
    private Manifest manifest;


    protected static final StringManager sm = StringManager.getManager(AbstractResourceSet.class);


    /**
     * 检查路径是否合法  路径必须以 / 开头
     * @param path
     */
    protected final void checkPath(String path) {
        if (path == null || path.length() == 0 || path.charAt(0) != '/') {
            throw new IllegalArgumentException(
                    sm.getString("abstractResourceSet.checkPath", path));
        }
    }

    @Override
    public final void setRoot(WebResourceRoot root) {
        this.root = root;
    }

    protected final WebResourceRoot getRoot() {
        return root;
    }


    protected final String getInternalPath() {
        return internalPath;
    }

    /**
     * 当设置内部路径的是否要做校验
     * @param internalPath
     */
    public final void setInternalPath(String internalPath) {
        checkPath(internalPath);
        // Optimise internal processing
        if (internalPath.equals("/")) {
            this.internalPath = "";
        } else {
            this.internalPath = internalPath;
        }
    }

    public final void setWebAppMount(String webAppMount) {
        checkPath(webAppMount);
        // Optimise internal processing
        if (webAppMount.equals("/")) {
            this.webAppMount = "";
        } else {
            this.webAppMount = webAppMount;
        }
    }

    protected final String getWebAppMount() {
        return webAppMount;
    }

    public final void setBase(String base) {
        this.base = base;
    }

    protected final String getBase() {
        return base;
    }

    @Override
    public boolean getClassLoaderOnly() {
        return classLoaderOnly;
    }

    @Override
    public void setClassLoaderOnly(boolean classLoaderOnly) {
        this.classLoaderOnly = classLoaderOnly;
    }

    @Override
    public boolean getStaticOnly() {
        return staticOnly;
    }

    @Override
    public void setStaticOnly(boolean staticOnly) {
        this.staticOnly = staticOnly;
    }

    protected final void setManifest(Manifest manifest) {
        this.manifest = manifest;
    }

    protected final Manifest getManifest() {
        return manifest;
    }


    //-------------------------------------------------------- Lifecycle methods
    @Override
    protected final void startInternal() throws LifecycleException {
        setState(LifecycleState.STARTING);
    }

    @Override
    protected final void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING);
    }

    @Override
    protected final void destroyInternal() throws LifecycleException {
        gc();
    }
}
