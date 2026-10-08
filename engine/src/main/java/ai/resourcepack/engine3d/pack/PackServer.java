package ai.resourcepack.engine3d.pack;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Serves the pack over HTTP from the server itself, at
 * {@code /pack/<sha1>.zip}: the URL changes whenever the pack does, so a
 * client never keeps a stale copy.
 */
public final class PackServer {

    private final HttpServer http;
    private volatile PackBuilder.Pack pack;

    public PackServer(String bind, int port) throws IOException {
        http = HttpServer.create(bind.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bind, port), 0);
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "3DEngine pack server");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/pack/", exchange -> {
            PackBuilder.Pack p = pack;
            String path = exchange.getRequestURI().getPath();
            if (p == null || !path.equals("/pack/" + p.hex() + ".zip")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, p.zip().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(p.zip());
            }
        });
        http.start();
    }

    public void serve(PackBuilder.Pack pack) {
        this.pack = pack;
    }

    public void stop() {
        http.stop(0);
    }
}
