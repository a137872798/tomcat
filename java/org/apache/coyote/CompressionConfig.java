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
package org.apache.coyote;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.ResponseUtil;
import org.apache.tomcat.util.http.parser.AcceptEncoding;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.res.StringManager;

/**
 * 压缩配置
 */
public class CompressionConfig {

    private static final Log log = LogFactory.getLog(CompressionConfig.class);
    private static final StringManager sm = StringManager.getManager(CompressionConfig.class);

    /**
     * 压缩级别
     */
    private int compressionLevel = 0;
    private Pattern noCompressionUserAgents = null;
    /**
     * 支持压缩的多媒体类型
     */
    private String compressibleMimeType = "text/html,text/xml,text/plain,text/css," +
            "text/javascript,application/javascript,application/json,application/xml";
    /**
     * 当前允许被压缩的多媒体类型
     */
    private String[] compressibleMimeTypes = null;
    /**
     * 启用压缩的 最小长度 也就是 至少大于 2048 才允许进行压缩 不然 额外的解压缩 也会浪费时间
     */
    private int compressionMinSize = 2048;


    /**
     * Set compression level.
     *
     * @param compression One of <code>on</code>, <code>force</code>,
     *                    <code>off</code> or the minimum compression size in
     *                    bytes which implies <code>on</code>
     *                    设置压缩级别
     */
    public void setCompression(String compression) {
        if (compression.equals("on")) {
            this.compressionLevel = 1;
        } else if (compression.equals("force")) {
            this.compressionLevel = 2;
        } else if (compression.equals("off")) {
            this.compressionLevel = 0;
        } else {
            // 其余情况 看作是 设置 compressionMinSize 同时 level 默认为1
            try {
                // Try to parse compression as an int, which would give the
                // minimum compression size
                setCompressionMinSize(Integer.parseInt(compression));
                this.compressionLevel = 1;
            } catch (Exception e) {
                this.compressionLevel = 0;
            }
        }
    }


    /**
     * Return compression level.
     *
     * @return The current compression level in string form (off/on/force)
     * 当前是否打开了 压缩开关
     */
    public String getCompression() {
        switch (compressionLevel) {
        case 0:
            return "off";
        case 1:
            return "on";
        case 2:
            return "force";
        }
        return "off";
    }


    public int getCompressionLevel() {
        return compressionLevel;
    }


    /**
     * Obtain the String form of the regular expression that defines the user
     * agents to not use gzip with.
     *
     * @return The regular expression as a String
     */
    public String getNoCompressionUserAgents() {
        if (noCompressionUserAgents == null) {
            return null;
        } else {
            return noCompressionUserAgents.toString();
        }
    }


    public Pattern getNoCompressionUserAgentsPattern() {
        return noCompressionUserAgents;
    }


    /**
     * Set no compression user agent pattern. Regular expression as supported
     * by {@link Pattern}. e.g.: <code>gorilla|desesplorer|tigrus</code>.
     *
     * @param noCompressionUserAgents The regular expression for user agent
     *                                strings for which compression should not
     *                                be applied
     */
    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        if (noCompressionUserAgents == null || noCompressionUserAgents.length() == 0) {
            this.noCompressionUserAgents = null;
        } else {
            this.noCompressionUserAgents =
                Pattern.compile(noCompressionUserAgents);
        }
    }


    public String getCompressibleMimeType() {
        return compressibleMimeType;
    }


    public void setCompressibleMimeType(String valueS) {
        compressibleMimeType = valueS;
        compressibleMimeTypes = null;
    }


    /**
     * 获取支持的压缩类型
     * @return
     */
    public String[] getCompressibleMimeTypes() {
        String[] result = compressibleMimeTypes;
        if (result != null) {
            return result;
        }
        List<String> values = new ArrayList<>();
        // 根据 "," 进行拆分
        StringTokenizer tokens = new StringTokenizer(compressibleMimeType, ",");
        while (tokens.hasMoreTokens()) {
            String token = tokens.nextToken().trim();
            if (token.length() > 0) {
                values.add(token);
            }
        }
        result = values.toArray(new String[values.size()]);
        compressibleMimeTypes = result;
        return result;
    }


    public int getCompressionMinSize() {
        return compressionMinSize;
    }


    /**
     * Set Minimum size to trigger compression.
     *
     * @param compressionMinSize The minimum content length required for
     *                           compression in bytes
     */
    public void setCompressionMinSize(int compressionMinSize) {
        this.compressionMinSize = compressionMinSize;
    }


    /**
     * Determines if compression should be enabled for the given response and if
     * it is, sets any necessary headers to mark it as such.
     *
     * @param request  The request that triggered the response
     * @param response The response to consider compressing
     *
     * @return {@code true} if compression was enabled for the given response,
     *         otherwise {@code false}
     *         校验是否满足压缩的资格 也就是 判断请求头 中 content-encoding 是否包含 gzip  并往响应头中设置 content-encoding
     */
    public boolean useCompression(Request request, Response response) {
        // Check if compression is enabled
        // 本身关闭了压缩功能 直接返回
        if (compressionLevel == 0) {
            return false;
        }

        // 获取响应头
        MimeHeaders responseHeaders = response.getMimeHeaders();

        // Check if content is not already compressed   获取 content-encoding 属性
        MessageBytes contentEncodingMB = responseHeaders.getValue("Content-Encoding");
        // 如果已经存在 content-encoding 那么判断 是否是gzip 如果是的话 可能已经触发过该方法了  因为在最后才为res 设置 content-encoding 属性
        if (contentEncodingMB != null) {
            // Content-Encoding values are ordered but order is not important
            // for this check so use a Set rather than a List
            Set<String> tokens = new HashSet<>();
            try {
                // 将content-encoding 中的信息解析出来后设置到 tokens 中
                TokenList.parseTokenList(responseHeaders.values("Content-Encoding"), tokens);
            } catch (IOException e) {
                // Because we are using StringReader, any exception here is a
                // Tomcat bug.
                log.warn(sm.getString("compressionConfig.ContentEncodingParseFail"), e);
                return false;
            }
            // 如果压缩方式是  gzip 或者br 放弃压缩
            if (tokens.contains("gzip") || tokens.contains("br")) {
                return false;
            }
        }

        // If force mode, the length and MIME type checks are skipped
        // level = 2 代表强制压缩模式
        if (compressionLevel != 2) {
            // Check if the response is of sufficient length to trigger the compression
            // 非强制压缩的情况 要检查以下 是否可以压缩
            long contentLength = response.getContentLengthLong();
            // 必须超过最小压缩长度 才允许压缩
            if (contentLength != -1 && contentLength < compressionMinSize) {
                return false;
            }

            // Check for compatible MIME-TYPE
            // 获取支持的压缩类型
            String[] compressibleMimeTypes = getCompressibleMimeTypes();
            if (compressibleMimeTypes != null &&
                    // 如果指定的 压缩方式没有包含在内部 则无法压缩
                    !startsWithStringArray(compressibleMimeTypes, response.getContentType())) {
                return false;
            }
        }

        // If processing reaches this far, the response might be compressed.
        // Therefore, set the Vary header to keep proxies happy
        // 上面过滤掉不合适(以及不支持)的压缩方式
        ResponseUtil.addVaryFieldName(responseHeaders, "accept-encoding");

        // Check if user-agent supports gzip encoding
        // Only interested in whether gzip encoding is supported. Other
        // encodings and weights can be ignored.
        // 从请求头中获取 支持的压缩方式
        Enumeration<String> headerValues = request.getMimeHeaders().values("accept-encoding");
        boolean foundGzip = false;
        while (!foundGzip && headerValues.hasMoreElements()) {
            List<AcceptEncoding> acceptEncodings = null;
            try {
                acceptEncodings = AcceptEncoding.parse(new StringReader(headerValues.nextElement()));
            } catch (IOException ioe) {
                // If there is a problem reading the header, disable compression
                return false;
            }

            // 寻找内部 有 gzip 的
            for (AcceptEncoding acceptEncoding : acceptEncodings) {
                if ("gzip".equalsIgnoreCase(acceptEncoding.getEncoding())) {
                    foundGzip = true;
                    break;
                }
            }
        }

        // 必须在 请求头上解析到 gzip 才能进行压缩
        if (!foundGzip) {
            return false;
        }

        // If force mode, the browser checks are skipped
        if (compressionLevel != 2) {
            // Check for incompatible Browser
            Pattern noCompressionUserAgents = this.noCompressionUserAgents;
            if (noCompressionUserAgents != null) {
                MessageBytes userAgentValueMB = request.getMimeHeaders().getValue("user-agent");
                if(userAgentValueMB != null) {
                    String userAgentValue = userAgentValueMB.toString();
                    if (noCompressionUserAgents.matcher(userAgentValue).matches()) {
                        return false;
                    }
                }
            }
        }

        // All checks have passed. Compression is enabled.

        // Compressed content length is unknown so mark it as such.
        response.setContentLength(-1);
        // Configure the content encoding for compressed content
        // 这里强制指定了 压缩方式为 gzip
        responseHeaders.setValue("Content-Encoding").setString("gzip");

        return true;
    }


    /**
     * Checks if any entry in the string array starts with the specified value
     *
     * @param sArray the StringArray
     * @param value string
     */
    private static boolean startsWithStringArray(String sArray[], String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < sArray.length; i++) {
            if (value.startsWith(sArray[i])) {
                return true;
            }
        }
        return false;
    }
}
