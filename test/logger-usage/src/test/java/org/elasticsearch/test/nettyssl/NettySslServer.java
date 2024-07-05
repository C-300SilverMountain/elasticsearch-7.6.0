package org.elasticsearch.test.nettyssl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.CharsetUtil;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

/**
 * https://blog.csdn.net/Josh_scott/article/details/133890164
 */
public class NettySslServer {

    private static final int PORT = 8888;

    public static void main(String[] args) throws Exception {
        // 加载SSL证书
        String serverKeyStorePath = "G:\\top1000\\keystore.jks";
        String clientKeyStorePath = "G:\\top1000\\keystore.jks";
        String serverKeyStorePassword = "happya";
        String clientKeyStorePassword = "happya";

        // 创建SslContext
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream keyStoreInputStream = new FileInputStream(serverKeyStorePath)) {
            keyStore.load(keyStoreInputStream, serverKeyStorePassword.toCharArray());
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, serverKeyStorePassword.toCharArray());

        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream trustStoreInputStream = new FileInputStream(clientKeyStorePath)) {
            trustStore.load(trustStoreInputStream, clientKeyStorePassword.toCharArray());
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SslContext sslContext = SslContextBuilder.forServer(keyManagerFactory)
            .trustManager(trustManagerFactory)
            .sslProvider(SslProvider.OPENSSL)
            .build();
        // 创建EventLoopGroup
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            // 创建服务器Bootstrap
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 在ChannelPipeline中添加SslHandler
                        pipeline.addLast(sslContext.newHandler(ch.alloc()));
                        // 添加加密通信处理器
                        pipeline.addLast(new SecureChatServerHandler());
                    }
                })
                .childOption(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            // 启动服务器
            System.out.println("======Begin to start ssl server======");
            ChannelFuture future = serverBootstrap.bind(PORT).sync();
            System.out.println("======Ssl server started======");
            future.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    public static class SecureChatServerHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 当连接建立时，发送欢迎消息
            System.out.println("Server channel active : " + ctx.channel().toString());
            ctx.channel().writeAndFlush(Unpooled.wrappedBuffer("Welcome to the secure chat server!\n".getBytes(StandardCharsets.UTF_8)));
            ctx.channel().writeAndFlush(Unpooled.wrappedBuffer("Your connection is protected by SSL.\n".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf byteBuf= (ByteBuf) msg;
            System.out.println("Server received message: " + byteBuf.toString(CharsetUtil.UTF_8));
            super.channelRead(ctx, msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            // 处理异常
            cause.printStackTrace();
            ctx.close();
        }
    }
}
