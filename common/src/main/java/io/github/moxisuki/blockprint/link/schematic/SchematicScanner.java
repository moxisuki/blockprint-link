package io.github.moxisuki.blockprint.link.schematic;

import io.github.moxisuki.blockprint.core.Litematic;
import io.github.moxisuki.blockprint.core.LitematicReader;
import io.github.moxisuki.blockprint.core.LitematicRegion;
import io.github.moxisuki.blockprint.core.SchematicFormat;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans local Minecraft schematics directories and vanilla structure exports.
 */
public final class SchematicScanner {

    public static final String FORMAT_LITEMATIC = "litematic";
    public static final String FORMAT_SCHEMATIC = "schematic";

    public static final String SCHEMATICS_SUBDIR = "schematics";
    public static final String SOURCE_SCHEMATICS = "schematics";
    public static final String SOURCE_SAVES_PREFIX = "saves/";

    private static final int DESCRIPTION_MAX = 60;
    private static final int ERROR_MAX = 100;

    private SchematicScanner() {}

    /**
     * @param source   where the file was found — "schematics" or "saves/&lt;world&gt;"
     * @param filePath absolute path on disk, used by the download handler
     */
    public record Entry(
        String fileName, String format, Litematic data, String error,
        String source, String filePath
    ) {
        public static Entry ok(String fileName, String format, Litematic data,
                               String source, String filePath) {
            return new Entry(fileName, format, data, null, source, filePath);
        }
        public static Entry fail(String fileName, String format, String error,
                                 String source, String filePath) {
            return new Entry(fileName, format, null, error, source, filePath);
        }
        public boolean isOk() { return error == null; }
    }

    public static File schematicsDir(File gameDir) {
        return new File(gameDir, SCHEMATICS_SUBDIR);
    }

    /**
     * Scan all schematic sources: schematics/ directory and vanilla structure exports.
     */
    public static List<Entry> scanAll(File gameDir) {
        List<Entry> out = new ArrayList<>();
        out.addAll(scanSchematics(gameDir));
        out.addAll(scanStructures(gameDir));
        return out;
    }

    /**
     * Scan {@code <gameDir>/schematics/} for known schematic files.
     */
    public static List<Entry> scanSchematics(File gameDir) {
        File dir = schematicsDir(gameDir);
        return scanDir(dir, SOURCE_SCHEMATICS, dir);
    }

    /**
     * Scan saves/*&#47;generated/minecraft/structures/ for vanilla
     * structure exports (.nbt files). Walks up from gameDir to find
     * all possible saves/ locations (instance-specific + shared .minecraft).
     */
    public static List<Entry> scanStructures(File gameDir) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Instance-specific
        addFromSaves(new File(gameDir, "saves"), out, seen);
        // Walk up to find shared .minecraft/saves/
        File p = gameDir.getParentFile();
        while (p != null) {
            File candidate = new File(p, "saves");
            if (!seen.contains(candidate.getAbsolutePath())) {
                addFromSaves(candidate, out, seen);
            }
            if (new File(p, "versions").isDirectory()) break;
            p = p.getParentFile();
        }
        return out;
    }

    private static void addFromSaves(File savesDir, List<Entry> out, Set<String> seen) {
        String abs = savesDir.getAbsolutePath();
        if (!seen.add(abs)) return;
        if (!savesDir.isDirectory()) return;
        File[] worlds = savesDir.listFiles(File::isDirectory);
        if (worlds == null) return;
        for (File world : worlds) {
            File structDir = new File(world, "generated/minecraft/structures");
            if (structDir.isDirectory()) {
                String source = SOURCE_SAVES_PREFIX + world.getName();
                out.addAll(scanDir(structDir, source, structDir));
            }
        }
    }

    // addFromSaves with Set param above replaces this old overload.

    /** Resolve the on-disk file for an entry. */
    public static File resolveFile(Entry entry) {
        if (entry.filePath() != null) return new File(entry.filePath());
        return new File(entry.fileName());
    }

    private static List<Entry> scanDir(File dir, String source, File sourceRoot) {
        List<Entry> out = new ArrayList<>();
        if (!dir.isDirectory()) return out;

        Path dirPath = dir.toPath();
        try (Stream<Path> stream = Files.list(dirPath)) {
            List<Path> matching = stream
                .filter(Files::isRegularFile)
                .filter(SchematicScanner::isBlueprintFile)
                .sorted()
                .toList();

            for (Path p : matching) {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                String absPath = p.toAbsolutePath().toString();
                try {
                    Litematic data = LitematicReader.readLenient(p.toFile());
                    String format = formatLabel(data.getFormat(), lower);
                    out.add(Entry.ok(name, format, data, source, absPath));
                } catch (Throwable t) {
                    // readLenient should not throw on any common format,
                    // but guard against corrupt files just in case.
                    String fmt = fallbackFormat(lower);
                    String msg = t.getClass().getSimpleName() + ": "
                        + (t.getMessage() == null ? "unknown" : t.getMessage());
                    out.add(Entry.fail(name, fmt, truncate(msg, ERROR_MAX), source, absPath));
                }
            }
        } catch (IOException e) {
            // Files.list failed — return what we have.
        }
        return out;
    }

    private static String formatLabel(SchematicFormat fmt, String lower) {
        return switch (fmt) {
            case Litematica -> FORMAT_LITEMATIC;
            case Sponge -> FORMAT_SCHEMATIC;
            case Structure -> "structure";
            case BuildingHelper -> "buildhelper";
            case PartialNbt -> "partial";
            case Unknown -> fallbackFormat(lower);
        };
    }

    private static String fallbackFormat(String lower) {
        if (lower.endsWith("." + FORMAT_SCHEMATIC)) return FORMAT_SCHEMATIC;
        if (lower.endsWith(".nbt")) return "nbt";
        return FORMAT_LITEMATIC;
    }

    private static boolean isBlueprintFile(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith("." + FORMAT_LITEMATIC)
            || name.endsWith("." + FORMAT_SCHEMATIC)
            || name.endsWith(".nbt")
            || name.endsWith(".json")
            || !name.contains(".");
    }

    public static Component formatEntry(Entry e) {
        if (!e.isOk()) {
            return Component.literal("§c[error] §e" + e.fileName() + "§c: §f" + e.error() + "§r");
        }
        Litematic data = e.data();
        LitematicRegion region = data.getPrimaryRegion();

        StringBuilder sb = new StringBuilder();
        sb.append("§6[").append(e.format().toUpperCase(Locale.ROOT)).append("]§r ");
        sb.append("§e").append(e.fileName()).append("§r ");
        sb.append("§7- §f").append(displayName(data.getName())).append("§r ");

        if (region != null) {
            sb.append("§7| §a").append(region.getWidth()).append("x")
                .append(region.getHeight()).append("x").append(region.getDepth()).append("§r ");
        }

        sb.append("§7| §b").append(data.blockCount(false)).append(" blocks§r ");

        if (!data.getAuthor().isEmpty()) {
            sb.append("§7| by §f").append(data.getAuthor()).append("§r ");
        }

        String desc = data.getDescription();
        if (!desc.isEmpty()) {
            sb.append("§7| §f").append(truncate(desc, DESCRIPTION_MAX)).append("§r ");
        }

        sb.append("§7| MC §f")
            .append(data.getMinecraftDataVersion() == null ? "?" : data.getMinecraftDataVersion())
            .append("§r §7| fmt §f")
            .append(data.getVersion() == null ? "?" : data.getVersion())
            .append("§r §7| regions §f")
            .append(data.getRegions().size())
            .append("§r");

        return Component.literal(sb.toString());
    }

    private static String displayName(String name) {
        return (name == null || name.isEmpty()) ? "(unnamed)" : name;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }
}
