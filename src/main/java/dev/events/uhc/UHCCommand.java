package dev.events.uhc;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /uhc <start|stop|status|spectate|exclude|setlobby> and /lobby */
public final class UHCCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("start", "stop", "status", "spectate", "exclude", "setlobby", "config");
    private final UHCPlugin plugin;

    UHCCommand(UHCPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean deny(CommandSender s) {
        s.sendMessage(Util.err("You don't have permission to do that."));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        GameManager g = plugin.game();

        if (cmd.getName().equalsIgnoreCase("lobby")) {
            if (!(s instanceof Player p)) return true;
            if (g.isAlive(p.getUniqueId())) {
                p.sendMessage(Util.err("You can't leave while you are still alive in the game."));
            } else {
                plugin.lobby().send(p);
            }
            return true;
        }

        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("uhc.admin");

        switch (sub) {
            case "start" -> {
                if (!admin) return deny(s);
                int secs = plugin.getConfig().getInt("game.countdown-seconds", 30);
                if (a.length > 1) {
                    try {
                        secs = Math.max(0, Integer.parseInt(a[1]));
                    } catch (NumberFormatException ex) {
                        s.sendMessage(Util.err("Usage: /uhc start [seconds]"));
                        return true;
                    }
                }
                String err = g.startCountdown(secs);
                if (err != null) s.sendMessage(Util.err(err));
                else Bukkit.broadcast(Util.msg("Countdown started - the game begins in " + secs + " seconds!"));
            }
            case "stop" -> {
                if (!admin) return deny(s);
                if (!g.stop()) s.sendMessage(Util.err("No game is running."));
            }
            case "status" -> s.sendMessage(Util.msg(g.status()));
            case "spectate" -> {
                if (!(s instanceof Player p)) return true;
                if (!g.isActive()) s.sendMessage(Util.err("There's no game to spectate right now."));
                else if (g.isAlive(p.getUniqueId())) s.sendMessage(Util.err("You're in the game!"));
                else g.spectate(p);
            }
            case "exclude" -> {
                if (!admin) return deny(s);
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null) {
                    s.sendMessage(Util.err("Usage: /uhc exclude <online player>  (toggles; excluded players are skipped at game start)"));
                    return true;
                }
                boolean now = g.toggleExcluded(t.getUniqueId());
                s.sendMessage(Util.msg(t.getName() + (now ? " will NOT take part in games." : " will take part in games.")));
            }
            case "config" -> {
                if (!admin) return deny(s);
                if (s instanceof Player p) plugin.gui().open(p);
            }
            case "setlobby" -> {
                if (!admin) return deny(s);
                if (!(s instanceof Player p)) return true;
                plugin.lobby().setSpawn(p.getLocation());
                s.sendMessage(Util.msg("Lobby spawn set to your location."));
            }
            default -> {
                s.sendMessage(Util.msg("Commands: /uhc start [seconds] | stop | status | spectate | exclude <player> | setlobby, /lobby, /uhcconfig"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!cmd.getName().equalsIgnoreCase("uhc")) return List.of();
        if (a.length == 1) {
            return SUBS.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("exclude")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
