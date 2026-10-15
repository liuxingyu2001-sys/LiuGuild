package com.liu.liuguild.util;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 世界识别工具。
 *
 * <p>Paper 1.21.11+ / Leaf 26.x 把世界存储改到了
 * {@code <容器>/<主世界名>/dimensions/<命名空间>/<世界>}（例如
 * {@code zy/dimensions/minecraft/jjc}），不再是旧的 {@code <容器>/<世界>}。
 * 本工具同时兼容两种布局，并支持用命名空间键（{@code minecraft:jjc}）或纯名（{@code jjc}）识别。</p>
 */
public final class WorldResolver {

    private WorldResolver() {
    }

    /** 解析已加载的世界；匹配 Bukkit 名、命名空间键（{@code minecraft:jjc}）与目录名。 */
    public static World resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String name = configured.trim();
        World w = Bukkit.getWorld(name);
        if (w != null) {
            return w;
        }
        if (!name.contains(":")) {
            w = Bukkit.getWorld("minecraft:" + name);
            if (w != null) {
                return w;
            }
        }
        String norm = normalize(keyName(name));
        if (norm.isEmpty()) {
            return null;
        }
        for (World world : Bukkit.getWorlds()) {
            if (normalize(world.getName()).equals(norm)) {
                return world;
            }
            try {
                if (world.getKey() != null
                        && (normalize(world.getKey().getKey()).equals(norm)
                        || normalize(world.getKey().toString()).equals(norm))) {
                    return world;
                }
            } catch (Throwable ignored) {
                // 老版本没有 getKey，忽略
            }
        }
        return null;
    }

    /** 世界在磁盘上的目录；找不到返回 null。兼容新旧两种布局。 */
    public static File folder(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return findFolder(Bukkit.getWorldContainer(), configured.trim());
    }

    /** 纯函数实现，便于单测：在给定容器下查找世界目录。 */
    public static File findFolder(File container, String name) {
        if (container == null || name == null || name.isBlank()) {
            return null;
        }
        String clean = keyName(name.trim());
        // 旧布局：<container>/<world>
        File legacy = new File(container, clean);
        if (isWorldDir(legacy)) {
            return legacy;
        }
        // 新布局：<container>/<root>/dimensions/<namespace>/<world>
        File[] roots = container.listFiles(File::isDirectory);
        if (roots != null) {
            for (File root : roots) {
                File dims = new File(root, "dimensions");
                if (!dims.isDirectory()) {
                    continue;
                }
                File[] namespaces = dims.listFiles(File::isDirectory);
                if (namespaces == null) {
                    continue;
                }
                for (File ns : namespaces) {
                    File candidate = new File(ns, clean);
                    if (isWorldDir(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** 已加载或磁盘上存在该世界。 */
    public static boolean exists(String configured) {
        return resolve(configured) != null || folder(configured) != null;
    }

    /** 已加载 + 磁盘识别到的世界名（去重，用可读名）。 */
    public static List<String> knownNames() {
        Set<String> out = new LinkedHashSet<>();
        for (World w : Bukkit.getWorlds()) {
            out.add(w.getName());
        }
        File container = Bukkit.getWorldContainer();
        if (container != null) {
            File[] roots = container.listFiles(File::isDirectory);
            if (roots != null) {
                for (File root : roots) {
                    File dims = new File(root, "dimensions");
                    if (!dims.isDirectory()) {
                        continue;
                    }
                    File[] namespaces = dims.listFiles(File::isDirectory);
                    if (namespaces == null) {
                        continue;
                    }
                    for (File ns : namespaces) {
                        File[] worlds = ns.listFiles(File::isDirectory);
                        if (worlds == null) {
                            continue;
                        }
                        for (File world : worlds) {
                            if (isWorldDir(world)) {
                                out.add(world.getName());
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isWorldDir(File dir) {
        return dir != null && dir.isDirectory()
                && (new File(dir, "level.dat").isFile()
                || new File(dir, "region").isDirectory()
                || new File(dir, "entities").isDirectory());
    }

    /** 去掉命名空间前缀（{@code minecraft:jjc} → {@code jjc}）。 */
    public static String keyName(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
