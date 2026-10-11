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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SecurityCamsPlugin extends JavaPlugin implements Listener {
    private final Map<String, Location> cameras = new HashMap<>();
    private final Map<UUID, ViewSession> viewers = new HashMap<>();
    private File cameraFile;
    private NamespacedKey cameraKey;

    private record ViewSession(Location previous, GameMode mode, UUID standId) {}
    private static final class CamMenu implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override public void onEnable() {
        cameraKey = new NamespacedKey(this, "camera_id");
        cameraFile = new File(getDataFolder(), "cameras.yml");
        loadCameras();
        getServer().getPluginManager().registerEvents(this, this);
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

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Nur im Spiel verwendbar."); return true; }
        if (!player.hasPermission("securitycams.use")) { player.sendMessage("Keine Berechtigung."); return true; }
        if (args.length == 0) { help(player); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("exit")) {
            if (!viewers.containsKey(player.getUniqueId())) player.sendMessage("Du siehst gerade keine Kamera an.");
            else stopViewing(player, true);
            return true;
        }
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
                if (cameras.remove(id) == null) player.sendMessage("Kamera nicht gefunden.");
                else { saveCameras(); player.sendMessage(ChatColor.GREEN + "Kamera gelöscht: " + id); }
            }
            case "view" -> viewCamera(player, id);
            default -> help(player);
        }
        return true;
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
        if (event.getRawSlot() < event.getView().getTopInventory().getSize()) {
            event.setCancelled(true);
            ItemStack item = event.getCurrentItem();
            if (item == null || !item.hasItemMeta()) return;
            String id = item.getItemMeta().getPersistentDataContainer().get(cameraKey, PersistentDataType.STRING);
            if (id == null || !(event.getWhoClicked() instanceof Player player)) return;
            Bukkit.getScheduler().runTask(this, () -> { player.closeInventory(); viewCamera(player, id); });
        }
    }

    private void viewCamera(Player player, String id) {
        Location location = cameras.get(id);
        if (location == null || location.getWorld() == null) { player.sendMessage("Kamera nicht gefunden oder Welt nicht geladen."); return; }
        if (viewers.containsKey(player.getUniqueId())) stopViewing(player, false);
        Location previous = player.getLocation().clone();
        GameMode oldMode = player.getGameMode();
        ArmorStand stand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setMarker(true);
        stand.setSilent(true);
        stand.setPersistent(false);
        stand.setRotation(location.getYaw(), location.getPitch());
        viewers.put(player.getUniqueId(), new ViewSession(previous, oldMode, stand.getUniqueId()));
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(location);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || !viewers.containsKey(player.getUniqueId()) || !stand.isValid()) return;
            player.setSpectatorTarget(stand);
            player.sendMessage(ChatColor.GREEN + "Kamera: " + id + ChatColor.GRAY + " | Verlassen: /cam exit");
        }, 2L);
    }

    private void stopViewing(Player player, boolean notify) {
        ViewSession session = viewers.remove(player.getUniqueId());
        if (session == null) return;
        player.setSpectatorTarget(null);
        if (session.previous().getWorld() != null) player.teleport(session.previous());
        player.setGameMode(session.mode());
        org.bukkit.entity.Entity entity = Bukkit.getEntity(session.standId());
        if (entity != null) entity.remove();
        if (notify) player.sendMessage(ChatColor.GREEN + "Kameraansicht verlassen.");
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (viewers.containsKey(player.getUniqueId())) stopViewing(player, false);
    }
}
