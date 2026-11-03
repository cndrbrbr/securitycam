package de.securitycams;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SecurityCamsPlugin extends JavaPlugin implements Listener, TabCompleter {
    private final Map<String, Location> cameras = new HashMap<>();
    private final Map<UUID, ViewSession> viewers = new HashMap<>();
    private File cameraFile;
    private File sessionFile;
    private NamespacedKey cameraKey;
    private UUID attaching;

    private static final class ViewSession {
        private final String cameraId;
        private final Location previous;
        private final GameMode mode;
        private final UUID standId;
        private boolean attached;
        private int reattachCount;
        private ViewSession(String cameraId, Location previous, GameMode mode, UUID standId) {
            this.cameraId = cameraId; this.previous = previous; this.mode = mode; this.standId = standId;
        }
    }
    private static final class CamMenu implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override public void onEnable() {
        cameraKey = new NamespacedKey(this, "camera_id");
        cameraFile = new File(getDataFolder(), "cameras.yml");
        sessionFile = new File(getDataFolder(), "sessions.yml");
        loadCameras();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::checkViewers, 1L, 1L);
        for (Player player : Bukkit.getOnlinePlayers()) restoreStaleSession(player);
        getLogger().info("SecurityCams bereit: " + cameras.size() + " Kameras");
    }

    @Override public void onDisable() {
        for (UUID uuid : new ArrayList<>(viewers.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) stopViewing(player, false);
        }
        saveCameras();
    }

    private void loadCameras() {
        cameras.clear();
        if (!cameraFile.exists()) return;
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(cameraFile);
        ConfigurationSection section = yaml.getConfigurationSection("cameras");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            Location location = section.getLocation(id);
            if (location != null && location.getWorld() != null) cameras.put(id, location);
        }
    }

    private void saveCameras() {
        FileConfiguration yaml = new YamlConfiguration();
        cameras.forEach((id, location) -> yaml.set("cameras." + id, location));
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IOException("Datenordner konnte nicht erstellt werden");
            yaml.save(cameraFile);
        } catch (IOException e) { getLogger().severe("Kameras konnten nicht gespeichert werden: " + e.getMessage()); }
    }

    // Sitzungen werden gespeichert, damit Spieler nach einem Absturz nicht im Zuschauer-Modus bleiben.
    private void storeSession(UUID uuid, ViewSession session) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionFile);
        if (session == null) yaml.set(uuid.toString(), null);
        else {
            yaml.set(uuid + ".previous", session.previous);
            yaml.set(uuid + ".mode", session.mode.name());
        }
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IOException("Datenordner konnte nicht erstellt werden");
            yaml.save(sessionFile);
        } catch (IOException e) { getLogger().severe("Sitzung konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    private void restoreStaleSession(Player player) {
        if (viewers.containsKey(player.getUniqueId()) || !sessionFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionFile);
        ConfigurationSection section = yaml.getConfigurationSection(player.getUniqueId().toString());
        if (section == null) return;
        Location previous = section.getLocation("previous");
        GameMode mode;
        try { mode = GameMode.valueOf(section.getString("mode", "SURVIVAL")); }
        catch (IllegalArgumentException e) { mode = Bukkit.getDefaultGameMode(); }
        player.setSpectatorTarget(null);
        player.teleport(previous != null && previous.getWorld() != null ? previous : Bukkit.getWorlds().get(0).getSpawnLocation());
        player.setGameMode(mode);
        storeSession(player.getUniqueId(), null);
        player.sendMessage(ChatColor.YELLOW + "Unterbrochene Kameraansicht wurde beendet.");
    }

    // Verhindert freies Umherfliegen: Verliert der Spieler das Kameraziel ohne Shift, wird es neu gesetzt.
    // setSpectatorTarget teleportiert intern mit Ursache SPECTATE; diese Teleports dürfen nicht blockiert werden.
    private boolean attach(Player player, org.bukkit.entity.Entity stand) {
        attaching = player.getUniqueId();
        try { player.setSpectatorTarget(stand); }
        finally { attaching = null; }
        org.bukkit.entity.Entity target = player.getSpectatorTarget();
        return target != null && target.getUniqueId().equals(stand.getUniqueId());
    }

    private void checkViewers() {
        for (Map.Entry<UUID, ViewSession> entry : new ArrayList<>(viewers.entrySet())) {
            ViewSession session = entry.getValue();
            if (!session.attached) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            org.bukkit.entity.Entity target = player.getSpectatorTarget();
            if (target != null && target.getUniqueId().equals(session.standId)) continue;
            org.bukkit.entity.Entity stand = Bukkit.getEntity(session.standId);
            if (stand == null || !stand.isValid()) {
                getLogger().info("Ansicht " + player.getName() + "/" + session.cameraId + " beendet: Kamera-Entity ungültig");
                stopViewing(player, true);
                continue;
            }
            session.reattachCount++;
            if (session.reattachCount <= 3 || session.reattachCount % 100 == 0)
                getLogger().info("Ansicht " + player.getName() + "/" + session.cameraId + ": Ziel verloren (" + (target == null ? "null" : target.getType()) + "), neu gesetzt #" + session.reattachCount);
            if (player.getLocation().distanceSquared(stand.getLocation()) > 1) player.teleport(stand.getLocation());
            attach(player, stand);
        }
    }

    // Shift beendet die Kameraansicht.
    @EventHandler public void onSneak(PlayerToggleSneakEvent event) {
        ViewSession session = viewers.get(event.getPlayer().getUniqueId());
        if (session == null || !session.attached || !event.isSneaking()) return;
        getLogger().info("Ansicht " + event.getPlayer().getName() + "/" + session.cameraId + " beendet: Shift");
        stopViewing(event.getPlayer(), true);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Nur im Spiel verwendbar."); return true; }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("exit")) {
            if (!viewers.containsKey(player.getUniqueId())) player.sendMessage("Du siehst gerade keine Kamera an.");
            else stopViewing(player, true);
            return true;
        }
        if (!player.hasPermission("securitycams.use")) { player.sendMessage("Keine Berechtigung."); return true; }
        if (args.length == 0) { help(player); return true; }
        if (action.equals("list")) { openMenu(player); return true; }
        if (args.length < 2) { help(player); return true; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}")) { player.sendMessage("Kameraname: 1-32 Zeichen (a-z, 0-9, _ und -)."); return true; }
        switch (action) {
            case "create" -> {
                if (!player.hasPermission("securitycams.admin")) { player.sendMessage("Keine Berechtigung."); return true; }
                if (cameras.containsKey(id)) { player.sendMessage("Diese Kamera existiert bereits."); return true; }
                cameras.put(id, player.getEyeLocation().clone());
                saveCameras();
                player.sendMessage(ChatColor.GREEN + "Kamera '" + id + "' erstellt. Blickrichtung gespeichert.");
            }
            case "delete" -> {
                if (!player.hasPermission("securitycams.admin")) { player.sendMessage("Keine Berechtigung."); return true; }
                if (cameras.remove(id) == null) { player.sendMessage("Kamera nicht gefunden."); return true; }
                saveCameras();
                for (Map.Entry<UUID, ViewSession> entry : new ArrayList<>(viewers.entrySet())) {
                    Player viewer = Bukkit.getPlayer(entry.getKey());
                    if (viewer != null && entry.getValue().cameraId.equals(id)) stopViewing(viewer, true);
                }
                player.sendMessage(ChatColor.GREEN + "Kamera gelöscht: " + id);
            }
            case "view" -> viewCamera(player, id);
            default -> help(player);
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> actions = new ArrayList<>(List.of("list", "view", "exit"));
            if (sender.hasPermission("securitycams.admin")) actions.addAll(List.of("create", "delete"));
            return actions.stream().filter(a -> a.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && Set.of("view", "delete").contains(args[0].toLowerCase(Locale.ROOT)))
            return cameras.keySet().stream().filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).sorted().toList();
        return List.of();
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "SecurityCams: /cam list, /cam view <name>, /cam exit");
        player.sendMessage(ChatColor.GRAY + "Administratoren: /cam create <name>, /cam delete <name>");
    }

    private void openMenu(Player player) {
        List<String> ids = cameras.keySet().stream().sorted().toList();
        int size = Math.max(9, Math.min(54, ((Math.min(54, ids.size()) + 8) / 9) * 9));
        CamMenu holder = new CamMenu();
        Inventory inv = Bukkit.createInventory(holder, size, ChatColor.DARK_BLUE + "SecurityCams - Kameras");
        holder.inventory = inv;
        for (int i = 0; i < Math.min(size, ids.size()); i++) {
            String id = ids.get(i);
            ItemStack item = new ItemStack(Material.ENDER_EYE);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + id);
            meta.setLore(List.of(ChatColor.GRAY + "Klicken: Liveansicht öffnen"));
            meta.getPersistentDataContainer().set(cameraKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        player.openInventory(inv);
        if (ids.size() > 54) player.sendMessage(ChatColor.YELLOW + "Nur die ersten 54 Kameras werden angezeigt; /cam view <name> für weitere.");
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CamMenu)) return;
        event.setCancelled(true);
        if (event.getRawSlot() >= 0 && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
            ItemStack item = event.getCurrentItem();
            if (item == null || !item.hasItemMeta()) return;
            String id = item.getItemMeta().getPersistentDataContainer().get(cameraKey, PersistentDataType.STRING);
            if (id == null || !(event.getWhoClicked() instanceof Player player)) return;
            Bukkit.getScheduler().runTask(this, () -> { player.closeInventory(); viewCamera(player, id); });
        }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CamMenu) event.setCancelled(true);
    }

    // Zuschauer-Teleport zu anderen Spielern (Zuschauermenü) während der Kameraansicht sperren.
    @EventHandler public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && viewers.containsKey(event.getPlayer().getUniqueId())
                && !event.getPlayer().getUniqueId().equals(attaching))
            event.setCancelled(true);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        restoreStaleSession(event.getPlayer());
    }

    private void viewCamera(Player player, String id) {
        Location location = cameras.get(id);
        if (location == null || location.getWorld() == null) { player.sendMessage("Kamera nicht gefunden oder Welt nicht geladen."); return; }
        if (viewers.containsKey(player.getUniqueId())) stopViewing(player, false);
        Location previous = player.getLocation().clone();
        GameMode oldMode = player.getGameMode();
        if (oldMode == GameMode.SPECTATOR) { player.sendMessage("Im Zuschauer-Modus nicht verfügbar."); return; }
        ArmorStand stand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setMarker(true);
        stand.setSilent(true);
        stand.setPersistent(false);
        stand.setRotation(location.getYaw(), location.getPitch());
        ViewSession session = new ViewSession(id, previous, oldMode, stand.getUniqueId());
        viewers.put(player.getUniqueId(), session);
        storeSession(player.getUniqueId(), session);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(location);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || viewers.get(player.getUniqueId()) != session) return;
            if (!stand.isValid()) { stopViewing(player, false); player.sendMessage("Kamera konnte nicht geöffnet werden."); return; }
            if (!attach(player, stand)) getLogger().warning("Kameraziel für " + player.getName() + "/" + id + " konnte nicht gesetzt werden");
            session.attached = true;
            player.sendMessage(ChatColor.GREEN + "Kamera: " + id + ChatColor.GRAY + " | Verlassen: Shift oder /cam exit");
        }, 2L);
    }

    private void stopViewing(Player player, boolean notify) {
        ViewSession session = viewers.remove(player.getUniqueId());
        if (session == null) return;
        player.setSpectatorTarget(null);
        player.teleport(session.previous.getWorld() != null ? session.previous : Bukkit.getWorlds().get(0).getSpawnLocation());
        player.setGameMode(session.mode);
        storeSession(player.getUniqueId(), null);
        org.bukkit.entity.Entity entity = Bukkit.getEntity(session.standId);
        if (entity != null) entity.remove();
        if (notify) player.sendMessage(ChatColor.GREEN + "Kameraansicht verlassen.");
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (viewers.containsKey(player.getUniqueId())) stopViewing(player, false);
    }
}
