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
package org.apache.catalina.util;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.SessionIdGenerator;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

/**
 * sessionId 生成器 的基类     实现了生命周期接口 那么会有各种对应的事件
 */
public abstract class SessionIdGeneratorBase extends LifecycleBase
        implements SessionIdGenerator {

    private final Log log = LogFactory.getLog(SessionIdGeneratorBase.class); // must not be static


    private static final StringManager sm =
        StringManager.getManager("org.apache.catalina.util");


    /**
     * Queue of random number generator objects to be used when creating session
     * identifiers. If the queue is empty when a random number generator is
     * required, a new random number generator object is created. This is
     * designed this way since random number generators use a sync to make them
     * thread-safe and the sync makes using a single object slow(er).
     * SecureRandom 用于生成随机数
     */
    private final Queue<SecureRandom> randoms = new ConcurrentLinkedQueue<>();

    private String secureRandomClass = null;

    /**
     * 使用的加密算法
     */
    private String secureRandomAlgorithm = "SHA1PRNG";

    private String secureRandomProvider = null;


    /** Node identifier when in a cluster. Defaults to the empty string.
     *  该字段用于标识在集群范围内的唯一节点  默认情况下是不需要设置的
     */
    private String jvmRoute = "";


    /** Number of bytes in a session ID. Defaults to 16. sessionId 的默认长度为16*/
    private int sessionIdLength = 16;


    /**
     * Get the class name of the {@link SecureRandom} implementation used to
     * generate session IDs.
     *
     * @return The fully qualified class name. {@code null} indicates that the
     *         JRE provided {@link SecureRandom} implementation will be used
     */
    public String getSecureRandomClass() {
        return secureRandomClass;
    }


    /**
     * Specify a non-default {@link SecureRandom} implementation to use. The
     * implementation must be self-seeding and have a zero-argument constructor.
     * If not specified, an instance of {@link SecureRandom} will be generated.
     *
     * @param secureRandomClass The fully-qualified class name
     */
    public void setSecureRandomClass(String secureRandomClass) {
        this.secureRandomClass = secureRandomClass;
    }


    /**
     * Get the name of the algorithm used to create the {@link SecureRandom}
     * instances which generate new session IDs.
     *
     * @return The name of the algorithm. {@code null} or the empty string means
     *         that platform default will be used
     */
    public String getSecureRandomAlgorithm() {
        return secureRandomAlgorithm;
    }


    /**
     * Specify a non-default algorithm to use to create instances of
     * {@link SecureRandom} which are used to generate session IDs. If no
     * algorithm is specified, SHA1PRNG is used. To use the platform default
     * (which may be SHA1PRNG), specify {@code null} or the empty string. If an
     * invalid algorithm and/or provider is specified the {@link SecureRandom}
     * instances will be created using the defaults for this
     * {@link SessionIdGenerator} implementation. If that fails, the
     * {@link SecureRandom} instances will be created using platform defaults.
     *
     * @param secureRandomAlgorithm The name of the algorithm
     */
    public void setSecureRandomAlgorithm(String secureRandomAlgorithm) {
        this.secureRandomAlgorithm = secureRandomAlgorithm;
    }


    /**
     * Get the name of the provider used to create the {@link SecureRandom}
     * instances which generate new session IDs.
     *
     * @return The name of the provider. {@code null} or the empty string means
     *         that platform default will be used
     *         SecureRandomProvider 是用来生成 secureRandom 的
     */
    public String getSecureRandomProvider() {
        return secureRandomProvider;
    }


    /**
     * Specify a non-default provider to use to create instances of
     * {@link SecureRandom} which are used to generate session IDs.  If no
     * provider is specified, the platform default is used. To use the platform
     * default specify {@code null} or the empty string. If an invalid algorithm
     * and/or provider is specified the {@link SecureRandom} instances will be
     * created using the defaults for this {@link SessionIdGenerator}
     * implementation. If that fails, the {@link SecureRandom} instances will be
     * created using platform defaults.
     *
     * @param secureRandomProvider  The name of the provider
     */
    public void setSecureRandomProvider(String secureRandomProvider) {
        this.secureRandomProvider = secureRandomProvider;
    }


    /**
     * Return the node identifier associated with this node which will be
     * included in the generated session ID.
     */
    @Override
    public String getJvmRoute() {
        return jvmRoute;
    }


    /**
     * Specify the node identifier associated with this node which will be
     * included in the generated session ID.
     *
     * @param jvmRoute  The node identifier
     */
    @Override
    public void setJvmRoute(String jvmRoute) {
        this.jvmRoute = jvmRoute;
    }


    /**
     * Return the number of bytes for a session ID
     */
    @Override
    public int getSessionIdLength() {
        return sessionIdLength;
    }


    /**
     * Specify the number of bytes for a session ID
     *
     * @param sessionIdLength   Number of bytes
     */
    @Override
    public void setSessionIdLength(int sessionIdLength) {
        this.sessionIdLength = sessionIdLength;
    }


    /**
     * Generate and return a new session identifier.
     * 生成 sessionId 的核心方法由子类实现
     */
    @Override
    public String generateSessionId() {
        return generateSessionId(jvmRoute);
    }


    protected void getRandomBytes(byte bytes[]) {

        SecureRandom random = randoms.poll();
        if (random == null) {
            // 创建一个 secureRandom
            random = createSecureRandom();
        }
        // 使用该对象 生成随机字符并填充数组
        random.nextBytes(bytes);
        // 将对象归还
        randoms.add(random);
    }


    /**
     * Create a new random number generator instance we should use for
     * generating session identifiers.
     * 构建一个  secureRandom 对象
     */
    private SecureRandom createSecureRandom() {

        SecureRandom result = null;

        long t1 = System.currentTimeMillis();
        // 如果指定了实现了  那么通过反射创建对象
        if (secureRandomClass != null) {
            try {
                // Construct and seed a new random number generator
                Class<?> clazz = Class.forName(secureRandomClass);
                result = (SecureRandom) clazz.getConstructor().newInstance();
            } catch (Exception e) {
                log.error(sm.getString("sessionIdGeneratorBase.random",
                        secureRandomClass), e);
            }
        }

        // 如果没有指定class
        boolean error = false;
        if (result == null) {
            // No secureRandomClass or creation failed. Use SecureRandom.
            try {
                // 如果指定了 provider  通过静态方法创建随机数生成对象
                if (secureRandomProvider != null &&
                        secureRandomProvider.length() > 0) {
                    result = SecureRandom.getInstance(secureRandomAlgorithm,
                            secureRandomProvider);
                } else if (secureRandomAlgorithm != null &&
                        secureRandomAlgorithm.length() > 0) {
                    result = SecureRandom.getInstance(secureRandomAlgorithm);
                }
            } catch (NoSuchAlgorithmException e) {
                error = true;
                log.error(sm.getString("sessionIdGeneratorBase.randomAlgorithm",
                        secureRandomAlgorithm), e);
            } catch (NoSuchProviderException e) {
                error = true;
                log.error(sm.getString("sessionIdGeneratorBase.randomProvider",
                        secureRandomProvider), e);
            }
        }

        if (result == null && error) {
            // Invalid provider / algorithm  还是没有时使用默认实现
            try {
                result = SecureRandom.getInstance("SHA1PRNG");
            } catch (NoSuchAlgorithmException e) {
                log.error(sm.getString("sessionIdGeneratorBase.randomAlgorithm",
                        secureRandomAlgorithm), e);
            }
        }

        if (result == null) {
            // Nothing works - use platform default
            result = new SecureRandom();
        }

        // Force seeding to take place
        result.nextInt();

        long t2 = System.currentTimeMillis();
        if ((t2 - t1) > 100) {
            log.warn(sm.getString("sessionIdGeneratorBase.createRandom",
                    result.getAlgorithm(), Long.valueOf(t2 - t1)));
        }
        return result;
    }


    @Override
    protected void initInternal() throws LifecycleException {
        // NO-OP
    }


    /**
     * 当 generator 被创建时  触发start 会生成sessionId
     * @throws LifecycleException
     */
    @Override
    protected void startInternal() throws LifecycleException {
        // Ensure SecureRandom has been initialised
        generateSessionId();

        setState(LifecycleState.STARTING);
    }


    /**
     * 终止时清除 randoms队列 看来单个 generator 对象是被多线程并发访问的  所以 queue 使用阻塞队列实现 当高并发情况 每个无法从队列中获取到元素
     * 就会创建一个新对象 而在处理完毕后 又会将对象归还到队列
     * @throws LifecycleException
     */
    @Override
    protected void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING);
        randoms.clear();
    }


    @Override
    protected void destroyInternal() throws LifecycleException {
        // NO-OP
    }
}
