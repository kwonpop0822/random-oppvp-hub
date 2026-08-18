package org.kwonpop.randomOPPVP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

public final class RandomOPPVPPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final long OP_DURATION_SECONDS = 10L;
    private static final long OP_INTERVAL_SECONDS = 60L;
    private static final long RESOURCE_TIME_SECONDS = 300L;
    private static final long BORDER_SHRINK_SECONDS = 180L;
    private static final double INITIAL_BORDER_SIZE = 1000.0D;
    private static final double FINAL_BORDER_SIZE = 50.0D;
    private static final double BORDER_DAMAGE = 4.0D;

    private boolean gameRunning;
    private boolean killTimeActive;
    private UUID currentOpPlayer;
    private final Map<UUID, Boolean> initialOpStatus = new HashMap<>();
    private final Map<UUID, GameMode> initialGameModes = new HashMap<>();
    private final Set<UUID> participants = new HashSet<>();
    private long gameStartTime;
    private long opGrantTime;
    private long borderShrinkStartTime;
    private BukkitTask opRotationTask;
    private BukkitTask killTimeTask;
    private BukkitTask scoreboardTask;
    private BukkitTask borderDamageTask;
    private Scoreboard mainScoreboard;
    private Objective objective;
    private WorldBorder gameWorldBorder;
    private World gameWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        mainScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = mainScoreboard.registerNewObjective("random_op_pvp", Criteria.DUMMY, "§e§l랜덤 OP PVP");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        String configuredWorld = getConfig().getString("game-world", "");
        gameWorld = configuredWorld.isBlank() ? (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0)) : Bukkit.getWorld(configuredWorld);
        if (gameWorld == null && !configuredWorld.isBlank()) {
            getLogger().warning("설정된 게임 월드를 찾을 수 없어 첫 번째 월드를 사용합니다: " + configuredWorld);
            gameWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }
        if (gameWorld != null) {
            gameWorldBorder = gameWorld.getWorldBorder();
            gameWorldBorder.reset();
        }
        for (String name : List.of("랜덤op시작", "랜덤op종료", "킬타임시작", "게임명령어")) {
            getCommand(name).setExecutor(this);
        }
        resetScoreboard();
        getLogger().info("랜덤 OP PVP 플러그인이 활성화되었습니다.");
    }

    @Override
    public void onDisable() {
        if (gameRunning || !initialOpStatus.isEmpty()) endGame(false);
        cancelTasks();
        if (gameWorldBorder != null) gameWorldBorder.reset();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !player.isOp() && !player.hasPermission("randomoppvp.admin")) {
            player.sendMessage("§c당신은 이 명령어를 사용할 권한이 없습니다.");
            return true;
        }
        switch (command.getName()) {
            case "랜덤op시작" -> {
                if (gameRunning) { sender.sendMessage("§c이미 게임이 진행 중입니다."); return true; }
                startGame();
            }
            case "랜덤op종료" -> {
                if (!gameRunning) { sender.sendMessage("§c진행 중인 게임이 없습니다."); return true; }
                endGame(true);
            }
            case "킬타임시작" -> {
                if (!gameRunning) { sender.sendMessage("§c게임이 진행 중일 때만 사용할 수 있습니다."); return true; }
                if (killTimeActive) { sender.sendMessage("§c이미 킬 타임입니다."); return true; }
                startKillTime();
            }
            case "게임명령어" -> sendHelp(sender);
            default -> { return false; }
        }
        return true;
    }

    /** 허브가 대기실과 분리된 PVP 월드를 지정할 때 호출합니다. */
    public boolean setGameWorld(String worldName) {
        if (gameRunning) return false;
        World requested = Bukkit.getWorld(worldName);
        if (requested == null) return false;
        gameWorld = requested;
        gameWorldBorder = requested.getWorldBorder();
        getConfig().set("game-world", requested.getName());
        saveConfig();
        return true;
    }

    public String getGameWorldName() { return gameWorld == null ? null : gameWorld.getName(); }
    public boolean isGameRunning() { return gameRunning; }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§e§l===== 랜덤 OP PVP =====");
        sender.sendMessage("§a/랜덤op시작 §7- 게임 시작");
        sender.sendMessage("§a/랜덤op종료 §7- 게임 종료 및 권한 복구");
        sender.sendMessage("§a/킬타임시작 §7- 킬 타임 즉시 시작");
        sender.sendMessage("§a/게임명령어 §7- 명령어 목록");
    }

    private void startGame() {
        resetState();
        if (gameWorld == null) {
            Bukkit.broadcastMessage("§c게임 월드를 찾을 수 없어 게임을 시작할 수 없습니다.");
            return;
        }
        gameRunning = true;
        gameStartTime = now();
        World world = gameWorld;
        if (world != null) {
            gameWorldBorder = world.getWorldBorder();
            gameWorldBorder.reset();
            gameWorldBorder.setCenter(world.getSpawnLocation());
            gameWorldBorder.setSize(INITIAL_BORDER_SIZE);
            gameWorldBorder.setDamageAmount(BORDER_DAMAGE);
            gameWorldBorder.setDamageBuffer(0.0D);
            gameWorldBorder.setWarningDistance(100);
            gameWorldBorder.setWarningTime(30);
        }
        for (Player player : world.getPlayers()) addParticipant(player);
        Bukkit.broadcastMessage("§a랜덤 OP PVP 게임을 시작합니다! 자원 시간 §b5분§a 동안 준비하세요!");
        Bukkit.broadcastMessage("§a킬 타임 전에는 모든 피해가 차단됩니다.");
        killTimeTask = Bukkit.getScheduler().runTaskLater(this, this::startKillTime, RESOURCE_TIME_SECONDS * 20L);
        opRotationTask = Bukkit.getScheduler().runTaskTimer(this, this::rotateOp, OP_INTERVAL_SECONDS * 20L, OP_INTERVAL_SECONDS * 20L);
        scoreboardTask = Bukkit.getScheduler().runTaskTimer(this, this::updateScoreboard, 0L, 20L);
        borderDamageTask = Bukkit.getScheduler().runTaskTimer(this, this::applyBorderDamage, 20L, 20L);
    }

    private void addParticipant(Player player) {
        UUID id = player.getUniqueId();
        participants.add(id);
        initialOpStatus.putIfAbsent(id, player.isOp());
        initialGameModes.putIfAbsent(id, player.getGameMode());
        if (player.isOp()) {
            player.setOp(false);
            player.sendMessage("§c[시스템] 게임 중 OP 권한이 임시 해제되었습니다.");
        }
        player.setGameMode(GameMode.SURVIVAL);
        clearEffects(player);
        player.setHealth(player.getMaxHealth());
        player.setScoreboard(mainScoreboard);
    }

    private void rotateOp() {
        if (!gameRunning || killTimeActive) return;
        revokeCurrentOp();
        List<Player> candidates = aliveParticipants();
        if (candidates.isEmpty()) return;
        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        currentOpPlayer = target.getUniqueId();
        opGrantTime = now();
        clearEffects(target);
        target.setOp(true);
        Bukkit.broadcastMessage("§a§l" + target.getName() + " 님이 §b§l10초§a 동안 OP 권한을 획득했습니다!");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (target.getUniqueId().equals(currentOpPlayer)) revokeCurrentOp();
        }, OP_DURATION_SECONDS * 20L);
    }

    private void startKillTime() {
        if (!gameRunning || killTimeActive) return;
        killTimeActive = true;
        revokeCurrentOp();
        if (killTimeTask != null) { killTimeTask.cancel(); killTimeTask = null; }
        if (gameWorldBorder != null) {
            gameWorldBorder.setSize(FINAL_BORDER_SIZE, BORDER_SHRINK_SECONDS * 20L);
            borderShrinkStartTime = now();
        }
        for (Player player : gameWorld.getPlayers()) {
            if (!participants.contains(player.getUniqueId())) continue;
            clearEffects(player);
            player.setGameMode(GameMode.SURVIVAL);
            player.sendTitle("§c§l킬 타임 시작!", "§4§l모두 싸워라!", 10, 70, 20);
        }
        Bukkit.broadcastMessage("§c§l킬 타임이 시작되었습니다!");
    }

    private void endGame(boolean announce) {
        gameRunning = false;
        killTimeActive = false;
        cancelTasks();
        revokeCurrentOp();
        for (UUID id : new HashSet<>(participants)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Boolean wasOp = initialOpStatus.get(id);
                if (wasOp != null) player.setOp(wasOp);
                GameMode mode = initialGameModes.get(id);
                if (mode != null) player.setGameMode(mode);
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            } else if (Boolean.TRUE.equals(initialOpStatus.get(id))) {
                Bukkit.getOfflinePlayer(id).setOp(true);
            }
        }
        participants.clear(); initialOpStatus.clear(); initialGameModes.clear(); currentOpPlayer = null;
        if (gameWorldBorder != null) gameWorldBorder.reset();
        resetScoreboard();
        if (announce) Bukkit.broadcastMessage("§c랜덤 OP PVP 게임이 종료되었습니다.");
    }

    private void revokeCurrentOp() {
        if (currentOpPlayer == null) return;
        Player player = Bukkit.getPlayer(currentOpPlayer);
        if (player != null && player.isOp()) {
            player.setOp(false);
            Bukkit.broadcastMessage("§c" + player.getName() + " 님의 OP 권한이 회수되었습니다.");
        }
        currentOpPlayer = null;
    }

    private void resetState() {
        cancelTasks();
        gameRunning = false; killTimeActive = false; currentOpPlayer = null;
        participants.clear(); initialOpStatus.clear(); initialGameModes.clear();
        resetScoreboard();
    }

    private void cancelTasks() {
        BukkitTask[] tasks = {opRotationTask, killTimeTask, scoreboardTask, borderDamageTask};
        for (BukkitTask task : tasks) if (task != null) task.cancel();
        opRotationTask = killTimeTask = scoreboardTask = borderDamageTask = null;
    }

    private List<Player> aliveParticipants() {
        return participants.stream().map(Bukkit::getPlayer).filter(p -> p != null && p.isOnline() && p.getWorld().equals(gameWorld) && p.getGameMode() == GameMode.SURVIVAL && !p.isDead() && p.getHealth() > 0).collect(Collectors.toCollection(ArrayList::new));
    }

    private void updateScoreboard() {
        if (objective == null) return;
        for (String entry : new HashSet<>(mainScoreboard.getEntries())) mainScoreboard.resetScores(entry);
        int score = 0;
        objective.getScore("§e§l랜덤 OP PVP").setScore(++score);
        objective.getScore("§f게임 상태: §a" + (gameRunning ? "진행 중" : "종료됨")).setScore(++score);
        objective.getScore("§f생존 플레이어: §b" + aliveParticipants().size()).setScore(++score);
        objective.getScore("§f킬 타임: §c" + (killTimeActive ? "활성화" : "비활성화")).setScore(++score);
        long resource = gameRunning && !killTimeActive ? Math.max(0, RESOURCE_TIME_SECONDS - (now() - gameStartTime)) : 0;
        objective.getScore("§f킬 타임까지: §e" + (killTimeActive ? "진행 중" : formatTime(resource))).setScore(++score);
        String op = currentOpPlayer == null ? "§7없음" : Bukkit.getOfflinePlayer(currentOpPlayer).getName();
        objective.getScore("§f현재 OP: §b" + op).setScore(++score);
        if (gameWorldBorder != null) objective.getScore("§f현재 경계: §b" + (int) gameWorldBorder.getSize() + "x" + (int) gameWorldBorder.getSize()).setScore(++score);
    }

    private void resetScoreboard() { if (objective != null) for (String entry : new HashSet<>(mainScoreboard.getEntries())) mainScoreboard.resetScores(entry); }
    private void clearEffects(Player player) { player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType())); }
    private long now() { return System.currentTimeMillis() / 1000L; }
    private String formatTime(long seconds) { return String.format("%02d:%02d", seconds / 60, seconds % 60); }

    private void applyBorderDamage() {
        if (!gameRunning || !killTimeActive || gameWorldBorder == null) return;
        for (Player player : aliveParticipants()) {
            if (!gameWorldBorder.isInside(player.getLocation())) {
                player.damage(BORDER_DAMAGE);
                player.sendActionBar("§c경계 밖입니다. 안으로 들어오세요!");
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (gameRunning && !killTimeActive && entity instanceof Player player && participants.contains(player.getUniqueId()) && !event.isCancelled()) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (gameRunning && player.getWorld().equals(gameWorld)) {
            addParticipant(player);
            player.sendMessage("§a[시스템] 현재 랜덤 OP PVP 게임이 진행 중입니다.");
        } else player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        updateScoreboard();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (id.equals(currentOpPlayer)) revokeCurrentOp();
        if (gameRunning && participants.contains(id)) checkForWinner();
        updateScoreboard();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!gameRunning || !participants.contains(victim.getUniqueId())) return;
        event.setDeathMessage("§c§l" + victim.getName() + " 님이 사망했습니다!");
        if (victim.getUniqueId().equals(currentOpPlayer)) revokeCurrentOp();
        Bukkit.getScheduler().runTask(this, this::checkForWinner);
        updateScoreboard();
    }

    private void checkForWinner() {
        if (!gameRunning) return;
        List<Player> alive = aliveParticipants();
        if (alive.size() == 1 && participants.size() > 1) {
            Bukkit.broadcastMessage("§a§l최후의 승자는 " + alive.get(0).getName() + " 님입니다!");
            endGame(true);
        } else if (alive.isEmpty()) {
            Bukkit.broadcastMessage("§e모든 플레이어가 사망했습니다.");
            endGame(true);
        }
    }
}
