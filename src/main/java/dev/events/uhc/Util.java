package dev.events.uhc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;

final class Util {
    private Util() {}

    static Component msg(String text) {
        return Component.text("[UHC] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.YELLOW));
    }

    static Component err(String text) {
        return Component.text("[UHC] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.RED));
    }

    static String clock(int seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    /** Parses a command argument into boolean / int / double / string. */
    static Object parse(String s) {
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return s;
    }
}
