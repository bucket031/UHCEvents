package dev.events.uhc;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Vault;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Veinminer, auto-smelt, drop rates, one-mace mode. All of it only applies inside the game world. */
public final class ExtrasListener implements Listener {
    private final UHCPlugin plugin;
    private final Map<Material, ItemStack> furnace = new HashMap<>();

    ExtrasListener(UHCPlugin plugin) {
        this.plugin = plugin;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            if (it.next() instanceof FurnaceRecipe fr && fr.getInputChoice() instanceof RecipeChoice.MaterialChoice mc) {
                for (Material m : mc.getChoices()) furnace.putIfAbsent(m, fr.getResult());
            }
        }
    }

    // ------------------------------------------------------------------ veinminer + autosmelt

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block origin = e.getBlock();
        if (!plugin.game().isGameWorld(origin.getWorld()) || p.getGameMode() != GameMode.SURVIVAL) return;

        FileConfiguration c = plugin.getConfig();
        ItemStack tool = p.getInventory().getItemInMainHand();
        String toolName = tool.getType().name();
        Material type = origin.getType();
        boolean ore = isOre(type);
        boolean log = Tag.LOGS.isTagged(type);

        boolean vein = c.getBoolean("extras.veinminer.enabled", true)
                && ((ore && toolName.endsWith("_PICKAXE")) || (log && toolName.endsWith("_AXE")))
                && (!c.getBoolean("extras.veinminer.require-sneak", false) || p.isSneaking());
        boolean autosmelt = ore && c.getBoolean("extras.autosmelt", true);
        if (!vein && !autosmelt) return;

        e.setDropItems(false); // we drop everything ourselves
        List<Block> blocks = vein ? collect(origin, c.getInt("extras.veinminer.max-blocks", 64)) : List.of(origin);
        for (Block b : blocks) {
            List<ItemStack> drops = new ArrayList<>(b.getDrops(tool, p));
            if (autosmelt) smeltDrops(drops, b.getType());
            Location at = b.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack d : drops) b.getWorld().dropItemNaturally(at, d);
            if (b != origin) { // the origin block is removed + damaged by vanilla
                b.setType(Material.AIR);
                if (!damageTool(p)) break;
            }
        }
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    private static String group(Material m) {
        return m.name().replace("DEEPSLATE_", ""); // iron ore + deepslate iron ore are one vein
    }

    private List<Block> collect(Block start, int max) {
        String key = group(start.getType());
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && out.size() < max) {
            Block b = queue.poll();
            out.add(b);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (!seen.contains(n) && group(n.getType()).equals(key)) {
                            seen.add(n);
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return out;
    }

    private void smeltDrops(List<ItemStack> drops, Material blockType) {
        for (int i = 0; i < drops.size(); i++) {
            ItemStack d = drops.get(i);
            if (d.getType() == blockType) continue; // silk touch: leave the ore block alone
            ItemStack result = furnace.get(d.getType());
            if (result == null) continue;
            ItemStack smelted = result.clone();
            smelted.setAmount(Math.min(smelted.getMaxStackSize(), d.getAmount() * result.getAmount()));
            drops.set(i, smelted);
        }
    }

    /** Damages the held tool for an extra vein block. Returns false (stop veining) if the tool is nearly broken. */
    private boolean damageTool(Player p) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!(tool.getItemMeta() instanceof Damageable d) || d.isUnbreakable()) return true;
        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return true;
        if (d.getDamage() + 2 >= tool.getType().getMaxDurability()) return false; // never break the tool ourselves
        d.setDamage(d.getDamage() + 1);
        tool.setItemMeta(d);
        p.getInventory().setItemInMainHand(tool);
        return true;
    }

    // ------------------------------------------------------------------ apple rate on trees

    private boolean isAppleLeaf(Material m) {
        return m == Material.OAK_LEAVES || m == Material.DARK_OAK_LEAVES;
    }

    private boolean appleRoll() {
        return ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("drops.apple-chance", 0.005);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeafBreak(BlockDropItemEvent e) {
        Material t = e.getBlockState().getType();
        if (!isAppleLeaf(t) || !plugin.game().isGameWorld(e.getBlock().getWorld())) return;
        if (e.getItems().stream().anyMatch(i -> i.getItemStack().getType() == t)) return; // shears / silk touch
        e.getItems().removeIf(i -> i.getItemStack().getType() == Material.APPLE);
        if (appleRoll()) {
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5),
                    new ItemStack(Material.APPLE));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent e) {
        Block b = e.getBlock();
        if (!isAppleLeaf(b.getType()) || !plugin.game().isGameWorld(b.getWorld())) return;
        e.setCancelled(true);
        List<ItemStack> drops = new ArrayList<>(b.getDrops());
        b.setType(Material.AIR);
        drops.removeIf(d -> d.getType() == Material.APPLE);
        if (appleRoll()) drops.add(new ItemStack(Material.APPLE));
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack d : drops) b.getWorld().dropItemNaturally(at, d);
    }

    // ------------------------------------------------------------------ mob drops

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player || !plugin.game().isGameWorld(e.getEntity().getWorld())) return;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("drops.mobs." + e.getEntityType().name());
        if (s == null) return;
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack d = it.next();
            double mult = s.getDouble(d.getType().name(), 1.0);
            if (mult == 1.0) continue;
            double scaled = d.getAmount() * mult;
            int n = (int) scaled;
            if (ThreadLocalRandom.current().nextDouble() < scaled - n) n++;
            n = Math.min(n, d.getMaxStackSize());
            if (n <= 0) it.remove(); else d.setAmount(n);
        }
    }

    // ------------------------------------------------------------------ one-mace mode

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory().getResult();
        GameManager g = plugin.game();
        if (result != null && result.getType() == Material.MACE && g.oneMaceActive() && g.maceCrafted()) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        GameManager g = plugin.game();
        if (e.getRecipe().getResult().getType() != Material.MACE || !g.oneMaceActive()) return;
        if (g.maceCrafted()) {
            e.setCancelled(true);
            return;
        }
        g.markMaceCrafted(e.getWhoClicked().getName());
    }

    /** Ominous vaults: boost the heavy core chance (the key ingredient of the mace). */
    @EventHandler
    public void onVaultLoot(BlockDispenseLootEvent e) {
        GameManager g = plugin.game();
        if (!g.oneMaceActive() || !g.isGameWorld(e.getBlock().getWorld())) return;
        if (!(e.getBlock().getBlockData() instanceof Vault vault) || !vault.isOminous()) return;
        double chance = plugin.getConfig().getDouble("modes.one-mace-heavy-core-chance", 0.5);
        List<ItemStack> loot = new ArrayList<>(e.getDispensedLoot());
        if (loot.isEmpty() || ThreadLocalRandom.current().nextDouble() >= chance) return;
        if (loot.stream().noneMatch(i -> i.getType() == Material.HEAVY_CORE)) {
            loot.set(0, new ItemStack(Material.HEAVY_CORE));
        }
        e.setDispensedLoot(loot);
    }
}
