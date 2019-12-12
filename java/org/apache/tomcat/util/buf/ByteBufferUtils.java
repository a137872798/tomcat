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
package org.apache.tomcat.util.buf;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.res.StringManager;

/**
 * 该工具类 内部存放的是 有关堆外内存释放的api
 */
public class ByteBufferUtils {

    private static final StringManager sm =
            StringManager.getManager(Constants.Package);
    private static final Log log = LogFactory.getLog(ByteBufferUtils.class);

    private static final Object unsafe;
    private static final Method cleanerMethod;
    private static final Method cleanMethod;
    private static final Method invokeCleanerMethod;

    static {
        ByteBuffer tempBuffer = ByteBuffer.allocateDirect(0);
        Method cleanerMethodLocal = null;
        Method cleanMethodLocal = null;
        Object unsafeLocal = null;
        Method invokeCleanerMethodLocal = null;
        // 判断当前环境是否是 jre9
        if (JreCompat.isJre9Available()) {
            try {
                // 通过反射 获取 unsafe 对象
                Class<?> clazz = Class.forName("sun.misc.Unsafe");
                Field theUnsafe = clazz.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                // 因为是静态字段 所有 get(null)
                unsafeLocal = theUnsafe.get(null);
                // 从 unsafe对象中获取 cleaner 方法  该方法可以使用cleaner 清除内部的 bytebuffer 对象
                invokeCleanerMethodLocal = clazz.getMethod("invokeCleaner", ByteBuffer.class);
                // 尝试使用 cleaner 清除 临时创建的 bytebuffer
                invokeCleanerMethodLocal.invoke(unsafeLocal, tempBuffer);
                // 当返回 相关异常时 代表尝试使用invokeCleaner进行清除工作失败
            } catch (IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException | NoSuchMethodException | SecurityException
                    | ClassNotFoundException | NoSuchFieldException e) {
                log.warn(sm.getString("byteBufferUtils.cleaner"), e);
                unsafeLocal = null;
                invokeCleanerMethodLocal = null;
            }
        } else {
            try {
                // 尝试获取 cleaner 方法 并调用
                cleanerMethodLocal = tempBuffer.getClass().getMethod("cleaner");
                cleanerMethodLocal.setAccessible(true);
                Object cleanerObject = cleanerMethodLocal.invoke(tempBuffer);
                // 获取 cleaner 对象的 clean 方法 并执行
                cleanMethodLocal = cleanerObject.getClass().getMethod("clean");
                cleanMethodLocal.invoke(cleanerObject);
            } catch (NoSuchMethodException | SecurityException | IllegalAccessException |
                    IllegalArgumentException | InvocationTargetException e) {
                log.warn(sm.getString("byteBufferUtils.cleaner"), e);
                cleanerMethodLocal = null;
                cleanMethodLocal = null;
            }
        }
        // 如果上面调用失败 那么不就无法释放堆外内存了吗
        cleanerMethod = cleanerMethodLocal;
        cleanMethod = cleanMethodLocal;
        unsafe = unsafeLocal;
        invokeCleanerMethod = invokeCleanerMethodLocal;
    }

    private ByteBufferUtils() {
        // Hide the default constructor since this is a utility class.
    }


    /**
     * Expands buffer to the given size unless it is already as big or bigger.
     * Buffers are assumed to be in 'write to' mode since there would be no need
     * to expand a buffer while it was in 'read from' mode.
     *
     * @param in        Buffer to expand
     * @param newSize   The size t which the buffer should be expanded
     * @return          The expanded buffer with any data from the input buffer
     *                  copied in to it or the original buffer if there was no
     *                  need for expansion
     */
    public static ByteBuffer expand(ByteBuffer in, int newSize) {
        if (in.capacity() >= newSize) {
            return in;
        }

        ByteBuffer out;
        boolean direct = false;
        if (in.isDirect()) {
            out = ByteBuffer.allocateDirect(newSize);
            direct = true;
        } else {
            out = ByteBuffer.allocate(newSize);
        }

        // Copy data
        in.flip();
        out.put(in);

        if (direct) {
            cleanDirectBuffer(in);
        }

        return out;
    }

    /**
     * 通过反射方法 清除堆外内存
     * @param buf
     */
    public static void cleanDirectBuffer(ByteBuffer buf) {
        // 如果 cleanMethod / invokeCleanerMethod 方法存在 那么针对 buf 进行释放 否则忽略
        if (cleanMethod != null) {
            try {
                cleanMethod.invoke(cleanerMethod.invoke(buf));
            } catch (IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException | SecurityException e) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("byteBufferUtils.cleaner"), e);
                }
            }
        } else if (invokeCleanerMethod != null) {
            try {
                invokeCleanerMethod.invoke(unsafe, buf);
            } catch (IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException | SecurityException e) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("byteBufferUtils.cleaner"), e);
                }
            }
        }
    }

}
