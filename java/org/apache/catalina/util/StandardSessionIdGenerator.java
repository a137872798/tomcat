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

/**
 * sessionGenerator 标准实现
 */
public class StandardSessionIdGenerator extends SessionIdGeneratorBase {

    /**
     * 在指定路径的基础上 构建sessionId  该方法不细看
     *
     * @param route node identifier to include in generated id
     * @return
     */
    @Override
    public String generateSessionId(String route) {

        // 创建 sessionId 长度的数组
        byte random[] = new byte[16];
        // 获取sessionId 的实际长度
        int sessionIdLength = getSessionIdLength();

        // Render the result as a String of hexadecimal digits
        // Start with enough space for sessionIdLength and medium route size
        // 这里生成指定大小的 strbuilder
        StringBuilder buffer = new StringBuilder(2 * sessionIdLength + 20);

        int resultLenBytes = 0;

        // 当读取的结果 小于指定长度时
        while (resultLenBytes < sessionIdLength) {
            // 这里使用secureRandom 填充数组对象
            getRandomBytes(random);
            for (int j = 0; j < random.length && resultLenBytes < sessionIdLength; j++) {
                byte b1 = (byte) ((random[j] & 0xf0) >> 4);
                byte b2 = (byte) (random[j] & 0x0f);
                if (b1 < 10)
                    buffer.append((char) ('0' + b1));
                else
                    buffer.append((char) ('A' + (b1 - 10)));
                if (b2 < 10)
                    buffer.append((char) ('0' + b2));
                else
                    buffer.append((char) ('A' + (b2 - 10)));
                resultLenBytes++;
            }
        }

        if (route != null && route.length() > 0) {
            buffer.append('.').append(route);
        } else {
            String jvmRoute = getJvmRoute();
            if (jvmRoute != null && jvmRoute.length() > 0) {
                buffer.append('.').append(jvmRoute);
            }
        }

        return buffer.toString();
    }
}
