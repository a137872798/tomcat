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
package org.apache.catalina.connector;

import java.io.BufferedReader;
import java.io.IOException;


/**
 * Coyote implementation of the buffered reader.
 *
 * @author Remy Maucherat
 * 字符流
 */
public class CoyoteReader
    extends BufferedReader {


    // -------------------------------------------------------------- Constants


    /**
     * 换行符
     */
    private static final char[] LINE_SEP = { '\r', '\n' };
    /**
     * 单行最大长度
     */
    private static final int MAX_LINE_LENGTH = 4096;


    // ----------------------------------------------------- Instance Variables


    /**
     * 该对象内部包含 byteBuffer 是真正存放数据的容器
     */
    protected InputBuffer ib;


    /**
     * 填装了 一行数据的 数组对象 注意这里使用了char 类型 这样直接是字符流 不需要通过charset处理
     */
    protected char[] lineBuffer = null;


    // ----------------------------------------------------------- Constructors


    public CoyoteReader(InputBuffer ib) {
        // 这里指定 上层的 sz 为1 就是为了屏蔽上层 缓冲区的功能
        super(ib, 1);
        this.ib = ib;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Prevent cloning the facade.
     * 该对象不支持 clone
     */
    @Override
    protected Object clone()
        throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }


    // -------------------------------------------------------- Package Methods


    /**
     * Clear facade.
     */
    void clear() {
        ib = null;
    }


    // --------------------------------------------------------- Reader Methods


    @Override
    public void close()
        throws IOException {
        ib.close();
    }


    @Override
    public int read()
        throws IOException {
        return ib.read();
    }


    @Override
    public int read(char[] cbuf)
        throws IOException {
        return ib.read(cbuf, 0, cbuf.length);
    }


    @Override
    public int read(char[] cbuf, int off, int len)
        throws IOException {
        return ib.read(cbuf, off, len);
    }


    @Override
    public long skip(long n)
        throws IOException {
        return ib.skip(n);
    }


    @Override
    public boolean ready()
        throws IOException {
        return ib.ready();
    }


    @Override
    public boolean markSupported() {
        return true;
    }


    @Override
    public void mark(int readAheadLimit)
        throws IOException {
        ib.mark(readAheadLimit);
    }


    @Override
    public void reset()
        throws IOException {
        ib.reset();
    }


    /**
     * 按行读取数据  这里不详细看了
     * @return
     * @throws IOException
     */
    @Override
    public String readLine()
        throws IOException {

        // 当用于存放行数据的数组 还没有被初始化时 在这里进行初始化
        if (lineBuffer == null) {
            lineBuffer = new char[MAX_LINE_LENGTH];
       }

        String result = null;

        int pos = 0;
        int end = -1;
        int skip = -1;
        StringBuilder aggregator = null;
        while (end < 0) {
            // 标记buf中的该位置
            mark(MAX_LINE_LENGTH);
            while ((pos < MAX_LINE_LENGTH) && (end < 0)) {
                // 开始将数据读取到 buf 中
                int nRead = read(lineBuffer, pos, MAX_LINE_LENGTH - pos);
                if (nRead < 0) {
                    if (pos == 0 && aggregator == null) {
                        return null;
                    }
                    end = pos;
                    skip = pos;
                }
                // 这里再 遍历读取到的数据
                for (int i = pos; (i < (pos + nRead)) && (end < 0); i++) {
                    // 如果发现了换行符
                    if (lineBuffer[i] == LINE_SEP[0]) {
                        end = i;
                        // 这里记录跳过的字符 也就是 被跳过的 空格所对应的下标
                        skip = i + 1;
                        char nextchar;
                        // 如果刚好读取到的最后一个字符是 换行 这里又读取了一个字符
                        if (i == (pos + nRead - 1)) {
                            nextchar = (char) read();
                        } else {
                            // 这里将 nextchar 指向下一个字符
                            nextchar = lineBuffer[i+1];
                        }
                        if (nextchar == LINE_SEP[1]) {
                            skip++;
                        }
                    } else if (lineBuffer[i] == LINE_SEP[1]) {
                        end = i;
                        skip = i + 1;
                    }
                }
                if (nRead > 0) {
                    pos += nRead;
                }
            }
            if (end < 0) {
                if (aggregator == null) {
                    aggregator = new StringBuilder();
                }
                aggregator.append(lineBuffer);
                pos = 0;
            } else {
                reset();
                skip(skip);
            }
        }

        if (aggregator == null) {
            result = new String(lineBuffer, 0, end);
        } else {
            aggregator.append(lineBuffer, 0, end);
            result = aggregator.toString();
        }

        return result;

    }


}
