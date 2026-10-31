package dev.events.uhc;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-structure world generation toggles.
 * Bukkit can't switch individual structures off at runtime, so we write a tiny datapack into the main world's
 * datapacks folder that empties the "has_structure/<x>" biome tags. Datapacks load at startup => restart needed.
 */
final class WorldgenPack {
    private static final Map<String, List<String>> STRUCTURES = new LinkedHashMap<>();

    static {
        STRUCTURES.put("stronghold", List.of("stronghold"));
        STRUCTURES.put("trial_chambers", List.of("trial_chambers"));
        STRUCTURES.put("woodland_mansion", List.of("woodland_mansion"));
        STRUCTURES.put("ancient_city", List.of("ancient_city"));
        STRUCTURES.put("village", List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga"));
        STRUCTURES.put("pillager_outpost", List.of("pillager_outpost"));
        STRUCTURES.put("ocean_monument", List.of("ocean_monument"));
        STRUCTURES.put("mineshaft", List.of("mineshaft", "mineshaft_mesa"));
        STRUCTURES.put("desert_pyramid", List.of("desert_pyramid"));
        STRUCTURES.put("jungle_temple", List.of("jungle_temple"));
        STRUCTURES.put("igloo", List.of("igloo"));
        STRUCTURES.put("swamp_hut", List.of("swamp_hut"));
        STRUCTURES.put("shipwreck", List.of("shipwreck", "shipwreck_beached"));
        STRUCTURES.put("buried_treasure", List.of("buried_treasure"));
        STRUCTURES.put("ruined_portal", List.of("ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_mountain",
                "ruined_portal_nether", "ruined_portal_ocean", "ruined_portal_standard", "ruined_portal_swamp"));
        STRUCTURES.put("ocean_ruin", List.of("ocean_ruin_cold", "ocean_ruin_warm"));
        STRUCTURES.put("trail_ruins", List.of("trail_ruins"));
    }

    private WorldgenPack() {}

    static Set<String> names() {
        return STRUCTURES.keySet();
    }

    /** @return true if the datapack changed (server restart required). */
    static boolean sync(UHCPlugin plugin) {
        File packDir = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "datapacks/uhc_worldgen");

        Map<String, String> files = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : STRUCTURES.entrySet()) {
            if (plugin.getConfig().getBoolean("worldgen.structures." + e.getKey(), true)) continue;
            for (String tag : e.getValue()) {
                files.put("data/minecraft/tags/worldgen/biome/has_structure/" + tag + ".json",
                        "{\"replace\":true,\"values\":[]}");
            }
        }
        String signature = String.join(",", files.keySet());

        try {
            File sigFile = new File(packDir, ".signature");
            String existing = sigFile.exists() ? Files.readString(sigFile.toPath()) : "";
            if (files.isEmpty() && !packDir.exists()) return false;
            if (signature.equals(existing)) return false;

            Util.deleteRecursive(packDir);
            if (!files.isEmpty()) {
                packDir.mkdirs();
                Files.writeString(new File(packDir, "pack.mcmeta").toPath(),
                        "{\"pack\":{\"description\":\"UHCEvents worldgen overrides\",\"pack_format\":48,"
                                + "\"supported_formats\":{\"min_inclusive\":48,\"max_inclusive\":999},"
                                + "\"min_format\":48,\"max_format\":999}}");
                for (Map.Entry<String, String> f : files.entrySet()) {
                    File out = new File(packDir, f.getKey());
                    out.getParentFile().mkdirs();
                    Files.writeString(out.toPath(), f.getValue());
                }
                Files.writeString(sigFile.toPath(), signature);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not update the worldgen datapack: " + ex.getMessage());
            return false;
        }
        plugin.getLogger().warning("Structure settings changed - restart the server for them to take effect.");
        return true;
    }
}
