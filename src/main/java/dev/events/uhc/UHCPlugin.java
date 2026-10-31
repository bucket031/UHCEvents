package dev.events.uhc;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class UHCPlugin extends JavaPlugin {
    private LobbyManager lobby;
    private GameManager game;
    private RecipeManager recipes;
    private ConfigGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        WorldgenPack.sync(this);

        lobby = new LobbyManager(this);
        lobby.load();
        game = new GameManager(this);
        recipes = new RecipeManager(this);
        recipes.reload();
        gui = new ConfigGui(this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ExtrasListener(this), this);

        UHCCommand uhc = new UHCCommand(this);
        bind("uhc", uhc);
        bind("lobby", uhc);
        bind("uhcconfig", new ConfigCommand(this));

        Bukkit.getScheduler().runTaskTimer(this, lobby::tick, 100L, 100L);
    }

    @Override
    public void onDisable() {
        if (game != null) game.shutdown();
    }

    private void bind(String name, TabExecutor executor) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        c.setExecutor(executor);
        c.setTabCompleter(executor);
    }

    public LobbyManager lobby() { return lobby; }
    public GameManager game() { return game; }
    public RecipeManager recipes() { return recipes; }
    public ConfigGui gui() { return gui; }
}
