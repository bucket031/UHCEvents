package dev.events.uhc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /uhcconfig <list [prefix] | get <key> | set <key> <value> | toggle <key> | reload> */
public final class ConfigCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("menu", "list", "get", "set", "toggle", "reload");
    private final UHCPlugin plugin;

    ConfigCommand(UHCPlugin plugin) {
        this.plugin = plugin;
    }

    private List<String> keys() {
        FileConfiguration c = plugin.getConfig();
        return c.getKeys(true).stream().filter(k -> !c.isConfigurationSection(k)).sorted().toList();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("uhc.admin")) {
            s.sendMessage(Util.err("You don't have permission to do that."));
            return true;
        }
        FileConfiguration c = plugin.getConfig();
        String sub = a.length == 0 ? (s instanceof Player ? "menu" : "help") : a[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "menu" -> {
                if (s instanceof Player p) plugin.gui().open(p);
                else s.sendMessage(Util.err("The menu is only available in-game."));
            }
            case "list" -> {
                String prefix = a.length > 1 ? a[1] : "";
                for (String k : keys()) {
                    if (k.startsWith(prefix)) {
                        s.sendMessage(Component.text(k + " = ", NamedTextColor.GRAY)
                                .append(Component.text(String.valueOf(c.get(k)), NamedTextColor.WHITE)));
                    }
                }
            }
            case "get" -> {
                if (a.length < 2) { s.sendMessage(Util.err("Usage: /uhcconfig get <key>")); return true; }
                s.sendMessage(Util.msg(a[1] + " = " + c.get(a[1])));
            }
            case "set", "toggle" -> {
                if (a.length < 2) { s.sendMessage(Util.err("Usage: /uhcconfig " + sub + " <key>" + (sub.equals("set") ? " <value>" : ""))); return true; }
                String key = a[1];
                if (c.isConfigurationSection(key) || (!c.contains(key) && !key.startsWith("drops.mobs."))) {
                    s.sendMessage(Util.err("Unknown key. Use /uhcconfig list to see all keys."));
                    return true;
                }
                Object value;
                if (sub.equals("toggle")) {
                    if (!(c.get(key) instanceof Boolean b)) {
                        s.sendMessage(Util.err(key + " isn't a true/false setting."));
                        return true;
                    }
                    value = !b;
                } else {
                    if (a.length < 3) { s.sendMessage(Util.err("Usage: /uhcconfig set <key> <value>")); return true; }
                    String raw = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                    if (c.get(key) instanceof List) {
                        List<String> list = new ArrayList<>();
                        if (!raw.equalsIgnoreCase("none")) {
                            for (String part : raw.split(",")) if (!part.isBlank()) list.add(part.trim());
                        }
                        value = list;
                    } else {
                        value = Util.parse(raw);
                    }
                }
                c.set(key, value);
                plugin.saveConfig();
                s.sendMessage(Util.msg(key + " = " + value));
                afterChange(s, key);
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.recipes().reload();
                plugin.lobby().load();
                if (WorldgenPack.sync(plugin)) s.sendMessage(Util.msg("Structure settings changed - restart the server to apply them."));
                s.sendMessage(Util.msg("Config reloaded."));
            }
            default -> s.sendMessage(Util.msg("Usage: /uhcconfig list [prefix] | get <key> | set <key> <value> | toggle <key> | reload. "
                    + "Lists take comma-separated values ('none' clears)."));
        }
        return true;
    }

    private void afterChange(CommandSender s, String key) {
        if (key.startsWith("recipes.")) {
            plugin.recipes().reload();
        } else if (key.startsWith("lobby.")) {
            plugin.lobby().load();
        } else if (key.startsWith("worldgen.structures.")) {
            if (WorldgenPack.sync(plugin)) s.sendMessage(Util.msg("Restart the server for per-structure changes to apply."));
        } else {
            s.sendMessage(Util.msg("Applies to the next game that is created."));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("uhc.admin")) return List.of();
        if (a.length == 1) return SUBS.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2 && !a[0].equalsIgnoreCase("reload")) {
            return keys().stream().filter(k -> k.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("set")) return List.of("true", "false");
        return List.of();
    }
}
