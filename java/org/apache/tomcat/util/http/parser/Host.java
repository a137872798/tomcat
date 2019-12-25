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
package org.apache.tomcat.util.http.parser;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;

public class Host {

    /**
     * Parse the given input as an HTTP Host header value.
     *
     * @param mb The host header value
     *
     * @return The position of ':' that separates the host from the port or -1
     *         if it is not present
     *
     * @throws IllegalArgumentException If the host header value is not
     *         specification compliant
     */
    public static int parse(MessageBytes mb) {
        return parse(new MessageBytesReader(mb));
    }


    /**
     * Parse the given input as an HTTP Host header value.
     *
     * @param string The host header value
     *
     * @return The position of ':' that separates the host from the port or -1
     *         if it is not present
     *
     * @throws IllegalArgumentException If the host header value is not
     *         specification compliant
     */
    public static int parse(String string) {
        return parse(new StringReader(string));
    }


    /**
     * 从数据流中解析出端口  一般来说 host 都是 完整的域名 不会直接暴露出 端口 比如  www.baidu.com
     * @param reader
     * @return
     */
    private static int parse(Reader reader) {
        try {
            // 首先尝试读取一个字节 并进行回退
            reader.mark(1);
            int first = reader.read();
            reader.reset();
            // 判断是否是字母
            if (HttpParser.isAlpha(first)) {
                // 读取域名  这里是 找到 : 所在的下标  如果是 www.baidu.com 这种 会返回-1
                return HttpParser.readHostDomainName(reader);
                // 如果是数字 读取端口
            } else if (HttpParser.isNumeric(first)) {
                return HttpParser.readHostIPv4(reader, false);
                // 忽略
            } else if ('[' == first) {
                return HttpParser.readHostIPv6(reader);
            } else {
                // Invalid
                throw new IllegalArgumentException();
            }
        } catch (IOException ioe) {
            // Should never happen
            throw new IllegalArgumentException(ioe);
        }
    }


    private static class MessageBytesReader extends Reader {

        private final byte[] bytes;
        private final int end;
        private int pos;
        private int mark;

        public MessageBytesReader(MessageBytes mb) {
            ByteChunk bc = mb.getByteChunk();
            bytes = bc.getBytes();
            pos = bc.getOffset();
            end = bc.getEnd();
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                // Want output in range 0 to 255, not -128 to 127
                cbuf[i] = (char) (bytes[pos++] & 0xFF);
            }
            return len;
        }

        @Override
        public void close() throws IOException {
            // NO-OP
        }

        // Over-ridden methods to improve performance

        @Override
        public int read() throws IOException {
            if (pos < end) {
                // Want output in range 0 to 255, not -128 to 127
                return bytes[pos++] & 0xFF;
            } else {
                return -1;
            }
        }

        // Methods to support mark/reset

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readAheadLimit) throws IOException {
            mark = pos;
        }

        @Override
        public void reset() throws IOException {
            pos = mark;
        }
    }
}
