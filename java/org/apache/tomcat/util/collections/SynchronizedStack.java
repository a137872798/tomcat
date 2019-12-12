/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.collections;

/**
 * This is intended as a (mostly) GC-free alternative to
 * {@link java.util.concurrent.ConcurrentLinkedQueue} when the requirement is to
 * create a pool of re-usable objects with no requirement to shrink the pool.
 * The aim is to provide the bare minimum of required functionality as quickly
 * as possible with minimum garbage.
 *
 * @param <T> The type of object managed by this stack
 *           同步栈对象
 */
public class SynchronizedStack<T> {

    public static final int DEFAULT_SIZE = 128;
    private static final int DEFAULT_LIMIT = -1;

    private int size;
    private final int limit;

    /*
     * Points to the next available object in the stack
     */
    private int index = -1;

    /**
     * 该数组对象没有使用 volatile 修饰
     */
    private Object[] stack;


    public SynchronizedStack() {
        this(DEFAULT_SIZE, DEFAULT_LIMIT);
    }

    /**
     * @param size
     * @param limit  允许的最大大小 （是否允许扩容 -1 代表没有限制）
     */
    public SynchronizedStack(int size, int limit) {
        if (limit > -1 && size > limit) {
            this.size = limit;
        } else {
            this.size = size;
        }
        this.limit = limit;
        // 根据 size 初始化数组
        stack = new Object[size];
    }


    /**
     * 将某个元素 设置到 栈结构中  该方法使用 synchronized 修饰
     * @param obj
     * @return
     */
    public synchronized boolean push(T obj) {
        index++;
        // 代表需要进行扩容
        if (index == size) {
            if (limit == -1 || size < limit) {
                expand();
            } else {
                // 如果 不允许扩容 那么本次 push 失败
                index--;
                return false;
            }
        }
        stack[index] = obj;
        return true;
    }

    /**
     * 从栈结构中弹出某个元素
     * @return
     */
    @SuppressWarnings("unchecked")
    public synchronized T pop() {
        if (index == -1) {
            return null;
        }
        T result = (T) stack[index];
        stack[index--] = null;
        return result;
    }

    public synchronized void clear() {
        if (index > -1) {
            for (int i = 0; i < index + 1; i++) {
                stack[i] = null;
            }
        }
        index = -1;
    }

    private void expand() {
        int newSize = size * 2;
        if (limit != -1 && newSize > limit) {
            newSize = limit;
        }
        Object[] newStack = new Object[newSize];
        System.arraycopy(stack, 0, newStack, 0, size);
        // This is the only point where garbage is created by throwing away the
        // old array. Note it is only the array, not the contents, that becomes
        // garbage.
        stack = newStack;
        size = newSize;
    }
}
