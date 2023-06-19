import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class Main {
    private static class Handler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("Got a " + exchange.getRequestMethod() + " request!");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Hello from " + InetAddress.getLocalHost().getHostName());
        System.out.println("Starting an HTTP server on port 80...");

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 80), 0);
        server.createContext("/", new Handler());
        server.start();
    }
}
