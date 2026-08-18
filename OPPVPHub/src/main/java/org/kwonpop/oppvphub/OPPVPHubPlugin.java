package org.kwonpop.oppvphub;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Optional-integration coordinator for RandomOPPVP.
 * It never replaces another plugin's data or settings without an explicit hub command.
 */
public final class OPPVPHubPlugin extends JavaPlugin implements CommandExecutor, Listener {
    private static final String ADMIN_PERMISSION = "oppvphub.admin";
    private static final String JOIN_PERMISSION = "oppvphub.join";
    private static final String RANDOM_PLUGIN_NAME = "RandomOPPVP";

    private Plugin randomPlugin;
    private boolean multiversePresent;
    private boolean luckPermsPresent;
    private boolean coreProtectPresent;
    private Integer coreProtectApiVersion;
    private boolean worldGuardPresent;
    private final Map<String, Object> originalPvpFlags = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("oppvphub")).setExecutor(this);
        refreshDependencies();
        getLogger().info("OPPVPHub가 활성화되었습니다. /oppvphub status 로 상태를 확인하세요.");
    }

    @Override
    public void onDisable() {
        restoreRegionPvp();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        if (sub.equals("join")) return joinGameWorld(sender);
        if (!hasAdminAccess(sender)) {
            sender.sendMessage(color("&c허브 관리 권한이 없습니다."));
            return true;
        }
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "status" -> sendStatus(sender);
            case "prepare" -> prepare(sender);
            case "start" -> start(sender);
            case "end" -> end(sender);
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean hasAdminAccess(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission(ADMIN_PERMISSION) || sender.isOp();
    }

    private boolean joinGameWorld(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c플레이어만 참가할 수 있습니다."));
            return true;
        }
        if (!player.hasPermission(JOIN_PERMISSION)) {
            player.sendMessage(color("&c참가 권한이 없습니다."));
            return true;
        }
        World world = ensureGameWorld(false);
        if (world == null) return true;
        player.teleport(world.getSpawnLocation());
        player.sendMessage(color("&aPVP 월드로 이동했습니다. 진행자가 게임을 시작할 때까지 기다리세요."));
        return true;
    }

    private void prepare(CommandSender sender) {
        refreshDependencies();
        World world = ensureGameWorld(true);
        if (world == null) return;
        if (!selectRandomGameWorld(world)) {
            sender.sendMessage(color("&cRandomOPPVP에 게임 월드를 전달하지 못했습니다. 최신 허브 연동판 JAR인지 확인하세요."));
            return;
        }
        setRegionPvp(world, true);
        sender.sendMessage(color("&a준비 완료: &f" + world.getName() + "&a 월드를 게임 대상으로 지정했습니다."));
        sender.sendMessage(color("&7참가자는 /oppvphub join 으로 이동한 뒤 /oppvphub start 를 실행하세요."));
        audit("게임 준비 완료: world=" + world.getName());
    }

    private void start(CommandSender sender) {
        World world = ensureGameWorld(true);
        if (world == null) return;
        if (!selectRandomGameWorld(world)) {
            sender.sendMessage(color("&cRandomOPPVP가 없거나 허브 연동판이 아닙니다."));
            return;
        }
        int players = (int) world.getPlayers().stream().filter(p -> p.hasPermission(JOIN_PERMISSION)).count();
        int minimum = Math.max(1, getConfig().getInt("game.minimum-players", 2));
        if (players < minimum) {
            sender.sendMessage(color("&c게임 월드에 참가 권한이 있는 플레이어가 " + minimum + "명 이상 있어야 합니다. 현재: " + players + "명"));
            return;
        }
        setRegionPvp(world, true);
        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "랜덤op시작");
        if (!dispatched) {
            sender.sendMessage(color("&cRandomOPPVP 시작 명령을 실행하지 못했습니다."));
            return;
        }
        Bukkit.broadcastMessage(color("&6[OPPVPHub] &f" + world.getName() + " 월드에서 랜덤 OP PVP를 시작합니다."));
        sender.sendMessage(color("&a게임 시작 명령을 전달했습니다."));
        audit("게임 시작 요청: world=" + world.getName() + ", players=" + players);
    }

    private void end(CommandSender sender) {
        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "랜덤op종료");
        if (!dispatched) {
            sender.sendMessage(color("&cRandomOPPVP 종료 명령을 실행하지 못했습니다."));
            return;
        }
        restoreRegionPvp();
        sender.sendMessage(color("&a게임 종료 명령을 전달했고, 저장된 WorldGuard PVP 규칙을 복구했습니다."));
        audit("게임 종료 요청");
    }

    private void reload(CommandSender sender) {
        reloadConfig();
        refreshDependencies();
        sender.sendMessage(color("&aOPPVPHub 설정과 연동 상태를 새로 고쳤습니다."));
    }

    private World ensureGameWorld(boolean tryMultiverseLoad) {
        String name = getConfig().getString("game.world", "oppvp");
        World world = Bukkit.getWorld(name);
        if (world == null && tryMultiverseLoad && multiversePresent && getConfig().getBoolean("multiverse.auto-load-world", true)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + name);
            world = Bukkit.getWorld(name);
        }
        if (world == null) {
            getLogger().warning("게임 월드를 찾을 수 없습니다: " + name);
            return null;
        }
        return world;
    }

    private boolean selectRandomGameWorld(World world) {
        randomPlugin = Bukkit.getPluginManager().getPlugin(RANDOM_PLUGIN_NAME);
        if (randomPlugin == null || !randomPlugin.isEnabled()) return false;
        try {
            Method method = randomPlugin.getClass().getMethod("setGameWorld", String.class);
            Object result = method.invoke(randomPlugin, world.getName());
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("RandomOPPVP 허브 연동 메서드를 찾지 못했습니다: " + ex.getMessage());
            return false;
        }
    }

    private void refreshDependencies() {
        randomPlugin = Bukkit.getPluginManager().getPlugin(RANDOM_PLUGIN_NAME);
        multiversePresent = isEnabled("Multiverse-Core");
        luckPermsPresent = isEnabled("LuckPerms");
        coreProtectPresent = isEnabled("CoreProtect");
        Plugin coreProtect = Bukkit.getPluginManager().getPlugin("CoreProtect");
        coreProtectApiVersion = coreProtectPresent && coreProtect != null ? CoreProtectBridge.apiVersion(coreProtect) : null;
        worldGuardPresent = isEnabled("WorldGuard");
    }

    private boolean isEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    private void setRegionPvp(World world, boolean allow) {
        if (!getConfig().getBoolean("worldguard.manage-region-pvp", true) || !worldGuardPresent) return;
        String regionId = getConfig().getString("worldguard.region-id", "oppvp_arena");
        try {
            Object previous = WorldGuardBridge.setPvp(world, regionId, allow);
            if (previous == WorldGuardBridge.REGION_MISSING) {
                getLogger().warning("WorldGuard 지역을 찾지 못했습니다: " + regionId + " (PVP 규칙은 변경하지 않았습니다)");
                return;
            }
            String key = regionKey(world, regionId);
            if (!originalPvpFlags.containsKey(key)) originalPvpFlags.put(key, previous);
        } catch (LinkageError | RuntimeException ex) {
            getLogger().warning("WorldGuard 연동을 건너뜁니다: " + ex.getMessage());
        }
    }

    private void restoreRegionPvp() {
        if (!worldGuardPresent || originalPvpFlags.isEmpty()) return;
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(originalPvpFlags).entrySet()) {
            String[] split = entry.getKey().split("\\|", 2);
            World world = split.length == 2 ? Bukkit.getWorld(split[0]) : null;
            if (world == null) continue;
            try {
                WorldGuardBridge.restorePvp(world, split[1], entry.getValue());
            } catch (LinkageError | RuntimeException ex) {
                getLogger().warning("WorldGuard PVP 규칙 복구에 실패했습니다: " + ex.getMessage());
            }
        }
        originalPvpFlags.clear();
    }

    private void audit(String message) {
        getLogger().info("[AUDIT] " + message + (coreProtectPresent ? " | CoreProtect API " + (coreProtectApiVersion == null ? "확인 불가" : coreProtectApiVersion) : ""));
    }

    private String regionKey(World world, String regionId) { return world.getName() + "|" + regionId; }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&6&l===== OPPVP 허브 ====="));
        sender.sendMessage(color("&e/oppvphub join &7- PVP 월드 입장"));
        sender.sendMessage(color("&e/oppvphub prepare &7- 월드·보호 규칙 준비"));
        sender.sendMessage(color("&e/oppvphub start &7- 게임 시작"));
        sender.sendMessage(color("&e/oppvphub end &7- 게임 종료 및 PVP 규칙 복구"));
        sender.sendMessage(color("&e/oppvphub status &7- 연동 상태 확인"));
    }

    private void sendStatus(CommandSender sender) {
        refreshDependencies();
        sender.sendMessage(color("&6&l===== OPPVP 허브 상태 ====="));
        sender.sendMessage(statusLine("RandomOPPVP", randomPlugin != null && randomPlugin.isEnabled()));
        sender.sendMessage(statusLine("Multiverse-Core", multiversePresent));
        sender.sendMessage(statusLine("WorldGuard", worldGuardPresent));
        sender.sendMessage(statusLine("LuckPerms", luckPermsPresent));
        sender.sendMessage(statusLine("CoreProtect" + (coreProtectApiVersion == null ? "" : " API " + coreProtectApiVersion), coreProtectPresent));
        String world = getConfig().getString("game.world", "oppvp");
        sender.sendMessage(color("&f게임 월드: &b" + world + (Bukkit.getWorld(world) == null ? " &c(미로드)" : " &a(로드됨)")));
    }

    private String statusLine(String name, boolean found) {
        return color("&f" + name + ": " + (found ? "&a연결됨" : "&7미설치/비활성"));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("messages.show-join-hint", true)) {
            event.getPlayer().sendMessage(color("&7PVP 참가: &e/oppvphub join"));
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        String gameWorld = getConfig().getString("game.world", "oppvp");
        if (event.getPlayer().getWorld().getName().equalsIgnoreCase(gameWorld)) {
            event.getPlayer().sendMessage(color("&aPVP 월드에 입장했습니다. 진행자의 안내를 기다리세요."));
        }
    }
}
