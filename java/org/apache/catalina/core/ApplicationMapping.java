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
package org.apache.catalina.core;

import org.apache.catalina.mapper.MappingData;
import org.apache.catalina.servlet4preview.http.HttpServletMapping;
import org.apache.catalina.servlet4preview.http.MappingMatch;

/**
 * 应用相关的映射对象
 */
public class ApplicationMapping {

    /**
     * 映射数据
     */
    private final MappingData mappingData;

    /**
     * 映射对象  可以直接将 <path,,servlet> 挂在里面
     */
    private volatile HttpServletMapping mapping = null;

    public ApplicationMapping(MappingData mappingData) {
        this.mappingData = mappingData;
    }

    /**
     * 获取映射对象
     * @return
     */
    public HttpServletMapping getHttpServletMapping() {
        if (mapping == null) {
            String servletName;
            // wrapper(container) 就是指代了 servlet
            if (mappingData.wrapper == null) {
                servletName = "";
            } else {
                servletName = mappingData.wrapper.getName();
            }
            // 如果 mappingData 内部没有指定映射方式
            if (mappingData.matchType == null) {
                // 这里mappingType 传入 null
                mapping = new MappingImpl("", "", null, servletName);
            } else {
                // 否则根据 matchType 创建不同的 mappingImpl
                switch (mappingData.matchType) {
                    case CONTEXT_ROOT:
                        mapping = new MappingImpl("", "", mappingData.matchType, servletName);
                        break;
                    case DEFAULT:
                        mapping = new MappingImpl("", "/", mappingData.matchType, servletName);
                        break;
                    case EXACT:
                        mapping = new MappingImpl(mappingData.wrapperPath.toString().substring(1),
                                mappingData.wrapperPath.toString(), mappingData.matchType, servletName);
                        break;
                    case EXTENSION:
                        String path = mappingData.wrapperPath.toString();
                        int extIndex = path.lastIndexOf('.');
                        mapping = new MappingImpl(path.substring(1, extIndex),
                                "*" + path.substring(extIndex), mappingData.matchType, servletName);
                        break;
                    case PATH:
                        // 先看有关 path 的匹配
                        String matchValue;
                        // 如果没有设置 path信息 那么 matchValue(代表匹配值) 就为null
                        if (mappingData.pathInfo.isNull()) {
                            matchValue = null;
                        } else {
                            // 这里将 最前面的 "/" 去掉
                            matchValue = mappingData.pathInfo.toString().substring(1);
                        }
                        // 应该就是 以 wrapperPath 打头的所有 path 都会交由 servletName 对应的 servlet处理
                        mapping = new MappingImpl(matchValue, mappingData.wrapperPath.toString() + "/*",
                                mappingData.matchType, servletName);
                        break;
                }
            }
        }

        return mapping;
    }

    public void recycle() {
        mapping = null;
    }

    /**
     * HttpServletMapping 接口 可以直接将 path 与 servlet的映射关系设置起来 原本必须要通过解析xml文件的方式
     * 只是一个简单的bean 对象
     */
    private static class MappingImpl implements HttpServletMapping {

        /**
         * 被匹配的值
         */
        private final String matchValue;
        /**
         * 使用的正则
         */
        private final String pattern;
        /**
         * 代表采用的匹配规则 可能是匹配path 也可能是匹配别的东西
         */
        private final MappingMatch mappingType;
        /**
         * 指定的servlet 名字
         */
        private final String servletName;

        public MappingImpl(String matchValue, String pattern, MappingMatch mappingType,
                String servletName) {
            this.matchValue = matchValue;
            this.pattern = pattern;
            this.mappingType = mappingType;
            this.servletName = servletName;
        }

        @Override
        public String getMatchValue() {
            return matchValue;
        }

        @Override
        public String getPattern() {
            return pattern;
        }

        @Override
        public MappingMatch getMappingMatch() {
            return mappingType;
        }

        @Override
        public String getServletName() {
            return servletName;
        }
    }
}
