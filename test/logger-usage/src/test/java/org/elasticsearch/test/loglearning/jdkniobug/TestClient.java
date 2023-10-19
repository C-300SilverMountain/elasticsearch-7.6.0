package org.elasticsearch.test.loglearning.jdkniobug;


import java.util.logging.*;
import java.io.*;
import java.net.*;

/**
 * @author: chenwm
 * @since: 2023/10/10 11:51
 */
public class TestClient {
    private static final long SLEEP_PERIOD = 5000L; // 5 seconds
    private String host;
    private int port;

    public TestClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) throws Throwable {

        new TestClient("localhost", 8989).start();
    }

    public void start() throws Throwable {
        Socket socket = new Socket(host, port);

        BufferedReader in = new BufferedReader(
            new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(
            new OutputStreamWriter(socket.getOutputStream()),
            true /* auto flush */);

        out.println("abcdef");

        System.out.println("1. CLIENT CONNECTED AND WROTE MESSAGE");

        Thread.sleep(SLEEP_PERIOD);

//         socket.shutdownOutput();
        socket.close();

        System.out.println("4. CLIENT SHUTDOWN OUTPUT");

        Thread.sleep(SLEEP_PERIOD * 3);
    }
}
