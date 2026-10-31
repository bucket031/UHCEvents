package dev.events.uhc;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class RecipeManager {
    private final UHCPlugin plugin;
    private final List<NamespacedKey> keys = new ArrayList<>();

    RecipeManager(UHCPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        for (NamespacedKey k : keys) Bukkit.removeRecipe(k);
        keys.clear();

        Set<Material> removed = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("recipes.remove-vanilla")) {
            Material m = Material.matchMaterial(s);
            if (m != null) removed.add(m);
        }
        if (!removed.isEmpty()) {
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                if (removed.contains(it.next().getResult().getType())) it.remove();
            }
        }

        ConfigurationSection custom = plugin.getConfig().getConfigurationSection("recipes.custom");
        if (custom != null) {
            for (String id : custom.getKeys(false)) {
                ConfigurationSection sec = custom.getConfigurationSection(id);
                if (sec == null || !sec.getBoolean("enabled", true)) continue;
                Material result = Material.matchMaterial(sec.getString("result", ""));
                if (result == null) {
                    plugin.getLogger().warning("Recipe '" + id + "' has an invalid result.");
                    continue;
                }
                NamespacedKey key = new NamespacedKey(plugin, "uhc_" + id.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
                ShapelessRecipe recipe = new ShapelessRecipe(key, new ItemStack(result, Math.max(1, sec.getInt("amount", 1))));
                int count = 0;
                for (String ing : sec.getStringList("ingredients")) {
                    Material m = Material.matchMaterial(ing);
                    if (m == null || count >= 9) continue;
                    recipe.addIngredient(m);
                    count++;
                }
                if (count == 0) {
                    plugin.getLogger().warning("Recipe '" + id + "' has no valid ingredients.");
                    continue;
                }
                Bukkit.removeRecipe(key);
                Bukkit.addRecipe(recipe);
                keys.add(key);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) discover(p);
    }

    void discover(Player p) {
        if (!keys.isEmpty()) p.discoverRecipes(keys);
    }
}
