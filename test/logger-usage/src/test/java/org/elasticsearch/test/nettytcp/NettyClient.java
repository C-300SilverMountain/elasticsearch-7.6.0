package org.elasticsearch.test.nettytcp;


import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringEncoder;

public class NettyClient {

    public static void main(String[] args) throws Exception {

        //客户端需要一个事件循环组
        EventLoopGroup group = new NioEventLoopGroup();
        try {

            //创建客户端启动对象
            //注意客户端使用的不是 ServerBootstrap 而是 Bootstrap
            Bootstrap bootstrap = new Bootstrap();
            //设置相关参数
            bootstrap.group(group) //设置线程组
                .channel(NioSocketChannel.class) // 设置客户端通道的实现类(反射)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {

                        //字符串编码器，一定要加在SimpleClientHandler 的上面
                        // 定义处理发送到远程服务器信息的处理器：OutboundHandler （发送数据时，必须）
                        ch.pipeline().addLast(new StringEncoder());

                        // 处理来自远程服务器信息的处理器：粘包拆包
                        ch.pipeline().addLast(new DelimiterBasedFrameDecoder(
                            Integer.MAX_VALUE, Delimiters.lineDelimiter()[0]));
                        // 定义处理来自远程服务器信息的处理器：InboundHandler （读取信息时，必须）
                        ch.pipeline().addLast(new NettyClientHandler()); //加入自己的处理器
                    }
                });

            System.out.println("客户端 ok..");
            //启动客户端去连接服务器端
            //关于 ChannelFuture 要分析，涉及到netty的异步模型
            ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", 6668).sync();


            // 客户端主动向服务器发送消息
            for(int i=0;i<5;i++){
                String msg = "hello boy!!! "+i+"\r\n";
                channelFuture.channel().writeAndFlush(msg);
            }
            //给关闭通道进行监听
            channelFuture.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
