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

package org.elasticsearch.transport.netty4;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import io.netty.util.CharsetUtil;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.transport.Transports;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Random;

/**
 * A handler (must be the last one!) that does size based frame decoding and forwards the actual message
 * to the relevant action.
 */
final class Netty4MessageChannelHandler extends ChannelDuplexHandler {

    private long date = new Random().nextLong();
    private final Netty4Transport transport;

    private final Queue<WriteOperation> queuedWrites = new ArrayDeque<>();

    private WriteOperation currentWrite;

    public long getDate() {
        return date;
    }

    Netty4MessageChannelHandler(Netty4Transport transport) {
        this.transport = transport;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        assert Transports.assertTransportThread();
        assert msg instanceof ByteBuf : "Expected message type ByteBuf, found: " + msg.getClass();

        final ByteBuf buffer = (ByteBuf) msg;
        try {
//            System.out.println("接收到客户端的消息:" + buffer.toString(CharsetUtil.UTF_8));

            Channel channel = ctx.channel();
            Attribute<Netty4TcpChannel> channelAttribute = channel.attr(Netty4Transport.CHANNEL_KEY);
            transport.inboundMessage(channelAttribute.get(), Netty4Utils.toBytesReference(buffer));
        } finally {
            buffer.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ExceptionsHelper.maybeDieOnAnotherThread(cause);
        final Throwable unwrapped = ExceptionsHelper.unwrap(cause, ElasticsearchException.class);
        final Throwable newCause = unwrapped != null ? unwrapped : cause;
        Netty4TcpChannel tcpChannel = ctx.channel().attr(Netty4Transport.CHANNEL_KEY).get();
        if (newCause instanceof Error) {
            transport.onException(tcpChannel, new Exception(newCause));
        } else {
            transport.onException(tcpChannel, (Exception) newCause);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        assert msg instanceof ByteBuf;
        final boolean queued = queuedWrites.offer(new WriteOperation((ByteBuf) msg, promise));
        assert queued;
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            doFlush(ctx);
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        if (channel.isWritable() || channel.isActive() == false) {
            doFlush(ctx);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        doFlush(ctx);
        super.channelInactive(ctx);
    }

    private void doFlush(ChannelHandlerContext ctx) {
        assert ctx.executor().inEventLoop();
        final Channel channel = ctx.channel();
        if (channel.isActive() == false) {
            if (currentWrite != null) {
                currentWrite.promise.tryFailure(new ClosedChannelException());
            }
            failQueuedWrites();
            return;
        }
        while (channel.isWritable()) {
            if (currentWrite == null) {
                currentWrite = queuedWrites.poll();
            }
            if (currentWrite == null) {
                break;
            }
            final WriteOperation write = currentWrite;
            if (write.buf.readableBytes() == 0) {
                write.promise.trySuccess();
                currentWrite = null;
                continue;
            }
            final int readableBytes = write.buf.readableBytes();
            final int bufferSize = Math.min(readableBytes, 1 << 18);
            final int readerIndex = write.buf.readerIndex();
            final boolean sliced = readableBytes != bufferSize;
            final ByteBuf writeBuffer;
            if (sliced) {
                writeBuffer = write.buf.retainedSlice(readerIndex, bufferSize);
                write.buf.readerIndex(readerIndex + bufferSize);
            } else {
                writeBuffer = write.buf;
            }
            // 接收 ack,代表发送成功
            final ChannelFuture writeFuture = ctx.write(writeBuffer);
            if (sliced == false || write.buf.readableBytes() == 0) {
                currentWrite = null;
                writeFuture.addListener(future -> {
                    assert ctx.executor().inEventLoop();
                    if (future.isSuccess()) {
                        write.promise.trySuccess();
                    } else {
                        write.promise.tryFailure(future.cause());
                    }
                });
            } else {
                writeFuture.addListener(future -> {
                    assert ctx.executor().inEventLoop();
                    if (future.isSuccess() == false) {
                        write.promise.tryFailure(future.cause());
                    }
                });
            }
            ctx.flush();
            if (channel.isActive() == false) {
                failQueuedWrites();
                return;
            }
        }
    }

    private void failQueuedWrites() {
        WriteOperation queuedWrite;
        while ((queuedWrite = queuedWrites.poll()) != null) {
            queuedWrite.promise.tryFailure(new ClosedChannelException());
        }
    }

    private static final class WriteOperation {

        private final ByteBuf buf;

        private final ChannelPromise promise;

        WriteOperation(ByteBuf buf, ChannelPromise promise) {
            this.buf = buf;
            this.promise = promise;
        }
    }
}
