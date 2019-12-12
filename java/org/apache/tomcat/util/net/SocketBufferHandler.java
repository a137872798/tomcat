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

import java.nio.ByteBuffer;

import org.apache.tomcat.util.buf.ByteBufferUtils;

/**
 * socket 缓冲区 处理器
 */
public class SocketBufferHandler {

    /**
     * 代表 readBuffer 当前被配置成写状态
     */
    private volatile boolean readBufferConfiguredForWrite = true;
    private volatile ByteBuffer readBuffer;

    /**
     * socket 写缓冲区 之后会通过内核态 将数据发送到远端
     */
    private volatile boolean writeBufferConfiguredForWrite = true;
    private volatile ByteBuffer writeBuffer;

    /**
     * 是否使用堆外内存
     */
    private final boolean direct;

    /**
     * 当声明 缓冲区大小后 初始化缓冲区
     * @param readBufferSize
     * @param writeBufferSize
     * @param direct
     */
    public SocketBufferHandler(int readBufferSize, int writeBufferSize,
            boolean direct) {
        this.direct = direct;
        if (direct) {
            readBuffer = ByteBuffer.allocateDirect(readBufferSize);
            writeBuffer = ByteBuffer.allocateDirect(writeBufferSize);
        } else {
            readBuffer = ByteBuffer.allocate(readBufferSize);
            writeBuffer = ByteBuffer.allocate(writeBufferSize);
        }
    }


    public void configureReadBufferForWrite() {
        setReadBufferConfiguredForWrite(true);
    }


    public void configureReadBufferForRead() {
        setReadBufferConfiguredForWrite(false);
    }


    /**
     * 是否为了 写入数据 读取bufferConfiguration
     * @param readBufferConFiguredForWrite
     */
    private void setReadBufferConfiguredForWrite(boolean readBufferConFiguredForWrite) {
        // NO-OP if buffer is already in correct state
        if (this.readBufferConfiguredForWrite != readBufferConFiguredForWrite) {
            // 如果从读模式变成写模式
            if (readBufferConFiguredForWrite) {
                // Switching to write
                int remaining = readBuffer.remaining();
                // 如果当前buffer内部没有数据 重置相关指针
                if (remaining == 0) {
                    readBuffer.clear();
                } else {
                    // 丢弃掉当前指针之前的数据
                    readBuffer.compact();
                }
            // 写模式转换成读模式
            } else {
                // Switching to read
                // 反转成读模式
                readBuffer.flip();
            }
            this.readBufferConfiguredForWrite = readBufferConFiguredForWrite;
        }
    }


    /**
     * 获取读缓冲区
     * @return
     */
    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }


    public boolean isReadBufferEmpty() {
        // 如果读buffer 被配置成了写状态 就是看当前指针是否指向首位
        if (readBufferConfiguredForWrite) {
            return readBuffer.position() == 0;
        } else {
            // 反之如果是读状态 就看剩余元素是否为0
            return readBuffer.remaining() == 0;
        }
    }


    public void configureWriteBufferForWrite() {
        setWriteBufferConfiguredForWrite(true);
    }


    public void configureWriteBufferForRead() {
        setWriteBufferConfiguredForWrite(false);
    }


    private void setWriteBufferConfiguredForWrite(boolean writeBufferConfiguredForWrite) {
        // NO-OP if buffer is already in correct state
        if (this.writeBufferConfiguredForWrite != writeBufferConfiguredForWrite) {
            // 如果当前writerBuffer 由读状态切换成写状态
            if (writeBufferConfiguredForWrite) {
                // Switching to write
                int remaining = writeBuffer.remaining();
                if (remaining == 0) {
                    writeBuffer.clear();
                } else {
                    writeBuffer.compact();
                    writeBuffer.position(remaining);
                    writeBuffer.limit(writeBuffer.capacity());
                }
            } else {
                // Switching to read
                // 由写切换成读状态
                writeBuffer.flip();
            }
            this.writeBufferConfiguredForWrite = writeBufferConfiguredForWrite;
        }
    }


    public boolean isWriteBufferWritable() {
        if (writeBufferConfiguredForWrite) {
            return writeBuffer.hasRemaining();
        } else {
            return writeBuffer.remaining() == 0;
        }
    }


    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }


    public boolean isWriteBufferEmpty() {
        if (writeBufferConfiguredForWrite) {
            return writeBuffer.position() == 0;
        } else {
            return writeBuffer.remaining() == 0;
        }
    }


    public void reset() {
        readBuffer.clear();
        readBufferConfiguredForWrite = true;
        writeBuffer.clear();
        writeBufferConfiguredForWrite = true;
    }


    public void expand(int newSize) {
        configureReadBufferForWrite();
        readBuffer = ByteBufferUtils.expand(readBuffer, newSize);
        configureWriteBufferForWrite();
        writeBuffer = ByteBufferUtils.expand(writeBuffer, newSize);
    }

    /**
     * 如果当前使用了堆外内存 那么使用 Clean 对象进行内存释放
     */
    public void free() {
        if (direct) {
            ByteBufferUtils.cleanDirectBuffer(readBuffer);
            ByteBufferUtils.cleanDirectBuffer(writeBuffer);
        }
    }

}
