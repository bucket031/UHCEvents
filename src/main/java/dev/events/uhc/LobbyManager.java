package dev.events.uhc;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.Random;

public final class LobbyManager {
    private final UHCPlugin plugin;
    private World world;

    LobbyManager(UHCPlugin plugin) {
        this.plugin = plugin;
    }

    /** Loads the lobby world, creating an empty void world with a platform if it does not exist yet. */
    void load() {
        String name = plugin.getConfig().getString("lobby.world", "lobby");
        world = Bukkit.getWorld(name);
        if (world == null) {
            boolean fresh = !new File(Bukkit.getWorldContainer(), name).exists();
            world = new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false).createWorld();
            if (world != null && fresh) buildPlatform();
        }
        if (world == null) {
            plugin.getLogger().severe("Could not load or create the lobby world '" + name + "'!");
            return;
        }
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setPVP(false);
        world.setStorm(false);
        world.setTime(6000);
        world.setSpawnLocation(spawn().getBlockX(), spawn().getBlockY(), spawn().getBlockZ());
    }

    private void buildPlatform() {
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                boolean edge = Math.max(Math.abs(x), Math.abs(z)) == 10;
                world.getBlockAt(x, 64, z).setType(edge ? Material.SEA_LANTERN : Material.POLISHED_ANDESITE);
            }
        }
    }

    void tick() {
        if (world == null) return;
        world.setTime(6000);
        world.setStorm(false);
    }

    public World world() {
        return world;
    }

    public boolean isLobby(World w) {
        return world != null && world.equals(w);
    }

    public Location spawn() {
        World w = world != null ? world : Bukkit.getWorlds().get(0);
        FileConfiguration c = plugin.getConfig();
        return new Location(w, c.getDouble("lobby.x", 0.5), c.getDouble("lobby.y", 65), c.getDouble("lobby.z", 0.5),
                (float) c.getDouble("lobby.yaw", 0), 0f);
    }

    public void setSpawn(Location l) {
        FileConfiguration c = plugin.getConfig();
        c.set("lobby.world", l.getWorld().getName());
        c.set("lobby.x", l.getX());
        c.set("lobby.y", l.getY());
        c.set("lobby.z", l.getZ());
        c.set("lobby.yaw", (double) l.getYaw());
        plugin.saveConfig();
        world = l.getWorld();
    }

    /** Resets the player and sends them to the lobby in adventure mode. */
    public void send(Player p) {
        plugin.game().hideBar(p);
        GameManager.reset(p, GameMode.ADVENTURE);
        p.teleportAsync(spawn());
    }

    /** Empty chunks everywhere = void. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World w, Random random) {
            return new Location(w, 0.5, 65, 0.5);
        }
    }
}
