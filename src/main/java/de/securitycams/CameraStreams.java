package de.securitycams;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rendert Kamerabilder serverseitig per Raycasting über Block-Snapshots.
 * Snapshots und Entity-Daten werden im Hauptthread gesammelt, gerendert wird asynchron.
 */
final class CameraStreams {
    private static final int UNKNOWN = 0x01000000;   // Chunk nicht geladen
    private static final int SOLID = 0xFF000000;
    private static final int LIQUID = 0x80000000;
    private static final int GLASS = 0x40000000;
    private static final long WATCH_TIMEOUT_MS = 10_000;
    private static final long TICKET_GRACE_MS = 60_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SecurityCamsPlugin plugin;
    private final Map<String, Location> cameras;
    private final int width, height, distance, refreshTicks, periodTicks;
    private final double tanHalfFov;
    private final int[] materialColors = new int[Material.values().length];
    private final Map<String, Feed> feeds = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SecurityCams-Render");
        t.setDaemon(true);
        return t;
    });
    private long tickCounter;

    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int color) {}
    record Label(double x, double y, double z, String text) {}
    record Grid(int ox, int oy, int oz, int sx, int sy, int sz, int[] cells, byte[] light) {}
    record Scene(Location eye, Grid grid, List<Box> boxes, List<Label> labels, double daylight) {}

    static final class Feed {
        final String id;
        volatile long lastRequest;
        volatile byte[] jpeg;
        volatile long frameNo;
        volatile long frameTime;
        volatile Grid grid;
        long lastGridTick = Long.MIN_VALUE;
        String ticketWorld;
        final Set<Long> tickets = new HashSet<>();
        final AtomicBoolean rendering = new AtomicBoolean();
        Feed(String id) { this.id = id; }

        synchronized void publish(byte[] data) {
            jpeg = data;
            frameTime = System.currentTimeMillis();
            frameNo++;
            notifyAll();
        }

        synchronized byte[] awaitFrame(long after, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (frameNo <= after) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                wait(left);
            }
            return jpeg;
        }
    }

    CameraStreams(SecurityCamsPlugin plugin, Map<String, Location> cameras) {
        this.plugin = plugin;
        this.cameras = cameras;
        var cfg = plugin.getConfig();
        width = Math.max(64, Math.min(1280, cfg.getInt("render.width", 320)));
        height = Math.max(36, Math.min(720, cfg.getInt("render.height", 180)));
        distance = Math.max(8, Math.min(96, cfg.getInt("render.distance", 48)));
        int fps = Math.max(1, Math.min(10, cfg.getInt("render.fps", 4)));
        periodTicks = Math.max(2, 20 / fps);
        refreshTicks = Math.max(periodTicks, (int) Math.round(cfg.getDouble("render.block-refresh-seconds", 2.0) * 20));
        tanHalfFov = Math.tan(Math.toRadians(Math.max(30, Math.min(110, cfg.getDouble("render.fov", 70))) / 2));
        buildMaterialColors();
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, periodTicks, periodTicks);
    }

    void stop() {
        pool.shutdownNow();
        for (Feed feed : feeds.values()) releaseTickets(feed);
        feeds.clear();
    }

    /** Vom Webserver aufgerufen: Kamera wird angesehen. Liefert null, wenn es die Kamera nicht gibt. */
    Feed watch(String id) {
        if (!cameras.containsKey(id)) return null;
        Feed feed = feeds.computeIfAbsent(id, Feed::new);
        feed.lastRequest = System.currentTimeMillis();
        return feed;
    }

    private void buildMaterialColors() {
        for (Material m : Material.values()) {
            int c = 0;
            try {
                if (m.isBlock() && !m.isLegacy() && !m.isAir()) {
                    int rgb = m.createBlockData().getMapColor().asRGB() & 0xFFFFFF;
                    if (m == Material.WATER || m == Material.BUBBLE_COLUMN) c = LIQUID | 0x3F76E4;
                    else if (m == Material.LAVA) c = SOLID | 0xD96415;
                    else if (m.name().contains("GLASS")) c = GLASS | (rgb == 0 ? 0xC0D8E0 : rgb);
                    else if (m.isSolid() && rgb != 0) c = SOLID | rgb;
                }
            } catch (RuntimeException ignored) { }
            materialColors[m.ordinal()] = c;
        }
    }

    // ---- Hauptthread ----

    private void tick() {
        tickCounter += periodTicks;
        long now = System.currentTimeMillis();
        feeds.values().removeIf(feed -> {
            if (cameras.containsKey(feed.id)) return false;
            releaseTickets(feed);
            return true;
        });
        for (Feed feed : feeds.values()) {
            long idle = now - feed.lastRequest;
            if (idle > WATCH_TIMEOUT_MS) {
                if (idle > TICKET_GRACE_MS) releaseTickets(feed);
                continue;
            }
            Location camera = cameras.get(feed.id);
            if (camera == null || camera.getWorld() == null || feed.rendering.get()) continue;
            World world = camera.getWorld();
            holdTickets(feed, world, camera);

            List<ChunkSnapshot> snapshots = null;
            if (feed.grid == null || tickCounter - feed.lastGridTick >= refreshTicks) {
                snapshots = snapshotChunks(world, camera);
                feed.lastGridTick = tickCounter;
            }
            List<Box> boxes = new ArrayList<>();
            List<Label> labels = new ArrayList<>();
            collectEntities(world, camera, boxes, labels);
            double daylight = daylight(world.getTime());
            Location eye = camera.clone();
            int minY = world.getMinHeight(), maxY = world.getMaxHeight();

            List<ChunkSnapshot> snaps = snapshots;
            feed.rendering.set(true);
            pool.execute(() -> {
                try {
                    if (snaps != null) feed.grid = buildGrid(eye, snaps, minY, maxY);
                    feed.publish(render(feed.id, new Scene(eye, feed.grid, boxes, labels, daylight), feed.frameNo));
                } catch (Throwable t) {
                    plugin.getLogger().warning("Rendern von Kamera " + feed.id + " fehlgeschlagen: " + t);
                } finally {
                    feed.rendering.set(false);
                }
            });
        }
    }

    private void holdTickets(Feed feed, World world, Location camera) {
        if (feed.ticketWorld != null && !feed.ticketWorld.equals(world.getName())) releaseTickets(feed);
        feed.ticketWorld = world.getName();
        int r = (distance + 15) / 16;
        int ccx = camera.getBlockX() >> 4, ccz = camera.getBlockZ() >> 4;
        Set<Long> wanted = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) wanted.add(key(ccx + dx, ccz + dz));
        for (Long k : new ArrayList<>(feed.tickets)) if (!wanted.contains(k)) { world.removePluginChunkTicket(kx(k), kz(k), plugin); feed.tickets.remove(k); }
        for (Long k : wanted) if (feed.tickets.add(k)) world.addPluginChunkTicket(kx(k), kz(k), plugin);
    }

    private void releaseTickets(Feed feed) {
        World world = feed.ticketWorld == null ? null : Bukkit.getWorld(feed.ticketWorld);
        if (world != null) for (Long k : feed.tickets) world.removePluginChunkTicket(kx(k), kz(k), plugin);
        feed.tickets.clear();
        feed.ticketWorld = null;
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    private static int kx(long k) { return (int) (k >> 32); }
    private static int kz(long k) { return (int) k; }

    private List<ChunkSnapshot> snapshotChunks(World world, Location camera) {
        List<ChunkSnapshot> list = new ArrayList<>();
        int r = (distance + 15) / 16;
        int ccx = camera.getBlockX() >> 4, ccz = camera.getBlockZ() >> 4;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (world.isChunkLoaded(ccx + dx, ccz + dz)) list.add(world.getChunkAt(ccx + dx, ccz + dz).getChunkSnapshot(false, false, false));
        }
        return list;
    }

    private void collectEntities(World world, Location camera, List<Box> boxes, List<Label> labels) {
        for (Entity e : world.getNearbyEntities(camera, distance, distance, distance)) {
            if (e instanceof ArmorStand stand && stand.isMarker()) continue;
            if (e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
            BoundingBox b = e.getBoundingBox();
            if (b.getVolume() <= 0) continue;
            if (e instanceof Player p) {
                double headY = b.getMaxY() - 0.5;
                int body = Color.HSBtoRGB((p.getName().hashCode() & 0xFFFF) / 65535f, 0.55f, 0.75f) & 0xFFFFFF;
                boxes.add(new Box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), headY, b.getMaxZ(), body));
                boxes.add(new Box(b.getCenterX() - 0.25, headY, b.getCenterZ() - 0.25, b.getCenterX() + 0.25, b.getMaxY(), b.getCenterZ() + 0.25, 0xC69C7B));
                labels.add(new Label(b.getCenterX(), b.getMaxY() + 0.4, b.getCenterZ(), p.getName()));
            } else {
                boxes.add(new Box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ(), entityColor(e.getType())));
            }
            if (boxes.size() >= 64) break;
        }
    }

    private static int entityColor(EntityType type) {
        return switch (type) {
            case ZOMBIE, CREEPER, DROWNED -> 0x4C8A3A;
            case SKELETON, STRAY -> 0xC8C8C8;
            case COW, MOOSHROOM -> 0x5A3E28;
            case PIG -> 0xF0A0A0;
            case SHEEP, CHICKEN -> 0xEEEEEE;
            case VILLAGER, WANDERING_TRADER -> 0x8B5E3C;
            case ITEM, EXPERIENCE_ORB -> 0xF2D04B;
            default -> 0xE08A2E;
        };
    }

    /** 1.0 = Mittag, ~0.1 = Mitternacht (Minecraft-Tag beginnt bei 0 = Sonnenaufgang). */
    private static double daylight(long time) {
        double angle = ((time + 18000) % 24000) / 24000.0 * Math.PI * 2;
        return Math.max(0.1, Math.min(1.0, 0.5 + Math.cos(angle)));
    }

    // ---- Renderthread ----

    private Grid buildGrid(Location eye, List<ChunkSnapshot> snapshots, int minY, int maxY) {
        int ox = eye.getBlockX() - distance, oz = eye.getBlockZ() - distance;
        int oy = Math.max(minY, eye.getBlockY() - distance);
        int top = Math.min(maxY - 1, eye.getBlockY() + distance);
        int sx = distance * 2 + 1, sz = distance * 2 + 1, sy = top - oy + 1;
        int[] cells = new int[sx * sy * sz];
        byte[] light = new byte[cells.length];
        java.util.Arrays.fill(cells, UNKNOWN);
        for (ChunkSnapshot snap : snapshots) {
            int bx = snap.getX() << 4, bz = snap.getZ() << 4;
            for (int x = 0; x < 16; x++) {
                int gx = bx + x - ox;
                if (gx < 0 || gx >= sx) continue;
                for (int z = 0; z < 16; z++) {
                    int gz = bz + z - oz;
                    if (gz < 0 || gz >= sz) continue;
                    for (int gy = 0; gy < sy; gy++) {
                        int y = oy + gy, i = (gy * sz + gz) * sx + gx;
                        cells[i] = materialColors[snap.getBlockType(x, y, z).ordinal()];
                        light[i] = (byte) ((snap.getBlockSkyLight(x, y, z) << 4) | snap.getBlockEmittedLight(x, y, z));
                    }
                }
            }
        }
        return new Grid(ox, oy, oz, sx, sy, sz, cells, light);
    }

    private byte[] render(String id, Scene scene, long frameNo) throws IOException {
        Location eye = scene.eye();
        double yaw = Math.toRadians(eye.getYaw()), pitch = Math.toRadians(eye.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double aspect = (double) width / height;
        boolean ir = scene.daylight() < 0.35;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int py = 0; py < height; py++) {
            double sy = (1 - 2 * (py + 0.5) / height) * tanHalfFov;
            for (int px = 0; px < width; px++) {
                double sx = (2 * (px + 0.5) / width - 1) * tanHalfFov * aspect;
                double dx = fx + rx * sx + ux * sy, dy = fy + uy * sy, dz = fz + rz * sx + uz * sy;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                int rgb = trace(scene, eye.getX(), eye.getY(), eye.getZ(), dx / len, dy / len, dz / len, ir);
                int noise = rnd.nextInt(-10, 11) - ((py & 1) == 0 ? 0 : 8);
                row[px] = clampRgb(((rgb >> 16) & 0xFF) + noise, ((rgb >> 8) & 0xFF) + noise, (rgb & 0xFF) + noise);
            }
            img.setRGB(0, py, width, 1, row, 0, width);
        }
        drawOverlay(img, id, scene, ir, frameNo, fx, fy, fz, rx, rz, ux, uy, uz, aspect);
        return encode(img);
    }

    private int trace(Scene s, double ex, double ey, double ez, double dx, double dy, double dz, boolean ir) {
        Grid g = s.grid();
        double boxT = Double.MAX_VALUE; int boxColor = 0; int boxFace = 1;
        for (Box b : s.boxes()) {
            double[] hit = intersect(b, ex, ey, ez, dx, dy, dz);
            if (hit != null && hit[0] < boxT) { boxT = hit[0]; boxColor = b.color(); boxFace = (int) hit[1]; }
        }
        double sky = s.daylight();
        if (g == null) return boxT < Double.MAX_VALUE ? shade(boxColor, boxFace, 1.0, ir) : skyColor(dy, sky, ir);

        double ox = ex - g.ox(), oy = ey - g.oy(), oz = ez - g.oz();
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - ox : ox - x) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - oy : oy - y) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - oz : oz - z) * tdz);
        double t = 0, waterT = -1; int face = 1; int prevLight = 0xF0;
        int tint = -1;
        while (t < distance) {
            if (t > boxT) return fog(shade(boxColor, boxFace, lightFactor(prevLight, sky, ir), ir), boxT, sky, dy, ir, waterT);
            if (x < 0 || y < 0 || z < 0 || x >= g.sx() || y >= g.sy() || z >= g.sz()) break;
            int i = (y * g.sz() + z) * g.sx() + x;
            int cell = g.cells()[i];
            if (cell == UNKNOWN) return fog(skyColor(Math.max(0, dy), sky, ir), t, sky, dy, ir, waterT);
            if ((cell & SOLID) == SOLID) {
                int c = shade(cell & 0xFFFFFF, face * (face == 1 ? -stepY : 1), lightFactor(prevLight, sky, ir), ir);
                if (tint >= 0) c = mix(c, tint, 0.35);
                return fog(c, t, sky, dy, ir, waterT);
            }
            if ((cell & LIQUID) == LIQUID) { if (waterT < 0) waterT = t; }
            else if ((cell & GLASS) == GLASS && tint < 0) tint = cell & 0xFFFFFF;
            else prevLight = g.light()[i] & 0xFF;
            if (tmx < tmy && tmx < tmz) { x += stepX; t = tmx; tmx += tdx; face = 0; }
            else if (tmy < tmz) { y += stepY; t = tmy; tmy += tdy; face = 1; }
            else { z += stepZ; t = tmz; tmz += tdz; face = 2; }
        }
        if (boxT < Double.MAX_VALUE) return fog(shade(boxColor, boxFace, lightFactor(prevLight, sky, ir), ir), boxT, sky, dy, ir, waterT);
        int c = skyColor(Math.max(0, dy), sky, ir);
        return waterT >= 0 ? mix(c, ir ? 0x203020 : 0x1E3A8A, 0.7) : c;
    }

    /** Liefert {t, face} mit face 1 = oben, -1 = unten, 0 = x-Seite, 2 = z-Seite. */
    private static double[] intersect(Box b, double ex, double ey, double ez, double dx, double dy, double dz) {
        double tmin = 0, tmax = Double.MAX_VALUE; int face = 1;
        double[] o = {ex, ey, ez}, d = {dx, dy, dz}, lo = {b.minX(), b.minY(), b.minZ()}, hi = {b.maxX(), b.maxY(), b.maxZ()};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) { if (o[a] < lo[a] || o[a] > hi[a]) return null; continue; }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tmin) { tmin = t1; face = a == 1 ? (d[a] < 0 ? 1 : -1) : a; }
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return null;
        }
        return tmin > 0 ? new double[]{tmin, face} : null;
    }

    private static double lightFactor(int packed, double daylight, boolean ir) {
        double sky = ((packed >> 4) & 0xF) / 15.0 * daylight, block = (packed & 0xF) / 15.0;
        double l = Math.max(sky, block);
        return ir ? 0.55 + 0.45 * l : 0.18 + 0.82 * l;
    }

    private static int shade(int rgb, int face, double light, boolean ir) {
        double f = (face == 1 ? 1.0 : face == -1 ? 0.5 : face == 0 ? 0.8 : 0.65) * light;
        int r = (int) (((rgb >> 16) & 0xFF) * f), g = (int) (((rgb >> 8) & 0xFF) * f), b = (int) ((rgb & 0xFF) * f);
        if (ir) { int l = (r * 3 + g * 6 + b) / 10; return clampRgb(l * 8 / 10, l, l * 8 / 10); }
        return clampRgb(r, g, b);
    }

    private int fog(int rgb, double t, double daylight, double dy, boolean ir, double waterT) {
        double k = Math.min(1, (t / distance) * (t / distance));
        int c = mix(rgb, skyColor(Math.max(0, dy), daylight, ir), k);
        if (waterT >= 0) c = mix(c, ir ? 0x203020 : 0x1E3A8A, Math.min(0.85, 0.45 + (t - waterT) * 0.04));
        return c;
    }

    private static int skyColor(double dy, double daylight, boolean ir) {
        if (ir) return dy < 0 ? 0x101810 : 0x1C2A1C;
        if (dy < 0) return mix(0x101418, 0x7A8EA8, daylight * 0.3);
        int day = mix(0xA8C8F0, 0x5A8BD8, Math.min(1, dy * 1.5));
        return mix(0x0A0E1C, day, daylight);
    }

    private static int mix(int a, int b, double k) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) (((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) ((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return clampRgb(r, g, bl);
    }

    private static int clampRgb(int r, int g, int b) {
        return (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
    }

    private void drawOverlay(BufferedImage img, String id, Scene scene, boolean ir, long frameNo,
                             double fx, double fy, double fz, double rx, double rz, double ux, double uy, double uz, double aspect) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int fontSize = Math.max(9, height / 16);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, fontSize));
        Location eye = scene.eye();
        for (Label label : scene.labels()) {
            double vx = label.x() - eye.getX(), vy = label.y() - eye.getY(), vz = label.z() - eye.getZ();
            double depth = vx * fx + vy * fy + vz * fz;
            if (depth < 0.5) continue;
            double sx = (vx * rx + vz * rz) / depth / (tanHalfFov * aspect), sy = (vx * ux + vy * uy + vz * uz) / depth / tanHalfFov;
            if (Math.abs(sx) > 1 || Math.abs(sy) > 1) continue;
            int px = (int) ((sx + 1) / 2 * width), py = (int) ((1 - sy) / 2 * height);
            int w = g.getFontMetrics().stringWidth(label.text());
            g.setColor(new Color(0, 0, 0, 140));
            g.fillRect(px - w / 2 - 2, py - fontSize, w + 4, fontSize + 3);
            g.setColor(Color.WHITE);
            g.drawString(label.text(), px - w / 2, py);
        }
        String left = "CAM " + id.toUpperCase() + (ir ? "  IR" : "");
        String right = LocalDateTime.now().format(TIME);
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect(0, 0, width, fontSize + 6);
        g.setColor(new Color(230, 230, 230));
        g.drawString(left, 4, fontSize + 1);
        g.drawString(right, width - g.getFontMetrics().stringWidth(right) - 4, fontSize + 1);
        if ((frameNo / 2) % 2 == 0) {
            g.setColor(new Color(220, 30, 30));
            int r = fontSize * 2 / 3;
            g.fillOval(4, height - r - 6, r, r);
            g.drawString("REC", 8 + r, height - 6);
        }
        g.dispose();
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.8f);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
