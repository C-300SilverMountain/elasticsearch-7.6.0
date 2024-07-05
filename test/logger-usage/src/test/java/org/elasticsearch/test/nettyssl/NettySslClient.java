package org.elasticsearch.test.nettyssl;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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

public class NettySslClient {

    private static final String HOST = "localhost";
    private static final int PORT = 8888;

    public static void main(String[] args) throws Exception {
        // 加载SSL证书
        String serverKeyStorePath = "G:\\top1000\\keystore.jks";
        String clientKeyStorePath = "G:\\top1000\\keystore.jks";
        String serverKeyStorePassword = "happya";
        String clientKeyStorePassword = "happya";

        // 创建SslContext
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream keyStoreInputStream = new FileInputStream(clientKeyStorePath)) {
            keyStore.load(keyStoreInputStream, clientKeyStorePassword.toCharArray());
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, clientKeyStorePassword.toCharArray());

        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream trustStoreInputStream = new FileInputStream(serverKeyStorePath)) {
            trustStore.load(trustStoreInputStream, serverKeyStorePassword.toCharArray());
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // 创建EventLoopGroup
        EventLoopGroup group = new NioEventLoopGroup();
        SslContext sslContext = SslContextBuilder.forClient()
            .keyManager(keyManagerFactory)
            .trustManager(trustManagerFactory)
            .sslProvider(SslProvider.OPENSSL)
            .build();
        try {
            // 创建客户端Bootstrap
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 在ChannelPipeline中添加SslHandler
                        pipeline.addLast(sslContext.newHandler(ch.alloc()));
                        // 添加加密通信处理器
                        pipeline.addLast(new SecureChatClientHandler());
                    }
                });

            // 连接服务器
            System.out.println("======Begin to start ssl client======");
            ChannelFuture future = bootstrap.connect(HOST, PORT).sync();
            System.out.println("======Ssl client started======");
            future.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    public static class SecureChatClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 连接建立时，发送一条消息给服务器
            System.out.println("Client channel active : " + ctx.channel().toString());
            ctx.channel().writeAndFlush(Unpooled.wrappedBuffer("Hello from client!\n".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf byteBuf = (ByteBuf) msg;
            System.out.println("Client received message: \n" + byteBuf.toString(CharsetUtil.UTF_8));
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
