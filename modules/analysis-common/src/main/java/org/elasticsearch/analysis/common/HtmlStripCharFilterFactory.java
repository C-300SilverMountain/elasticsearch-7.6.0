/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractCharFilterFactory;

import java.io.Reader;
import java.util.List;
import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static org.elasticsearch.common.util.set.Sets.newHashSet;

/**
 * html字符过滤器
 * 链接：
 * https://blog.csdn.net/weixin_30439067/article/details/95932705
 * https://juejin.cn/post/7141198973120806926
 * https://www.hebinghua.com/studydetail/10/18.html
 */
public class HtmlStripCharFilterFactory extends AbstractCharFilterFactory {
    private final Set<String> escapedTags;

    HtmlStripCharFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(indexSettings, name);
        List<String> escapedTags = settings.getAsList("escaped_tags");
        if (escapedTags.size() > 0) {
            this.escapedTags = unmodifiableSet(newHashSet(escapedTags));
        } else {
            this.escapedTags = null;
        }
    }

    @Override
    public Reader create(Reader tokenStream) {
        return new HTMLStripCharFilter(tokenStream, escapedTags);
    }
}
