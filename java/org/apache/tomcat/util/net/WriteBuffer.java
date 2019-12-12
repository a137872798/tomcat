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
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.tomcat.util.buf.ByteBufferHolder;

/**
 * Provides an expandable set of buffers for writes. Non-blocking writes can be
 * of any size and may not be able to be written immediately or wholly contained
 * in the buffer used to perform the writes to the next layer. This class
 * provides a buffering capability to allow such writes to return immediately
 * and also allows for the user provided buffers to be re-used / recycled as
 * required.
 * 非阻塞的 IO 缓冲区
 */
public class WriteBuffer {

    /**
     * 每个缓冲区大小
     */
    private final int bufferSize;

    /**
     * 阻塞双端队列  内部维护的元素是 ByteBufferHolder  对外提供了flip()  用于转换内部buffer的模式
     */
    private final LinkedBlockingDeque<ByteBufferHolder> buffers = new LinkedBlockingDeque<>();

    /**
     * 代表内部每个 bytebuffer 都使用这个大小
     * @param bufferSize
     */
    public WriteBuffer(int bufferSize) {
        this.bufferSize = bufferSize;
    }


    /**
     * 为当前 writerBuffer 对象 写入一个 byte[]
     * @param buf
     * @param offset
     * @param length
     */
    void add(byte[] buf, int offset, int length) {
        // 分配合适大小的 holder
        ByteBufferHolder holder = getByteBufferHolder(length);
        // 将 byte[] 的内容转移到 bytebuffer 中
        holder.getBuf().put(buf, offset, length);
    }


    public void add(ByteBuffer from) {
        ByteBufferHolder holder = getByteBufferHolder(from.remaining());
        holder.getBuf().put(from);
    }


    /**
     * 获取缓冲区对象
     * @param capacity
     * @return
     */
    private ByteBufferHolder getByteBufferHolder(int capacity) {
        // 从双端队列中弹出最后一个元素
        ByteBufferHolder holder = buffers.peekLast();
        // 如果bytebuffer 还没有创建 或者 已经翻转成读模式 或者 不足以分配指定的容量
        if (holder == null || holder.isFlipped() || holder.getBuf().remaining() < capacity) {
            // 默认情况下 分配 bufferSize 如果不足  则分配 capacity 的大小 那么有些 bytebuffer 会存在浪费
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(bufferSize, capacity));
            // 将byteBuffer 封装成 holder对象
            holder = new ByteBufferHolder(buffer, false);
            buffers.add(holder);
        }
        return holder;
    }


    public boolean isEmpty() {
        return buffers.isEmpty();
    }


    /**
     * Create an array of ByteBuffers from the current WriteBuffer, prefixing
     * that array with the provided ByteBuffers.
     *
     * @param prefixes The additional ByteBuffers to add to the start of the
     *                 array   一组前置的bytebuffer
     *
     * @return an array of ByteBuffers from the current WriteBuffer prefixed by
     *         the provided ByteBuffers
     */
    ByteBuffer[] toArray(ByteBuffer... prefixes) {
        List<ByteBuffer> result = new ArrayList<>();
        // 只要还有剩余空间的 byteBuffer 都会被加入到  list中
        for (ByteBuffer prefix : prefixes) {
            if (prefix.hasRemaining()) {
                result.add(prefix);
            }
        }
        // 将 当前对象维护的所有 bytebuffer 都转移到list中
        for (ByteBufferHolder buffer : buffers) {
            buffer.flip();
            result.add(buffer.getBuf());
        }
        // 清除内部维护的 buffer
        buffers.clear();
        return result.toArray(new ByteBuffer[result.size()]);
    }


    /**
     * 将数据从 writeBuffer 写入到 socket
     * @param socketWrapper
     * @param blocking  是否阻塞写入
     * @return
     * @throws IOException
     */
    boolean write(SocketWrapperBase<?> socketWrapper, boolean blocking) throws IOException {
        // 遍历当前已有的全部 buffer
        Iterator<ByteBufferHolder> bufIter = buffers.iterator();
        boolean dataLeft = false;
        while (!dataLeft && bufIter.hasNext()) {
            ByteBufferHolder buffer = bufIter.next();
            buffer.flip();
            if (blocking) {
                // 已阻塞方式写入到 buffer 中
                socketWrapper.writeBlocking(buffer.getBuf());
            } else {
                // 已非阻塞方式写入到 buffer 中
                socketWrapper.writeNonBlockingInternal(buffer.getBuf());
            }
            if (buffer.getBuf().remaining() == 0) {
                bufIter.remove();
            } else {
                // 如果某个 buffer 还有剩余空间 那么不需要再往下遍历了 实际上如果分配的 容量不够那么某些buffer 不是会有剩余空间吗
                dataLeft = true;
            }
        }
        return dataLeft;
    }


    /**
     * 将数据写入到一个 sink 对象
     * @param sink
     * @param blocking
     * @return
     * @throws IOException
     */
    public boolean write(Sink sink, boolean blocking) throws IOException {
        Iterator<ByteBufferHolder> bufIter = buffers.iterator();
        boolean dataLeft = false;
        while (!dataLeft && bufIter.hasNext()) {
            ByteBufferHolder buffer = bufIter.next();
            buffer.flip();
            dataLeft = sink.writeFromBuffer(buffer.getBuf(), blocking);
            if (!dataLeft) {
                bufIter.remove();
            }
        }
        return dataLeft;
    }


    /**
     * Interface implemented by clients of the WriteBuffer to enable data to be
     * written back out from the buffer.
     * 一个下沉接口 可以将buffer 的数据写入到内部
     */
    public interface Sink {
        boolean writeFromBuffer(ByteBuffer buffer, boolean block) throws IOException;
    }
}
