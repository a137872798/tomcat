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

import java.io.InputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.jar.Manifest;

/**
 * Represents a file or directory within a web application. It borrows heavily
 * from {@link java.io.File}.
 * 资源对象接口
 */
public interface WebResource {
    /**
     * @return {@link java.io.File#lastModified()}.
     * 获取资源最后修改时间
     */
    long getLastModified();

    /**
     * @return the last modified time of this resource in the correct format for
     * the HTTP Last-Modified header as specified by RFC 2616.
     * 返回 http 标准格式的修改
     */
    String getLastModifiedHttp();

    /**
     * @return {@link java.io.File#exists()}.
     * 判断指定资源是否存在
     */
    boolean exists();

    /**
     * Indicates if this resource is required for applications to correctly scan
     * the file structure but that does not exist in either the main or any
     * additional {@link WebResourceSet}. For example, if an external
     * directory is mapped to /WEB-INF/lib in an otherwise empty web
     * application, /WEB-INF will be represented as a virtual resource.
     * 是否是虚拟资源???
     *
     * @return <code>true</code> for a virtual resource
     *
     */
    boolean isVirtual();

    /**
     * 是否是目录
     * @return {@link java.io.File#isDirectory()}.
     */
    boolean isDirectory();

    /**
     * 是否是文件
     * @return {@link java.io.File#isFile()}.
     */
    boolean isFile();

    /**
     * 删除 并返回是否成功?
     * @return {@link java.io.File#delete()}.
     */
    boolean delete();

    /**
     * 获取文件名
     * @return {@link java.io.File#getName()}.
     */
    String getName();

    /**
     * 获取文件体长度
     * @return {@link java.io.File#length()}.
     */
    long getContentLength();

    /**
     * 获取文件规范路径
     * @return {@link java.io.File#getCanonicalPath()}.
     */
    String getCanonicalPath();

    /**
     * 判断文件是否能读取
     * @return {@link java.io.File#canRead()}.
     */
    boolean canRead();

    /**
     * 该路径相对于 webApplication.root 的路径
     * @return The path of this resource relative to the web application root. If the
     * resource is a directory, the return value will end in '/'.
     */
    String getWebappPath();

    /**
     * Return the strong ETag if available (currently not supported) else return
     * the weak ETag calculated from the content length and last modified.
     * 先不看 ETag
     * @return  The ETag for this resource
     */
    String getETag();

    /**
     * Set the MIME type for this Resource.
     * 为本资源设置多媒体类型
     * @param mimeType The mime type that will be associated with the resource
     */
    void setMimeType(String mimeType);

    /**
     * @return the MIME type for this Resource.
     */
    String getMimeType();

    /**
     * Obtain an InputStream based on the contents of this resource.
     * 将资源以 输入流方式获取
     * @return  An InputStream based on the contents of this resource or
     *          <code>null</code> if the resource does not exist or does not
     *          represent a file
     */
    InputStream getInputStream();

    /**
     * 获取资源内容
     * @return the binary content of this resource or {@code null} if it is not
     *         available in a byte[] because, for example, it is too big.
     */
    byte[] getContent();

    /**
     * 获取文件创建时间
     * @return The time the file was created. If not available, the result of
     * {@link #getLastModified()} will be returned.
     */
    long getCreation();

    /**
     * 获取资源url
     * @return a URL to access the resource or <code>null</code> if no such URL
     * is available or if the resource does not exist.
     */
    URL getURL();

    /**
     * 该资源会使用的代码库
     * @return the code base for this resource that will be used when looking up the
     * assigned permissions for the code base in the security policy file when
     * running under a security manager.
     */
    URL getCodeBase();

    /**
     * 获取根路径资源
     * @return a reference to the WebResourceRoot of which this WebResource is a
     * part.
     */
    WebResourceRoot getWebResourceRoot();

    /**
     * @return the certificates that were used to sign this resource to verify
     * it or @null if none.
     *
     * @see java.util.jar.JarEntry#getCertificates()
     */
    Certificate[] getCertificates();

    /**
     * @return the manifest associated with this resource or @null if none.
     *
     * @see java.util.jar.JarFile#getManifest()
     */
    Manifest getManifest();
}
