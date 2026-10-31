package dev.events.uhc;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

/** Lobby protection + game lifecycle hooks (join, quit, death, respawn). */
public final class GameListener implements Listener {
    private final UHCPlugin plugin;

    GameListener(UHCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.recipes().discover(e.getPlayer());
        plugin.game().onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.game().onQuit(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        plugin.game().onDeath(p);
        if (plugin.game().isGameWorld(p.getWorld())) {
            // skip the death screen: respawn straight into spectator
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && p.isDead()) p.spigot().respawn();
            }, 2L);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        plugin.game().onRespawn(e);
    }

    // ---- lobby protection

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.lobby().isLobby(p.getWorld())) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) plugin.lobby().send(p);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (plugin.lobby().isLobby(p.getWorld())) {
            e.setCancelled(true);
        } else if (plugin.game().noSaturationActive()) {
            Bukkit.getScheduler().runTask(plugin, () -> p.setSaturation(0f));
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (plugin.lobby().isLobby(e.getLocation().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent e) {
        if (e.toWeatherState() && plugin.lobby().isLobby(e.getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (plugin.lobby().isLobby(e.getBlock().getWorld()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.lobby().isLobby(e.getBlock().getWorld()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent e) {
        if (plugin.game().isGameWorld(e.getFrom().getWorld()) && plugin.getConfig().getBoolean("game.disable-portals", true)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Util.err("Portals are disabled during the game."));
        }
    }
}
