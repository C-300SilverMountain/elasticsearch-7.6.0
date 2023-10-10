package org.elasticsearch.test.loglearning.jdkniobug;


import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.logging.*;
/**
 *
 * @author: chenwm
 * @since: 2023/10/10 11:50
 * @see https://blog.csdn.net/May121812345/article/details/120391457?spm=1001.2014.3001.5502
 *      https://cloud.tencent.com/developer/article/1543810
 *      https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6670302
 *
 */
public class TestServer {
    private static final long SLEEP_PERIOD = 5000L; // 5 seconds
    private static final int BUFFER_SIZE = 8192;
    private int port;

    public TestServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Throwable {


        new TestServer(8989).start();
    }

    public void start() throws Throwable {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        ServerSocket server = serverChannel.socket();
        server.bind(new InetSocketAddress(port));

        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        SocketChannel clientChannel = null;

        System.out.println("0. SERVER STARTED TO LISTEN");
        boolean writeNow = false;

        while (true) {
            try {
                // wait for selection
                int numKeys = selector.select();

                if (numKeys == 0) {
                    //下载jdk6最初版本jdk-6-linux-amd64-rpm.bin，部署到centos上，可观察到，从client断开后，一直打印这里
                    //即select()，并没有阻塞，因为客户端断开后，通道状态发生变化，解除阻塞，以下代码有没有处理断开事件（jdk都没有定义，可查SelectionKey）
                    //就会导致某个通道一直存在【断开事件】，该通道已失效
                    //https://www.oracle.com/java/technologies/javase-java-archive-javase6-downloads.html
                    System.err.println("select wakes up with zero!!!");

                    //netty解决思路：
                    // 统计因【断开事件】唤醒通道次数，当大于阈值时，遍历所有通道，将仍有效的通道注册到新的selector上，然后新selector取缔旧selector
                }

                Iterator it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey selected = (SelectionKey) it.next();
                    int ops = selected.interestOps();

                    try {
                        // process new connection
                        if ((ops & SelectionKey.OP_ACCEPT) != 0) {
                            clientChannel = serverChannel.accept();
                            clientChannel.configureBlocking(false);

                            // register channel to selector
                            clientChannel.register(selector, SelectionKey.OP_READ, null);
                            System.out.println("2. SERVER ACCEPTED AND REGISTER READ OP : client - " + clientChannel.socket().getInetAddress());
                        }

                        if ((ops & SelectionKey.OP_READ) != 0) {
                            // read client message
                            System.out.println("3. SERVER READ DATA FROM client - " + clientChannel.socket().getInetAddress());
                            readClient((SocketChannel) selected.channel(), buffer);

                            // deregister OP_READ
                            System.out.println("PREV SET : " + selected.interestOps());
                            selected.interestOps(selected.interestOps() & ~SelectionKey.OP_READ);
                            System.out.println("NEW SET : " + selected.interestOps());

                            Thread.sleep(SLEEP_PERIOD * 2);
                            new WriterThread(clientChannel).start();
                        }

                    } finally {
                        // remove from selected key set
                        it.remove();
                    }
                }
            } catch (IOException e) {
                System.err.println("IO Error : " + e.getMessage());
            }
        }
    }


    public void readClient(SocketChannel channel, ByteBuffer buffer) throws IOException {
        try {
            buffer.clear();

            int nRead = channel.read(buffer);

            if (nRead < 0) {
                channel.close();
                return;
            }

            if (buffer.position() != 0) {
                int size = buffer.position();
                buffer.flip();
                byte[] bytes = new byte[size];
                buffer.get(bytes);
                System.out.println("RECVED : " + new String(bytes));
            }
        } catch (IOException e) {
            System.err.println("IO Error : " + e.getMessage());
            channel.close();
        }
    }

    static class WriterThread extends Thread {
        private SocketChannel clientChannel;
        public WriterThread(SocketChannel clientChannel) {
            this.clientChannel = clientChannel;
        }

        public void run() {
            try {
                writeClient(clientChannel);
                System.out.println("5. SERVER WRITE DATA TO client - " + clientChannel.socket().getInetAddress());
            } catch (IOException e) {
                System.err.println("5. SERVER WRITE DATA FAILED : " + e);
            }
        }

        public void writeClient(SocketChannel channel) throws IOException {
            try {
                ByteBuffer buffer = ByteBuffer.wrap("zwxydfdssdfsd".getBytes());
                int total = buffer.limit();

                int totalWrote = 0;
                int nWrote = 0;

                while ((nWrote = channel.write(buffer)) >= 0) {
                    totalWrote += nWrote;
                    if (totalWrote == total) {
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("IO Error : " + e.getMessage());
                channel.close();
            }
        }


    }
}
