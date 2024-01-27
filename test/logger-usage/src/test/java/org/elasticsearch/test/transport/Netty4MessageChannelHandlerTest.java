package org.elasticsearch.test.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.DefaultPromise;

import java.util.concurrent.CountDownLatch;

/**
 * @author: chenwm
 * @since: 2023/12/15 9:53
 */
public class Netty4MessageChannelHandlerTest {
    public static void main(String[] args) throws InterruptedException {
        // 启动后，cmd中执行: telnet localhost 8090 > 按下 Ctrl + ]键  > send hello world
        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup(3);

        new ServerBootstrap()
            .channel(NioServerSocketChannel.class)
            .group(boss, worker)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socketChannel) throws Exception {
                    socketChannel.pipeline()
                        .addLast("decoder", new StringDecoder())
                        .addLast("encoder", new StringEncoder())
                        .addLast("A", new ZidanHandler())
                        .addLast("B", new ZidanHandler())
                        .addLast("C", new ZidanHandler());
                }
            }).bind(8090).sync();
    }
}

/**
 * 双向处理器
 */
class ZidanHandler extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println(ctx.name() + " channelRead: " + msg);
        String result = (String) msg;

        if (("ctx.close." + ctx.name()).equals(result)) {
            ctx.close();
        } else if (("ctx.channel.close." + ctx.name()).equals(result)) {
            ctx.channel().close();
        } else {
//            ctx.fireChannelRead(msg);
            ChannelPromise promise = new DefaultChannelPromise(ctx.channel());
            ChannelFuture writeFuture = ctx.write(String.format("[%s]%s", ctx.name(), msg), promise);
            CountDownLatch latch = new CountDownLatch(1);
            writeFuture.addListener(future -> {
                System.out.println(future.get());
                System.out.println(future.isSuccess());

                latch.countDown();
            });
            ctx.flush();

            latch.await();
        }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        System.out.println(ctx.name() + " close");
        ctx.close(promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        System.out.println(ctx.name() + " write");
        ChannelFuture writeFuture = ctx.write(String.format("[%s]%s", ctx.name(), msg), promise);
        CountDownLatch latch = new CountDownLatch(1);
        writeFuture.addListener(future -> {
            System.out.println(future.get());
            System.out.println(future.isSuccess());

            latch.countDown();
        });
        ctx.flush();

        latch.await();

    }
}
