package org.elasticsearch.test.nettyhttp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 1、实例要求：使用 IDEA 创建 Netty 项目
 * 2、Netty 服务器在 6668 端口监听，浏览器发出请求 http://localhost:6668/
 * 3、服务器可以回复消息给客户端"Hello!我是服务器5",并对特定请求资源进行过滤。
 * 4、目的：Netty 可以做 Http 服务开发，并且理解 Handler 实例和客户端及其请求的关系。
 *
 * @author: chenwm
 * @since: 2023/10/20 14:21
 *
 */
public class TestServer {

    public static void main(String[] args) throws Exception {

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();

            serverBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).childHandler(new TestServerInitializer());

            ChannelFuture channelFuture = serverBootstrap.bind(6668).sync();

            channelFuture.channel().closeFuture().sync();

        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
