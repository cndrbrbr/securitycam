package de.securitycams;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Location;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/** Kleiner HTTP-Server für die Kameraübersicht, MJPEG-Streams und Einzelbilder. */
final class WebServer {
    private static final String BOUNDARY = "securitycamsframe";
    private final SecurityCamsPlugin plugin;
    private final CameraStreams streams;
    private final Map<String, Location> cameras;
    private final byte[] token;
    private final Semaphore streamSlots;
    private final byte[] page;
    private final String pageVersion;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SecurityCams-Web");
        t.setDaemon(true);
        return t;
    });
    private HttpServer server;
    private volatile boolean running;

    WebServer(SecurityCamsPlugin plugin, CameraStreams streams, Map<String, Location> cameras, String token, int maxStreams) {
        this.plugin = plugin;
        this.streams = streams;
        this.cameras = cameras;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.streamSlots = new Semaphore(Math.max(1, maxStreams));
        // Seitenversion = Hash der Seite; die Seite lädt sich neu, wenn der Server eine andere meldet.
        String html;
        try (InputStream in = plugin.getResource("web/index.html")) {
            html = in == null ? "Ressource fehlt" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            html = "Ressource fehlt";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(html.getBytes(StandardCharsets.UTF_8));
            pageVersion = HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        page = html.replace("__PAGE_VERSION__", pageVersion).getBytes(StandardCharsets.UTF_8);
    }

    void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        running = true;
        server.start();
    }

    void stop() {
        running = false;
        if (server != null) server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "Method not allowed"); return; }
            if (!authorized(ex)) { send(ex, 401, "text/plain; charset=utf-8", "Zugriff verweigert: ?token=... fehlt oder ist falsch."); return; }
            if (path.equals("/")) {
                ex.getResponseHeaders().add("Set-Cookie", "sctoken=" + new String(token, StandardCharsets.UTF_8) + "; Path=/; HttpOnly; SameSite=Strict");
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, page.length);
                ex.getResponseBody().write(page);
            } else if (path.equals("/api/cameras")) {
                String json = cameras.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> "{\"id\":\"" + e.getKey() + "\",\"world\":\"" + (e.getValue().getWorld() == null ? "" : jsonEscape(e.getValue().getWorld().getName())) + "\"}")
                        .collect(Collectors.joining(",", "[", "]"));
                ex.getResponseHeaders().add("X-SecurityCams-Page", pageVersion);
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                send(ex, 200, "application/json", json);
            } else if (path.startsWith("/cam/") && path.endsWith(".jpg")) {
                snapshot(ex, path.substring(5, path.length() - 4));
            } else if (path.startsWith("/cam/") && path.endsWith(".mjpg")) {
                stream(ex, path.substring(5, path.length() - 5));
            } else {
                send(ex, 404, "text/plain", "Not found");
            }
        } catch (IOException ignored) {
            // Client hat die Verbindung geschlossen
        }
    }

    private boolean authorized(HttpExchange ex) {
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            if (part.startsWith("token=") && matches(URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8))) return true;
        }
        String cookies = ex.getRequestHeaders().getFirst("Cookie");
        if (cookies != null) for (String c : cookies.split(";")) {
            c = c.trim();
            if (c.startsWith("sctoken=") && matches(c.substring(8))) return true;
        }
        return false;
    }

    private boolean matches(String candidate) {
        return MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
    }

    private void snapshot(HttpExchange ex, String id) throws IOException {
        CameraStreams.Feed feed = streams.watch(id);
        if (feed == null) { send(ex, 404, "text/plain", "Kamera nicht gefunden"); return; }
        byte[] jpeg = feed.jpeg;
        try {
            // Nach Leerlauf ist das gespeicherte Bild veraltet: auf ein neues warten
            if (jpeg == null || System.currentTimeMillis() - feed.frameTime > 1500) {
                byte[] fresh = feed.awaitFrame(feed.frameNo, 10_000);
                if (fresh != null) jpeg = fresh;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        if (jpeg == null) { send(ex, 503, "text/plain", "Noch kein Bild"); return; }
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("Content-Type", "image/jpeg");
        ex.sendResponseHeaders(200, jpeg.length);
        ex.getResponseBody().write(jpeg);
    }

    private void stream(HttpExchange ex, String id) throws IOException {
        CameraStreams.Feed feed = streams.watch(id);
        if (feed == null) { send(ex, 404, "text/plain", "Kamera nicht gefunden"); return; }
        if (!streamSlots.tryAcquire()) { send(ex, 503, "text/plain", "Zu viele gleichzeitige Streams"); return; }
        try {
            ex.getResponseHeaders().add("Cache-Control", "no-store");
            ex.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=" + BOUNDARY);
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            long last = 0;
            while (running && streams.watch(id) != null) {
                byte[] jpeg = feed.awaitFrame(last, 5_000);
                if (jpeg == null) continue;
                last = feed.frameNo;
                out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(jpeg);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            streamSlots.release();
        }
    }

    private static void send(HttpExchange ex, int status, String type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
