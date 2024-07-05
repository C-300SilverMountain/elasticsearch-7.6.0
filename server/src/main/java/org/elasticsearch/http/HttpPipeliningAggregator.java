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
package org.elasticsearch.http;

import org.elasticsearch.common.collect.Tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class HttpPipeliningAggregator<Response extends HttpPipelinedMessage, Listener> {

    private final int maxEventsHeld;
    private final PriorityQueue<Tuple<Response, Listener>> outboundHoldingQueue;
    /*
     * The current read and write sequence numbers. Read sequence numbers are attached to requests in the order they are read from the
     * channel, and then transferred to responses. A response is not written to the channel context until its sequence number matches the
     * current write sequence, implying that all preceding messages have been written.
     */
    private int readSequence;
    private int writeSequence;

    public HttpPipeliningAggregator(int maxEventsHeld) {
        this.maxEventsHeld = maxEventsHeld;
        //优先队列的作用是能保证每次取出的元素都是队列中权值最小的
        //https://juejin.cn/post/7136936945321508878
        this.outboundHoldingQueue = new PriorityQueue<>(1, Comparator.comparing(Tuple::v1));
    }

    public <Request> HttpPipelinedRequest<Request> read(final Request request) {
        return new HttpPipelinedRequest<>(readSequence++, request);
    }

    public List<Tuple<Response, Listener>> write(final Response response, Listener listener) {
        if (outboundHoldingQueue.size() < maxEventsHeld) {
            ArrayList<Tuple<Response, Listener>> readyResponses = new ArrayList<>();
            outboundHoldingQueue.add(new Tuple<>(response, listener));
            while (!outboundHoldingQueue.isEmpty()) {
                /*
                 * Since the response with the lowest sequence number is the top of the priority queue, we know if its sequence
                 * number does not match the current write sequence number then we have not processed all preceding responses yet.
                 */
                //https://juejin.cn/post/7136936945321508878
                // peek():查看下个元素的内容，如果没有返回null   (仅查看，不会弹出 元素)
                // poll():获取下个元素，如果没有返回null        (返回且会弹出 元素)
                final Tuple<Response, Listener> top = outboundHoldingQueue.peek();

                if (top.v1().getSequence() != writeSequence) {
                    break;
                }

                outboundHoldingQueue.poll();
                readyResponses.add(top);
                //确保处理的序号从小到大
                writeSequence++;
            }

            return readyResponses;
        } else {
            int eventCount = outboundHoldingQueue.size() + 1;
            throw new IllegalStateException("Too many pipelined events [" + eventCount + "]. Max events allowed ["
                + maxEventsHeld + "].");
        }
    }

    public List<Tuple<Response, Listener>> removeAllInflightResponses() {
        ArrayList<Tuple<Response, Listener>> responses = new ArrayList<>(outboundHoldingQueue);
        outboundHoldingQueue.clear();
        return responses;
    }
}
