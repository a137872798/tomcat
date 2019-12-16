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
package org.apache.tomcat.util.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;

import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.res.StringManager;

/**
 * This class is used to contain standard internet message headers,
 * used for SMTP (RFC822) and HTTP (RFC2068) messages as well as for
 * MIME (RFC 2045) applications such as transferring typed data and
 * grouping related items in multipart message bodies.
 *
 * <P> Message headers, as specified in RFC822, include a field name
 * and a field body.  Order has no semantic significance, and several
 * fields with the same name may exist.  However, most fields do not
 * (and should not) exist more than once in a header.
 *
 * <P> Many kinds of field body must conform to a specified syntax,
 * including the standard parenthesized comment syntax.  This class
 * supports only two simple syntaxes, for dates and integers.
 *
 * <P> When processing headers, care must be taken to handle the case of
 * multiple same-name fields correctly.  The values of such fields are
 * only available as strings.  They may be accessed by index (treating
 * the header as an array of fields), or by name (returning an array
 * of string values).
 */

/* Headers are first parsed and stored in the order they are
   received. This is based on the fact that most servlets will not
   directly access all headers, and most headers are single-valued.
   ( the alternative - a hash or similar data structure - will add
   an overhead that is not needed in most cases )

   Apache seems to be using a similar method for storing and manipulating
   headers.

   Future enhancements:
   - hash the headers the first time a header is requested ( i.e. if the
   servlet needs direct access to headers).
   - scan "common" values ( length, cookies, etc ) during the parse
   ( addHeader hook )

*/


/**
 *  Memory-efficient repository for Mime Headers. When the object is recycled, it
 *  will keep the allocated headers[] and all the MimeHeaderField - no GC is generated.
 *
 *  For input headers it is possible to use the MessageByte for Fields - so no GC
 *  will be generated.
 *
 *  The only garbage is generated when using the String for header names/values -
 *  this can't be avoided when the servlet calls header methods, but is easy
 *  to avoid inside tomcat. The goal is to use _only_ MessageByte-based Fields,
 *  and reduce to 0 the memory overhead of tomcat.
 *
 *  TODO:
 *  XXX one-buffer parsing - for http ( other protocols don't need that )
 *  XXX remove unused methods
 *  XXX External enumerations, with 0 GC.
 *  XXX use HeaderName ID
 *
 *
 * @author dac@eng.sun.com
 * @author James Todd [gonzo@eng.sun.com]
 * @author Costin Manolache
 * @author kevin seguin
 * 多媒体数据头
 */
public class MimeHeaders {
    /** Initial size - should be == average number of headers per request
     *  XXX  make it configurable ( fine-tuning of web-apps )
     *  默认的数据头大小
     */
    public static final int DEFAULT_HEADER_SIZE=8;

    private static final StringManager sm =
            StringManager.getManager("org.apache.tomcat.util.http");

    /**
     * The header fields.
     * fields 内部每个字段都有 name/value 2个属性
     */
    private MimeHeaderField[] headers = new
        MimeHeaderField[DEFAULT_HEADER_SIZE];

    /**
     * The current number of header fields.
     * 记录当前header 字段数
     */
    private int count;

    /**
     * The limit on the number of header fields.
     * 头部数量限制
     */
    private int limit = -1;

    /**
     * Creates a new MimeHeaders object using a default buffer size.
     */
    public MimeHeaders() {
        // NO-OP
    }

    /**
     * Set limit on the number of header fields.
     * @param limit The new limit
     *              设置限制量
     */
    public void setLimit(int limit) {
        this.limit = limit;
        // 如果当前headers 数组的长度 已经超过了limit 而内部实际存放的长度还不及 limit 这时才可以进行拷贝 否则可能会丢失部分数据而放弃拷贝
        if (limit > 0 && headers.length > limit && count < limit) {
            // shrink header list array
            MimeHeaderField tmp[] = new MimeHeaderField[limit];
            System.arraycopy(headers, 0, tmp, 0, count);
            headers = tmp;
        }
    }

    /**
     * Clears all header fields.
     */
    // [seguin] added for consistency -- most other objects have recycle().
    public void recycle() {
        clear();
    }

    /**
     * Clears all header fields.
     * 将每个字段进行置空
     */
    public void clear() {
        for (int i = 0; i < count; i++) {
            headers[i].recycle();
        }
        count = 0;
    }

    /**
     * EXPENSIVE!!!  only for debugging.
     */
    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("=== MimeHeaders ===");
        Enumeration<String> e = names();
        while (e.hasMoreElements()) {
            String n = e.nextElement();
            Enumeration<String> ev = values(n);
            while (ev.hasMoreElements()) {
                pw.print(n);
                pw.print(" = ");
                pw.println(ev.nextElement());
            }
        }
        return sw.toString();
    }


    /**
     * 根据 源数据生成一份数据
     * @param source
     * @throws IOException
     */
    public void duplicate(MimeHeaders source) throws IOException {
        for (int i = 0; i < source.size(); i++) {
            // 创建多媒体头字段
            MimeHeaderField mhf = createHeader();
            // 将source 的数据拷贝达到 mhf中
            mhf.getName().duplicate(source.getName(i));
            mhf.getValue().duplicate(source.getValue(i));
        }
    }


    // -------------------- Idx access to headers ----------

    /**
     * @return the current number of header fields.
     */
    public int size() {
        return count;
    }

    /**
     * @param n The header index
     * @return the Nth header name, or null if there is no such header.
     * This may be used to iterate through all header fields.
     * name / value 字段都是 MessageBytes 类型的 通过数组下标定位到数据 并获取相关属性
     */
    public MessageBytes getName(int n) {
        return n >= 0 && n < count ? headers[n].getName() : null;
    }

    /**
     * @param n The header index
     * @return the Nth header value, or null if there is no such header.
     * This may be used to iterate through all header fields.
     */
    public MessageBytes getValue(int n) {
        return n >= 0 && n < count ? headers[n].getValue() : null;
    }

    /**
     * Find the index of a header with the given name.
     * @param name The header name
     * @param starting Index on which to start looking  代表查询的起始下标
     * @return the header index
     * 通过name 查找某个多媒体头
     */
    public int findHeader( String name, int starting ) {
        // We can use a hash - but it's not clear how much
        // benefit you can get - there is an  overhead
        // and the number of headers is small (4-5 ?)
        // Another problem is that we'll pay the overhead
        // of constructing the hashtable

        // A custom search tree may be better
        for (int i = starting; i < count; i++) {
            if (headers[i].getName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    // -------------------- --------------------

    /**
     * Returns an enumeration of strings representing the header field names.
     * Field names may appear multiple times in this enumeration, indicating
     * that multiple fields with that name exist in this header.
     * @return the enumeration
     */
    public Enumeration<String> names() {
        return new NamesEnumerator(this);
    }

    public Enumeration<String> values(String name) {
        return new ValuesEnumerator(this, name);
    }

    // -------------------- Adding headers --------------------


    /**
     * Adds a partially constructed field to the header.  This
     * field has not had its name or value initialized.
     * 创建 header 字段
     */
    private MimeHeaderField createHeader() {
        if (limit > -1 && count >= limit) {
            throw new IllegalStateException(sm.getString(
                    "headers.maxCountFail", Integer.valueOf(limit)));
        }
        MimeHeaderField mh;
        // 获取头部的长度
        int len = headers.length;
        // count 怎么能比 len 大???
        if (count >= len) {
            // expand header list array
            int newLength = count * 2;
            if (limit > 0 && newLength > limit) {
                newLength = limit;
            }
            // 进行扩容
            MimeHeaderField tmp[] = new MimeHeaderField[newLength];
            System.arraycopy(headers, 0, tmp, 0, len);
            headers = tmp;
        }
        // 填充数组
        if ((mh = headers[count]) == null) {
            headers[count] = mh = new MimeHeaderField();
        }
        count++;
        return mh;
    }

    /**
     * Create a new named header , return the MessageBytes
     * container for the new value
     * @param name The header name
     * @return the message bytes container for the value
     * 指定name 添加一个 mimeHeader 同时返回一个value
     */
    public MessageBytes addValue( String name ) {
         MimeHeaderField mh = createHeader();
        mh.getName().setString(name);
        return mh.getValue();
    }

    /**
     * Create a new named header using un-translated byte[].
     * The conversion to chars can be delayed until
     * encoding is known.
     * @param b The header name bytes
     * @param startN Offset
     * @param len Length
     * @return the message bytes container for the value
     * 通过从 b 中指定起始偏移量 和 len 来截取name
     */
    public MessageBytes addValue(byte b[], int startN, int len)
    {
        MimeHeaderField mhf=createHeader();
        mhf.getName().setBytes(b, startN, len);
        return mhf.getValue();
    }

    /**
     * Allow "set" operations, which removes all current values
     * for this header.
     * @param name The header name
     * @return the message bytes container for the value
     * 这个方法为什么叫 setValue 呢  只有一个 name 属性 而 mimeHeader.getValue 是一个空对象啊
     */
    public MessageBytes setValue( String name ) {
        for ( int i = 0; i < count; i++ ) {
            if(headers[i].getName().equalsIgnoreCase(name)) {
                for ( int j=i+1; j < count; j++ ) {
                    // 之后发现同名的要移除
                    if(headers[j].getName().equalsIgnoreCase(name)) {
                        removeHeader(j--);
                    }
                }
                return headers[i].getValue();
            }
        }
        MimeHeaderField mh = createHeader();
        mh.getName().setString(name);
        return mh.getValue();
    }

    //-------------------- Getting headers --------------------
    /**
     * Finds and returns a header field with the given name.  If no such
     * field exists, null is returned.  If more than one such field is
     * in the header, an arbitrary one is returned.
     * @param name The header name
     * @return the value
     */
    public MessageBytes getValue(String name) {
        for (int i = 0; i < count; i++) {
            if (headers[i].getName().equalsIgnoreCase(name)) {
                return headers[i].getValue();
            }
        }
        return null;
    }

    /**
     * Finds and returns a unique header field with the given name. If no such
     * field exists, null is returned. If the specified header field is not
     * unique then an {@link IllegalArgumentException} is thrown.
     * @param name The header name
     * @return the value if unique
     * @throws IllegalArgumentException if the header has multiple values
     */
    public MessageBytes getUniqueValue(String name) {
        MessageBytes result = null;
        for (int i = 0; i < count; i++) {
            if (headers[i].getName().equalsIgnoreCase(name)) {
                if (result == null) {
                    result = headers[i].getValue();
                } else {
                    throw new IllegalArgumentException();
                }
            }
        }
        return result;
    }

    // bad shortcut - it'll convert to string ( too early probably,
    // encoding is guessed very late )
    public String getHeader(String name) {
        MessageBytes mh = getValue(name);
        return mh != null ? mh.toString() : null;
    }

    // -------------------- Removing --------------------
    /**
     * Removes a header field with the specified name.  Does nothing
     * if such a field could not be found.
     * @param name the name of the header field to be removed
     */
    public void removeHeader(String name) {
        // XXX
        // warning: rather sticky code; heavily tuned

        for (int i = 0; i < count; i++) {
            if (headers[i].getName().equalsIgnoreCase(name)) {
                removeHeader(i--);
            }
        }
    }

    /**
     * reset and swap with last header
     * @param idx the index of the header to remove.
     */
    private void removeHeader(int idx) {
        MimeHeaderField mh = headers[idx];

        mh.recycle();
        headers[idx] = headers[count - 1];
        headers[count - 1] = mh;
        count--;
    }

}

/** Enumerate the distinct header names.
    Each nextElement() is O(n) ( a comparison is
    done with all previous elements ).

    This is less frequent than add() -
    we want to keep add O(1).
    一个过时的迭代器接口
*/
class NamesEnumerator implements Enumeration<String> {
    // 当前指针
    private int pos;
    // 内部有多少元素
    private final int size;
    // 获取下个元素
    private String next;
    // 这里header 来源于哪个多媒体头对象
    private final MimeHeaders headers;

    public NamesEnumerator(MimeHeaders headers) {
        this.headers=headers;
        pos=0;
        size = headers.size();
        findNext();
    }

    /**
     * 从 mimeHeaders 中获取元素
     */
    private void findNext() {
        next=null;
        for(; pos< size; pos++ ) {
            next=headers.getName( pos ).toString();
            // 定位到某一位置后 如果发现前面有一样的name  继续往后查询
            for( int j=0; j<pos ; j++ ) {
                if( headers.getName( j ).equalsIgnoreCase( next )) {
                    // duplicate.
                    next=null;
                    break;
                }
            }
            if( next!=null ) {
                // it's not a duplicate
                break;
            }
        }
        // next time findNext is called it will try the
        // next element
        pos++;
    }

    @Override
    public boolean hasMoreElements() {
        return next!=null;
    }

    @Override
    public String nextElement() {
        String current=next;
        findNext();
        return current;
    }
}

/** Enumerate the values for a (possibly ) multiple
    value element.
*/
class ValuesEnumerator implements Enumeration<String> {
    private int pos;
    private final int size;
    private MessageBytes next;
    private final MimeHeaders headers;
    // 当前停留 value 对应的 name
    private final String name;

    ValuesEnumerator(MimeHeaders headers, String name) {
        this.name=name;
        this.headers=headers;
        pos=0;
        size = headers.size();
        findNext();
    }

    /**
     * 查询下一个value
     */
    private void findNext() {
        next=null;
        for(; pos< size; pos++ ) {
            MessageBytes n1=headers.getName( pos );
            if( n1.equalsIgnoreCase( name )) {
                next=headers.getValue( pos );
                break;
            }
        }
        pos++;
    }

    @Override
    public boolean hasMoreElements() {
        return next!=null;
    }

    @Override
    public String nextElement() {
        MessageBytes current=next;
        findNext();
        return current.toString();
    }
}

/**
 * 多媒体数据头字段
 */
class MimeHeaderField {

    // 每个字段包含一个  name/value 属性
    private final MessageBytes nameB = MessageBytes.newInstance();
    private final MessageBytes valueB = MessageBytes.newInstance();

    /**
     * Creates a new, uninitialized header field.
     */
    public MimeHeaderField() {
        // NO-OP
    }

    public void recycle() {
        nameB.recycle();
        valueB.recycle();
    }

    public MessageBytes getName() {
        return nameB;
    }

    public MessageBytes getValue() {
        return valueB;
    }
}
