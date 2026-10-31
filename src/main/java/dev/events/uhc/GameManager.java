package dev.events.uhc;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Owns the whole game lifecycle: countdown -> teleport -> grace -> PvP + shrinking border -> winner -> lobby. */
public final class GameManager {
    public enum State { LOBBY, COUNTDOWN, GRACE, PVP, ENDING }

    private static final Set<Material> BAD_GROUND = Set.of(Material.CACTUS, Material.MAGMA_BLOCK, Material.FIRE,
            Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.LAVA, Material.WATER);

    private final UHCPlugin plugin;
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<UUID> excluded = new HashSet<>();
    private final Map<UUID, BukkitTask> dcTimers = new HashMap<>();
    private final Map<UUID, Location> deathSpots = new HashMap<>();
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);

    private State state = State.LOBBY;
    private World gameWorld;
    private BukkitTask loop;
    private BukkitTask endTask;

    private int elapsed, countdown, graceSeconds, shrinkAt, shrinkSeconds;
    private double startSize, finalSize;
    private boolean shrinking, maceCrafted, oneMace, noSaturation;
    private PotionEffectType effect;
    private int effectAmp;

    GameManager(UHCPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ------------------------------------------------------------------ queries

    public State state() { return state; }
    public boolean isActive() { return state == State.GRACE || state == State.PVP; }
    public boolean isAlive(UUID id) { return alive.contains(id); }
    public boolean isGameWorld(World w) { return gameWorld != null && gameWorld.equals(w); }
    public boolean oneMaceActive() { return isActive() && oneMace; }
    public boolean maceCrafted() { return maceCrafted; }
    public boolean noSaturationActive() { return isActive() && noSaturation; }

    public boolean toggleExcluded(UUID id) {
        if (!excluded.remove(id)) {
            excluded.add(id);
            return true;
        }
        return false;
    }

    public String status() {
        return "State: " + state + (gameWorld != null ? " | World: " + gameWorld.getName() : "")
                + " | Alive: " + alive.size() + " | Lobby players: " + participants().size();
    }

    private List<Player> participants() {
        World lobby = plugin.lobby().world();
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getWorld().equals(lobby) && !excluded.contains(p.getUniqueId()))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ player reset

    static void reset(Player p, GameMode mode) {
        p.getInventory().clear();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setGameMode(mode);
        p.setInvulnerable(false);
        p.setLevel(0);
        p.setExp(0f);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    void hideBar(Player p) {
        p.hideBossBar(bar);
    }

    // ------------------------------------------------------------------ countdown

    /** @return an error message, or null on success. */
    public String startCountdown(int seconds) {
        if (state != State.LOBBY) return "A game is already running.";
        int min = cfg().getInt("game.min-players", 2);
        if (participants().size() < min) return "Need at least " + min + " players in the lobby.";

        World w = createWorld();
        if (w == null) return "Could not create the game world.";
        gameWorld = w;
        state = State.COUNTDOWN;
        countdown = seconds;
        loop = Bukkit.getScheduler().runTaskTimer(plugin, this::countdownTick, 0L, 20L);
        return null;
    }

    private void countdownTick() {
        if (countdown <= 0) {
            loop.cancel();
            begin();
            return;
        }
        if (countdown <= 5) {
            titleAll(String.valueOf(countdown), "Get ready!", NamedTextColor.YELLOW, Sound.BLOCK_NOTE_BLOCK_PLING);
        } else if (countdown == 10 || countdown % 30 == 0) {
            Bukkit.broadcast(Util.msg("The game starts in " + countdown + " seconds!"));
        }
        countdown--;
    }

    private World createWorld() {
        FileConfiguration c = cfg();
        String name = "uhc_" + System.currentTimeMillis();
        World w = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .generateStructures(c.getBoolean("worldgen.structures-enabled", true))
                .createWorld();
        if (w == null) return null;

        startSize = c.getDouble("border.start-size", 2000);
        finalSize = c.getDouble("border.final-size", 30);
        graceSeconds = c.getInt("game.grace-minutes", 15) * 60;
        shrinkAt = graceSeconds + c.getInt("border.shrink-delay-minutes", 0) * 60;
        shrinkSeconds = c.getInt("border.shrink-minutes", 30) * 60;
        noSaturation = c.getBoolean("modes.no-saturation", false);
        oneMace = c.getBoolean("modes.one-mace", false);

        WorldBorder b = w.getWorldBorder();
        b.setCenter(0, 0);
        b.setSize(startSize);
        b.setWarningDistance(10);

        w.setPVP(false);
        w.setDifficulty(Difficulty.NORMAL);
        w.setTime(0);
        w.setStorm(false);
        w.setGameRule(GameRule.LOCATOR_BAR, c.getBoolean("game.locator-bar", true));
        w.setSpawnLocation(0, w.getHighestBlockYAt(0, 0) + 1, 0);
        return w;
    }

    // ------------------------------------------------------------------ start

    private void begin() {
        List<Player> players = participants();
        int min = cfg().getInt("game.min-players", 2);
        if (players.size() < min) {
            Bukkit.broadcast(Util.err("Not enough players - game cancelled."));
            returnToLobby();
            return;
        }
        alive.clear();
        deathSpots.clear();
        maceCrafted = false;
        resolveEffect();

        for (Player p : players) {
            alive.add(p.getUniqueId());
            reset(p, GameMode.SURVIVAL);
            p.showBossBar(bar);
        }
        titleAll("GOOD LUCK", "Teleporting...", NamedTextColor.GREEN, Sound.ENTITY_PLAYER_LEVELUP);
        announceModes();
        spread(new ArrayDeque<>(players), new ArrayList<>(), cfg().getDouble("teleport.min-distance", 250), 0);
    }

    private void resolveEffect() {
        effect = null;
        effectAmp = cfg().getInt("modes.effect-amplifier", 0);
        String v = cfg().getString("modes.effect", "NONE").toLowerCase(Locale.ROOT);
        if (v.equals("none")) return;
        if (v.equals("random")) {
            List<String> pool = cfg().getStringList("modes.effect-pool");
            if (pool.isEmpty()) return;
            v = pool.get(ThreadLocalRandom.current().nextInt(pool.size())).toLowerCase(Locale.ROOT);
        }
        effect = Registry.EFFECT.get(NamespacedKey.minecraft(v));
        if (effect == null) {
            plugin.getLogger().warning("Unknown effect '" + v + "' in modes.effect");
        } else {
            Bukkit.broadcast(Util.msg("Special event: everybody has " + v.replace('_', ' ').toUpperCase(Locale.ROOT)
                    + " for the whole game!"));
        }
    }

    private void announceModes() {
        if (noSaturation) Bukkit.broadcast(Util.msg("Mode: NO SATURATION"));
        if (oneMace) Bukkit.broadcast(Util.msg("Mode: ONE MACE - only a single mace can be crafted this game!"));
    }

    /** Teleports players one by one to random, well-spaced, dry spots. Chunks load asynchronously. */
    private void spread(Deque<Player> queue, List<Location> placed, double minDist, int fails) {
        Player p = queue.peek();
        if (p == null) {
            startTimeline();
            return;
        }
        if (!p.isOnline()) {
            queue.poll();
            alive.remove(p.getUniqueId());
            spread(queue, placed, minDist, 0);
            return;
        }

        double half = Math.max(10, startSize / 2.0 - cfg().getDouble("teleport.edge-margin", 150));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double md = minDist;
        int x, z, guard = 0;
        do {
            x = (int) r.nextDouble(-half, half);
            z = (int) r.nextDouble(-half, half);
            if (++guard % 20 == 0) md *= 0.8; // map getting crowded: relax spacing
        } while (!farEnough(placed, x, z, md));

        final int fx = x, fz = z;
        final double fmd = md;
        final World w = gameWorld;
        w.getChunkAtAsync(fx >> 4, fz >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (state != State.COUNTDOWN || gameWorld != w) return;
            Block top = w.getHighestBlockAt(fx, fz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if ((top.isLiquid() || BAD_GROUND.contains(top.getType())) && fails < 60) {
                spread(queue, placed, fmd, fails + 1);
                return;
            }
            Location loc = top.getLocation().add(0.5, 1, 0.5);
            queue.poll();
            placed.add(loc);
            p.teleportAsync(loc).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 4, false, false));
                spread(queue, placed, fmd, 0);
            }));
        }));
    }

    private static boolean farEnough(List<Location> placed, int x, int z, double minDist) {
        for (Location l : placed) {
            if (Math.hypot(l.getX() - x, l.getZ() - z) < minDist) return false;
        }
        return true;
    }

    private void startTimeline() {
        if (state != State.COUNTDOWN) return;
        state = State.GRACE;
        elapsed = 0;
        shrinking = false;
        titleAll("GRACE PERIOD", "No PvP for " + (graceSeconds / 60) + " minutes", NamedTextColor.GREEN,
                Sound.BLOCK_NOTE_BLOCK_PLING);
        loop = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        checkWin();
    }

    // ------------------------------------------------------------------ main loop (1 Hz)

    private void tick() {
        elapsed++;
        if (state == State.GRACE) {
            int left = graceSeconds - elapsed;
            if (left <= 0) {
                startPvp();
            } else {
                bar.name(Component.text("Grace period - PvP in " + Util.clock(left), NamedTextColor.GREEN));
                bar.progress(clamp(left / (float) graceSeconds));
                if (left == 60 || left == 30 || left == 10 || left <= 5) {
                    Bukkit.broadcast(Util.msg("PvP enables in " + left + (left == 1 ? " second" : " seconds") + "!"));
                }
            }
        }
        if (state == State.PVP) {
            if (!shrinking && elapsed >= shrinkAt) startShrink();
            updatePvpBar();
        }
        applyModes();
    }

    private void startPvp() {
        state = State.PVP;
        gameWorld.setPVP(true);
        bar.color(BossBar.Color.RED);
        titleAll("PVP ENABLED", "Grace period is over!", NamedTextColor.RED, Sound.ENTITY_ENDER_DRAGON_GROWL);
    }

    private void startShrink() {
        shrinking = true;
        gameWorld.getWorldBorder().setSize(finalSize, shrinkSeconds);
        Bukkit.broadcast(Util.msg("The border is shrinking to " + (int) finalSize + "x" + (int) finalSize + "!"));
    }

    private void updatePvpBar() {
        if (!shrinking) {
            bar.name(Component.text("Border shrinks in " + Util.clock(shrinkAt - elapsed), NamedTextColor.RED));
            bar.progress(1f);
            return;
        }
        double size = gameWorld.getWorldBorder().getSize();
        bar.name(Component.text("Border: " + (int) size + " x " + (int) size, NamedTextColor.RED));
        bar.progress(clamp((float) ((size - finalSize) / Math.max(1, startSize - finalSize))));
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private void applyModes() {
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (noSaturation && p.getSaturation() > 0f) p.setSaturation(0f);
            if (effect != null && !p.hasPotionEffect(effect)) {
                p.addPotionEffect(new PotionEffect(effect, PotionEffect.INFINITE_DURATION, effectAmp, true, false, true));
            }
        }
    }

    public void markMaceCrafted(String who) {
        maceCrafted = true;
        Bukkit.broadcast(Util.msg(who + " crafted THE mace! No more maces can be crafted this game."));
    }

    // ------------------------------------------------------------------ eliminations & winning

    public void onDeath(Player p) {
        if (!isActive() || !alive.contains(p.getUniqueId())) return;
        deathSpots.put(p.getUniqueId(), p.getLocation());
        eliminate(p.getUniqueId(), "was eliminated");
    }

    public void onRespawn(PlayerRespawnEvent e) {
        Location spot = deathSpots.remove(e.getPlayer().getUniqueId());
        if (spot == null || gameWorld == null) return;
        e.setRespawnLocation(spot);
        Bukkit.getScheduler().runTask(plugin, () -> e.getPlayer().setGameMode(GameMode.SPECTATOR));
    }

    private void eliminate(UUID id, String reason) {
        if (!alive.remove(id)) return;
        BukkitTask t = dcTimers.remove(id);
        if (t != null) t.cancel();
        String name = Bukkit.getOfflinePlayer(id).getName();
        Bukkit.broadcast(Util.msg((name == null ? "A player" : name) + " " + reason + ". " + alive.size() + " remaining."));
        checkWin();
    }

    private void checkWin() {
        if (!isActive() || alive.size() > 1) return;
        finish(alive.stream().findFirst().orElse(null));
    }

    private void finish(UUID winner) {
        state = State.ENDING;
        if (loop != null) loop.cancel();
        gameWorld.setPVP(false);

        String name = winner == null ? null : Bukkit.getOfflinePlayer(winner).getName();
        int secs = cfg().getInt("game.win-screen-seconds", 10);
        Title title = Title.title(
                Component.text(name == null ? "NOBODY WON" : name + " WON", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Returning to the lobby shortly...", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(secs), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            p.hideBossBar(bar);
        }
        Player w = winner == null ? null : Bukkit.getPlayer(winner);
        if (w != null) w.setInvulnerable(true);

        endTask = Bukkit.getScheduler().runTaskLater(plugin, this::returnToLobby, secs * 20L);
    }

    // ------------------------------------------------------------------ join / quit / spectate

    public void onJoin(Player p) {
        BukkitTask t = dcTimers.remove(p.getUniqueId());
        if (t != null) t.cancel();
        if (isActive() && alive.contains(p.getUniqueId())) {
            p.showBossBar(bar);
            return;
        }
        plugin.lobby().send(p);
        if (isActive()) p.sendMessage(Util.msg("A game is in progress. Use /uhc spectate to watch."));
    }

    public void onQuit(Player p) {
        UUID id = p.getUniqueId();
        if (!isActive() || !alive.contains(id)) return;
        int timeout = cfg().getInt("game.disconnect-timeout-seconds", 180);
        dcTimers.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            dcTimers.remove(id);
            eliminate(id, "disconnected and was eliminated");
        }, timeout * 20L));
    }

    public void spectate(Player p) {
        if (!isActive() || alive.contains(p.getUniqueId())) return;
        p.setGameMode(GameMode.SPECTATOR);
        p.showBossBar(bar);
        p.teleportAsync(gameWorld.getSpawnLocation().add(0.5, 10, 0.5));
    }

    // ------------------------------------------------------------------ end / cleanup

    /** Admin abort (also cancels a running countdown). */
    public boolean stop() {
        if (state == State.LOBBY) return false;
        if (loop != null) loop.cancel();
        if (endTask != null) endTask.cancel();
        Bukkit.broadcast(Util.err("The game was stopped by an admin."));
        returnToLobby();
        return true;
    }

    private void returnToLobby() {
        if (loop != null) loop.cancel();
        if (endTask != null) endTask.cancel();
        state = State.LOBBY;
        World old = gameWorld;
        gameWorld = null;
        alive.clear();
        deathSpots.clear();
        dcTimers.values().forEach(BukkitTask::cancel);
        dcTimers.clear();
        for (Player p : Bukkit.getOnlinePlayers()) plugin.lobby().send(p);
        destroyWorld(old, 0);
    }

    private void destroyWorld(World w, int attempt) {
        if (w == null) return;
        File folder = w.getWorldFolder();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : w.getPlayers()) plugin.lobby().send(p);
            if (!w.getPlayers().isEmpty() || !Bukkit.unloadWorld(w, false)) {
                if (attempt < 5) destroyWorld(w, attempt + 1);
                return;
            }
            if (cfg().getBoolean("game.delete-world-after-game", true)) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> Util.deleteRecursive(folder));
            }
        }, 60L);
    }

    void shutdown() {
        if (loop != null) loop.cancel();
        if (endTask != null) endTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
    }

    // ------------------------------------------------------------------ helpers

    private void titleAll(String big, String small, NamedTextColor color, Sound sound) {
        Title t = Title.title(Component.text(big, color, TextDecoration.BOLD),
                Component.text(small, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(300)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(t);
            if (sound != null) p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }
}
