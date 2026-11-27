package com.casualsmp.casualffa;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.util.Vector;

import java.io.*;
import java.util.*;

public final class CasualFFA extends JavaPlugin implements Listener, CommandExecutor {
    private enum State { IDLE, REGISTRATION, RUNNING, PAUSED }
    private State state = State.IDLE;
    private Location lobby;
    private Location arenaCenter;
    private boolean clearInventory = true;
    private boolean kitDistribution = false;
    private ItemStack[] kit;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Set<UUID> excluded = new HashSet<>();
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final Set<UUID> managedTeleports = new HashSet<>();
    private final Set<UUID> pendingEliminationRestore = new HashSet<>();
    private File dataFile;
    private YamlConfiguration data;

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        lobby = readLocation("locations.lobby");
        arenaCenter = readLocation("locations.arena");
        clearInventory = getConfig().getBoolean("clear-inventory", true);
        if (data.contains("kit")) kit = decodeItems(data.getString("kit"));
        loadPendingSnapshots();
        Objects.requireNonNull(getCommand("ffa")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CasualFFA enabled. Pending inventory backups: " + snapshots.size());
    }

    @Override public void onDisable() {
        // Keep all outstanding backups durable before the plugin/server shuts down.
        for (Map.Entry<UUID, Snapshot> entry : snapshots.entrySet()) saveSnapshot(entry.getKey(), entry.getValue());
        saveData();
    }

    private String msg(String key) { return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + key, "&c" + key)); }
    private void tell(CommandSender sender, String text) { sender.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&8[&bCasualFFA&8] ")) + text); }
    private void broadcast(String key, String player) { Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&8[&bCasualFFA&8] ") + getConfig().getString("messages." + key, "&c" + key).replace("%player%", player))); }
    private boolean admin(CommandSender s) { if (s.hasPermission("casualffa.admin")) return true; tell(s, msg("no-permission")); return false; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 0) { help(sender); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("join")) { if (!(sender instanceof Player p)) { tell(sender, "&cPlayers only."); return true; } join(p); return true; }
        if (sub.equals("leave")) { if (!(sender instanceof Player p)) { tell(sender, "&cPlayers only."); return true; } leave(p, true); return true; }
        if (sub.equals("status")) { tell(sender, "&eState: &f" + state + " &7| &eRegistered: &f" + participants.size() + " &7| &eAlive: &f" + alive.size()); return true; }
        if (!admin(sender)) return true;
        switch (sub) {
            case "start" -> {
                if (state != State.IDLE) { tell(sender, "&cAn FFA is already active. Use /ffa stop first."); return true; }
                if (lobby == null || arenaCenter == null) { tell(sender, msg("not-configured")); return true; }
                participants.clear(); alive.clear(); eliminated.clear();
                state = State.REGISTRATION; kitDistribution = false;
                Bukkit.broadcastMessage(prefix() + ChatColor.GREEN + "FFA registration is now OPEN! Type /ffa join to participate.");
            }
            case "begin" -> begin(sender);
            case "pause" -> { if (state != State.RUNNING) tell(sender, "&cThe FFA is not running."); else { state = State.PAUSED; tell(sender, "&eFFA paused."); } }
            case "resume" -> { if (state != State.PAUSED) tell(sender, "&cThe FFA is not paused."); else { state = State.RUNNING; tell(sender, "&aFFA resumed."); checkWinner(); } }
            case "stop" -> stopTournament(sender);
            case "setlobby" -> setLocation(sender, "lobby");
            case "setarena" -> setLocation(sender, "arena");
            case "setspawn" -> { if (!(sender instanceof Player p)) { tell(sender, "&cPlayers only."); return true; } arenaCenter = p.getLocation().clone(); saveLocation("locations.arena", arenaCenter); tell(sender, "&aArena center set to your current location. Players will spread around it."); }
            case "clearinv" -> {
                if (a.length < 2 || !(a[1].equalsIgnoreCase("true") || a[1].equalsIgnoreCase("false"))) { tell(sender, "&eUsage: /ffa clearinv true/false"); return true; }
                clearInventory = Boolean.parseBoolean(a[1]); getConfig().set("clear-inventory", clearInventory); saveConfig(); tell(sender, "&aClear inventory: " + clearInventory);
            }
            case "list" -> tell(sender, "&eRegistered: &f" + names(participants) + " &7| &eAlive: &f" + names(alive));
            case "exclude" -> changeExcluded(sender, a, true);
            case "include" -> changeExcluded(sender, a, false);
            case "excluded" -> tell(sender, "&eExcluded UUIDs: &f" + excluded);
            case "kit" -> kitCommand(sender, a);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        tell(s, "&bCommands: &f/ffa join, leave, status, start, begin, pause, resume, stop, setlobby, setarena, setspawn, clearinv true/false, list, exclude, include, excluded, kit create/distribute/delete");
    }
    private String prefix() { return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&8[&bCasualFFA&8] ")); }
    private String names(Collection<UUID> ids) { List<String> n = new ArrayList<>(); for (UUID id : ids) { OfflinePlayer op = Bukkit.getOfflinePlayer(id); n.add(op.getName() == null ? id.toString() : op.getName()); } return n.isEmpty() ? "none" : String.join(", ", n); }

    private void join(Player p) {
        if (state != State.REGISTRATION) { tell(p, msg("not-open")); return; }
        if (excluded.contains(p.getUniqueId())) { tell(p, msg("excluded")); return; }
        if (participants.contains(p.getUniqueId()) || eliminated.contains(p.getUniqueId()) || snapshots.containsKey(p.getUniqueId())) { tell(p, msg("already-joined")); return; }
        Snapshot snap = Snapshot.capture(p);
        snapshots.put(p.getUniqueId(), snap);
        saveSnapshot(p.getUniqueId(), snap);
        participants.add(p.getUniqueId());
        if (clearInventory) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(new ItemStack[4]);
            p.getInventory().setItemInOffHand(null);
        }
        if (kitDistribution && kit != null) giveKit(p);
        teleportManaged(p, lobby);
        broadcast("joined", p.getName());
    }

    private void begin(CommandSender sender) {
        if (state != State.REGISTRATION) { tell(sender, "&cRegistration is not open."); return; }
        if (participants.isEmpty()) { tell(sender, "&cNobody has joined."); return; }
        state = State.RUNNING;
        alive.clear(); alive.addAll(participants);
        List<UUID> order = new ArrayList<>(participants); Collections.shuffle(order);
        int radius = Math.max(0, getConfig().getInt("spawn-radius", 35));
        for (UUID id : order) {
            Player p = Bukkit.getPlayer(id); if (p == null) continue;
            double angle = Math.random() * Math.PI * 2;
            double distance = Math.sqrt(Math.random()) * radius;
            Location spawn = arenaCenter.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            spawn.setY(arenaCenter.getWorld().getHighestBlockYAt(spawn) + 1.0);
            spawn.setDirection(arenaCenter.toVector().subtract(spawn.toVector()));
            teleportManaged(p, spawn);
        }
        Bukkit.broadcastMessage(prefix() + ChatColor.GOLD + "FFA tournament has STARTED! " + ChatColor.YELLOW + alive.size() + " players are fighting!");
        checkWinner();
    }

    private void giveKit(Player p) {
        if (kit == null) return;
        for (ItemStack item : kit) if (item != null) p.getInventory().addItem(item.clone()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void leave(Player p, boolean voluntary) {
        UUID id = p.getUniqueId();
        if (!participants.contains(id)) { tell(p, "&cYou are not registered."); return; }
        if (state == State.RUNNING || state == State.PAUSED) { tell(p, "&cYou cannot leave after the battle has begun."); return; }
        participants.remove(id); restore(p); if (voluntary) tell(p, "&eYou left the FFA.");
    }

    private void eliminate(Player p) {
        UUID id = p.getUniqueId();
        if (!alive.remove(id)) return;
        eliminated.add(id);
        pendingEliminationRestore.add(id);
        Bukkit.broadcastMessage(prefix() + ChatColor.RED + p.getName() + " has been eliminated!");
        checkWinner();
    }

    private void checkWinner() {
        if (state != State.RUNNING) return;
        // Offline participants remain alive until they return or are explicitly handled; disconnects are eliminated below.
        if (alive.size() == 1) {
            UUID winner = alive.iterator().next();
            Player p = Bukkit.getPlayer(winner);
            String name = p != null ? p.getName() : Bukkit.getOfflinePlayer(winner).getName();
            Bukkit.broadcastMessage(prefix() + ChatColor.GOLD + (name == null ? "A player" : name) + " is the last player standing and wins the FFA!");
            finishAfterWinner();
        } else if (alive.isEmpty()) {
            Bukkit.broadcastMessage(prefix() + ChatColor.YELLOW + "The FFA ended without a winner.");
            finishAfterWinner();
        }
    }

    private void finishAfterWinner() {
        for (UUID id : new HashSet<>(participants)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !pendingEliminationRestore.contains(id)) { restore(p); if (lobby != null) teleportManaged(p, lobby); }
        }
        state = State.IDLE; participants.clear(); alive.clear(); eliminated.clear(); kitDistribution = false;
    }

    private void stopTournament(CommandSender sender) {
        for (UUID id : new HashSet<>(snapshots.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) { restore(p); if (lobby != null) teleportManaged(p, lobby); } }
        state = State.IDLE; participants.clear(); alive.clear(); eliminated.clear(); kitDistribution = false;
        tell(sender, "&cFFA stopped. Online players' original inventories have been restored; offline backups are retained.");
    }

    private void restore(Player p) {
        Snapshot snap = snapshots.remove(p.getUniqueId());
        if (snap != null) { snap.restore(p); data.set("backups." + p.getUniqueId(), null); saveData(); }
    }

    private void setLocation(CommandSender sender, String which) {
        if (!(sender instanceof Player p)) { tell(sender, "&cPlayers only."); return; }
        Location loc = p.getLocation().clone();
        if (which.equals("lobby")) lobby = loc; else arenaCenter = loc;
        saveLocation("locations." + which, loc);
        tell(sender, "&a" + which + " location saved.");
    }
    private void saveLocation(String path, Location l) {
        data.set(path + ".world", l.getWorld().getName()); data.set(path + ".x", l.getX()); data.set(path + ".y", l.getY()); data.set(path + ".z", l.getZ()); data.set(path + ".yaw", l.getYaw()); data.set(path + ".pitch", l.getPitch()); saveData();
    }
    private Location readLocation(String path) {
        if (!data.contains(path + ".world")) return null;
        World w = Bukkit.getWorld(data.getString(path + ".world")); if (w == null) return null;
        return new Location(w, data.getDouble(path + ".x"), data.getDouble(path + ".y"), data.getDouble(path + ".z"), (float)data.getDouble(path + ".yaw"), (float)data.getDouble(path + ".pitch"));
    }

    private void kitCommand(CommandSender sender, String[] a) {
        if (a.length < 2) { tell(sender, "&eUsage: /ffa kit create/distribute/delete"); return; }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player p)) { tell(sender, "&cPlayers only."); return; }
                kit = p.getInventory().getContents().clone();
                data.set("kit", encodeItems(kit)); saveData();
                tell(sender, "&aFFA kit saved from your inventory.");
            }
            case "distribute" -> {
                if (kit == null) { tell(sender, "&cCreate a kit first with /ffa kit create."); return; }
                if (state != State.REGISTRATION) { tell(sender, "&cOpen registration first with /ffa start."); return; }
                kitDistribution = true;
                for (UUID id : participants) { Player p = Bukkit.getPlayer(id); if (p != null) giveKit(p); }
                tell(sender, "&aAutomatic kit distribution enabled. Current and future lobby joiners will receive the kit.");
            }
            case "delete" -> { kit = null; data.set("kit", null); saveData(); tell(sender, "&cFFA kit deleted."); }
            default -> tell(sender, "&eUsage: /ffa kit create/distribute/delete");
        }
    }

    private void changeExcluded(CommandSender sender, String[] a, boolean add) {
        if (a.length < 2) { tell(sender, "&eUsage: /ffa " + (add ? "exclude" : "include") + " <player>"); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(a[1]);
        if (target.getName() == null && !target.hasPlayedBefore()) { tell(sender, "&cPlayer not found."); return; }
        if (add) {
            excluded.add(target.getUniqueId());
            if (participants.contains(target.getUniqueId()) && state == State.REGISTRATION) { participants.remove(target.getUniqueId()); Player online = target.getPlayer(); if (online != null) restore(online); }
            tell(sender, "&a" + a[1] + " excluded from FFA participation.");
        } else { excluded.remove(target.getUniqueId()); tell(sender, "&a" + a[1] + " can participate again."); }
        data.set("excluded", excluded.stream().map(UUID::toString).toList()); saveData();
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (alive.contains(p.getUniqueId())) {
            e.getDrops().clear(); e.setDroppedExp(0);
            eliminate(p);
        }
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (pendingEliminationRestore.contains(p.getUniqueId())) {
            if (lobby != null) e.setRespawnLocation(lobby);
            Bukkit.getScheduler().runTask(this, () -> { pendingEliminationRestore.remove(p.getUniqueId()); restore(p); if (lobby != null) teleportManaged(p, lobby); });
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (alive.contains(id) && (state == State.RUNNING || state == State.PAUSED)) {
            Bukkit.getScheduler().runTask(this, () -> { alive.remove(id); eliminated.add(id); checkWinner(); });
        }
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Snapshot pending = snapshots.get(p.getUniqueId());
        if (pending != null && !participants.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> { restore(p); if (lobby != null) teleportManaged(p, lobby); p.sendMessage(prefix() + ChatColor.GREEN + "Your saved inventory has been restored."); });
        }
    }
    @EventHandler public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if ((participants.contains(id) || alive.contains(id)) && !e.getPlayer().hasPermission("casualffa.bypasslock")) {
            String cmd = e.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
            if (!cmd.equals("/ffa")) { e.setCancelled(true); tell(e.getPlayer(), "&cCommands are disabled while you are in the FFA."); }
        }
    }
    @EventHandler public void onPausedDamage(EntityDamageByEntityEvent e) {
        if (state != State.PAUSED) return;
        if (e.getEntity() instanceof Player victim && participants.contains(victim.getUniqueId())) e.setCancelled(true);
        if (e.getDamager() instanceof Player attacker && participants.contains(attacker.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (participants.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onPickup(PlayerAttemptPickupItemEvent e) { if (participants.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onTeleport(PlayerTeleportEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (participants.contains(id) && !managedTeleports.contains(id) && !e.getPlayer().hasPermission("casualffa.bypasslock")) e.setCancelled(true);
    }
    private void teleportManaged(Player p, Location destination) {
        if (destination == null) return;
        managedTeleports.add(p.getUniqueId());
        try { p.teleport(destination); } finally { managedTeleports.remove(p.getUniqueId()); }
    }

    private void saveSnapshot(UUID id, Snapshot snap) { data.set("backups." + id, snap.encode()); saveData(); }
    private void loadPendingSnapshots() {
        if (data.getConfigurationSection("backups") != null) {
            for (String key : data.getConfigurationSection("backups").getKeys(false)) {
                try { snapshots.put(UUID.fromString(key), Snapshot.decode(data.getString("backups." + key))); }
                catch (Exception ex) { getLogger().severe("Could not load inventory backup for " + key + ": " + ex.getMessage()); }
            }
        }
        for (String s : data.getStringList("excluded")) try { excluded.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
    }
    private void saveData() { try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save data.yml: " + e.getMessage()); } }
    private String encodeItems(ItemStack[] items) { try { ByteArrayOutputStream b = new ByteArrayOutputStream(); BukkitObjectOutputStream o = new BukkitObjectOutputStream(b); o.writeObject(items); o.close(); return Base64.getEncoder().encodeToString(b.toByteArray()); } catch (Exception e) { throw new IllegalStateException(e); } }
    private ItemStack[] decodeItems(String s) { try { BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(s))); ItemStack[] out = (ItemStack[]) in.readObject(); in.close(); return out; } catch (Exception e) { getLogger().warning("Could not decode kit: " + e.getMessage()); return null; } }

    private static final class Snapshot {
        ItemStack[] contents, armor; ItemStack offhand; int level, food; float exp, saturation; double health; GameMode mode; boolean allowFlight, flying; Collection<PotionEffect> effects;
        static Snapshot capture(Player p) {
            Snapshot s = new Snapshot(); s.contents = p.getInventory().getContents().clone(); s.armor = p.getInventory().getArmorContents().clone(); s.offhand = cloneItem(p.getInventory().getItemInOffHand());
            s.level = p.getLevel(); s.exp = p.getExp(); s.food = p.getFoodLevel(); s.saturation = p.getSaturation(); s.health = p.getHealth(); s.mode = p.getGameMode(); s.allowFlight = p.getAllowFlight(); s.flying = p.isFlying(); s.effects = new ArrayList<>(p.getActivePotionEffects()); return s;
        }
        void restore(Player p) {
            p.getInventory().clear(); p.getInventory().setContents(contents); p.getInventory().setArmorContents(armor); p.getInventory().setItemInOffHand(offhand);
            p.setLevel(level); p.setExp(exp); p.setFoodLevel(food); p.setSaturation(saturation); p.setGameMode(mode); p.setAllowFlight(allowFlight); if (allowFlight) p.setFlying(flying);
            for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType()); for (PotionEffect effect : effects) p.addPotionEffect(effect);
            double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(); p.setHealth(Math.max(0.5, Math.min(health, max)));
        }
        String encode() { try { ByteArrayOutputStream b = new ByteArrayOutputStream(); BukkitObjectOutputStream o = new BukkitObjectOutputStream(b); o.writeObject(contents); o.writeObject(armor); o.writeObject(offhand); o.writeInt(level); o.writeFloat(exp); o.writeInt(food); o.writeFloat(saturation); o.writeDouble(health); o.writeObject(mode.name()); o.writeBoolean(allowFlight); o.writeBoolean(flying); o.writeObject(new ArrayList<>(effects)); o.close(); return Base64.getEncoder().encodeToString(b.toByteArray()); } catch (Exception e) { throw new IllegalStateException(e); } }
        @SuppressWarnings("unchecked") static Snapshot decode(String encoded) throws Exception {
            BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))); Snapshot s = new Snapshot();
            s.contents = (ItemStack[]) in.readObject(); s.armor = (ItemStack[]) in.readObject(); s.offhand = (ItemStack) in.readObject(); s.level = in.readInt(); s.exp = in.readFloat(); s.food = in.readInt(); s.saturation = in.readFloat(); s.health = in.readDouble(); s.mode = GameMode.valueOf((String) in.readObject()); s.allowFlight = in.readBoolean(); s.flying = in.readBoolean(); s.effects = (Collection<PotionEffect>) in.readObject(); in.close(); return s;
        }
        static ItemStack cloneItem(ItemStack i) { return i == null ? null : i.clone(); }
    }
}
