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

import java.io.Serializable;

/**
 * Base class for the *Chunk implementation to reduce duplication.
 * 用于表示字节的数组对象 在这里被封装成 chunk
 */
public abstract class AbstractChunk implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;

    /*
     * JVMs may limit the maximum array size to slightly less than
     * Integer.MAX_VALUE. On markt's desktop the limit is MAX_VALUE - 2.
     * Comments in the JRE source code for ArrayList and other classes indicate
     * that it may be as low as MAX_VALUE - 8 on some systems.
     * 允许使用的最大数组长度
     */
    public static final int ARRAY_MAX_SIZE = Integer.MAX_VALUE - 8;

    private int hashCode = 0;
    protected boolean hasHashCode = false;

    /**
     * 是否设置了值
     */
    protected boolean isSet;

    private int limit = -1;

    /**
     * 目标起始偏移量和 终止偏移量
     */
    protected int start;
    protected int end;


    /**
     * Maximum amount of data in this buffer. If -1 or not set, the buffer will
     * grow to {{@link #ARRAY_MAX_SIZE}. Can be smaller than the current buffer
     * size ( which will not shrink ). When the limit is reached, the buffer
     * will be flushed (if out is set) or throw exception.
     *
     * @param limit The new limit
     */
    public void setLimit(int limit) {
        this.limit = limit;
    }


    public int getLimit() {
        return limit;
    }


    protected int getLimitInternal() {
        if (limit > 0) {
            return limit;
        } else {
            return ARRAY_MAX_SIZE;
        }
    }


    /**
     * @return the start position of the data in the buffer  获取起始/终止指针
     */
    public int getStart() {
        return start;
    }


    public int getEnd() {
        return end;
    }


    public void setEnd(int i) {
        end = i;
    }


    // TODO: Deprecate offset and use start

    public int getOffset() {
        return start;
    }


    public void setOffset(int off) {
        if (end < off) {
            end = off;
        }
        start = off;
    }


    /**
     * @return the length of the data in the buffer
     */
    public int getLength() {
        return end - start;
    }


    public boolean isNull() {
        if (end > 0) {
            return false;
        }
        return !isSet;
    }


    /**
     * 寻找指定字符 在 当前 chunk 的起始位置
     * @param src
     * @param srcOff  从 src的第几个字符开始匹配
     * @param srcLen  允许匹配到第几个字符
     * @param myOff
     * @return
     */
    public int indexOf(String src, int srcOff, int srcLen, int myOff) {
        // 找到首个 尝试匹配的字符
        char first = src.charAt(srcOff);

        // Look for first char   定位到末尾
        int srcEnd = srcOff + srcLen;

        mainLoop: for (int i = myOff + start; i <= (end - srcLen); i++) {
            // 根据下标来获取元素 当不匹配时继续往下
            if (getBufferElement(i) != first) {
                continue;
            }
            // found first char, now look for a match  进入到这里 代表匹配成功 还需要往下匹配更多字符
            int myPos = i + 1;
            for (int srcPos = srcOff + 1; srcPos < srcEnd;) {
                if (getBufferElement(myPos++) != src.charAt(srcPos++)) {
                    continue mainLoop;
                }
            }
            return i - start; // found it
        }
        // 代表没有匹配成功
        return -1;
    }


    /**
     * Resets the chunk to an uninitialized state.
     * 将chunk 还原
     */
    public void recycle() {
        hasHashCode = false;
        isSet = false;
        start = 0;
        end = 0;
    }


    @Override
    public int hashCode() {
        if (hasHashCode) {
            return hashCode;
        }
        int code = 0;

        code = hash();
        hashCode = code;
        hasHashCode = true;
        return code;
    }


    public int hash() {
        int code = 0;
        for (int i = start; i < end; i++) {
            code = code * 37 + getBufferElement(i);
        }
        return code;
    }


    protected abstract int getBufferElement(int index);
}
