package com.liu.liuguild.config;

import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** messages.yml 加载与渲染（支持多语言段）。 */
public final class Messages {

    private final Plugin plugin;
    private final File root;
    private volatile String language = "zh_CN";
    private volatile Map<String, String> table = new HashMap<>();
    /** 列表型消息（GUI lore 等）；在 collect 时单独收集。 */
    private volatile Map<String, java.util.List<String>> listTable = new HashMap<>();

    public Messages(Plugin plugin, File root) {
        this.plugin = plugin;
        this.root = root;
    }

    /** 若共享目录里没有 messages.yml 则复制内置默认。 */
    public void copyDefault() {
        File target = new File(root, "messages.yml");
        if (target.exists()) {
            return;
        }
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in == null) {
                return;
            }
            root.mkdirs();
            Files.copy(in, target.toPath());
        } catch (Exception e) {
            plugin.getLogger().warning("复制 messages.yml 失败: " + e.getMessage());
        }
    }

    public void reload(String language) {
        this.language = language == null || language.isBlank() ? "zh_CN" : language;
        Map<String, String> map = new HashMap<>();
        Map<String, java.util.List<String>> lists = new HashMap<>();
        File file = new File(root, "messages.yml");
        // 先铺内置默认，再用用户文件覆盖：新增键在旧配置里也能正常显示，不会回退成键名。
        try (java.io.InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                collect(defaults, map, lists);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取内置 messages.yml 失败: " + e.getMessage());
        }
        if (file.exists()) {
            try {
                collect(YamlConfiguration.loadConfiguration(file), map, lists);
            } catch (RuntimeException e) {
                // 用户 messages.yml 损坏时不要中断启动，保留内置默认。
                plugin.getLogger().warning("messages.yml 解析失败，已回退内置默认: " + e.getMessage());
            }
        }
        this.table = map;
        this.listTable = lists;
    }

    /** 把指定语言的键值写入 map（用户文件后写入，自然覆盖内置默认）。 */
    private void collect(YamlConfiguration yml, Map<String, String> map, Map<String, java.util.List<String>> lists) {
        ConfigurationSection sec = yml.getConfigurationSection(this.language);
        if (sec == null) {
            sec = yml.getConfigurationSection("zh_CN");
        }
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(true)) {
            if (sec.isConfigurationSection(key)) {
                continue;
            }
            Object v = sec.get(key);
            if (v == null) {
                continue;
            }
            if (v instanceof java.util.List<?> rawList) {
                java.util.List<String> lines = new java.util.ArrayList<>(rawList.size());
                for (Object o : rawList) {
                    lines.add(String.valueOf(o));
                }
                lists.put(key, lines);
                map.put(key, String.join("\n", lines));
            } else {
                map.put(key, String.valueOf(v));
                lists.remove(key);
            }
        }
    }

    public String language() {
        return language;
    }

    public String raw(String key) {
        String v = table.get(key);
        return v == null ? key : v;
    }

    /** 列表型消息（GUI lore）：没有对应列表时退化为单行 {@link #raw(String)}。 */
    public java.util.List<String> rawList(String key) {
        java.util.List<String> v = listTable.get(key);
        if (v != null) {
            return v;
        }
        String single = table.get(key);
        return single == null ? java.util.List.of(key) : java.util.List.of(single);
    }

    public Component component(String key, Object... kv) {
        return Text.fmt(raw(key), kv);
    }

    /** 带前缀（prefix）的组件。 */
    public Component prefixed(String key, Object... kv) {
        return Text.fmt(raw("prefix") + raw(key), kv);
    }

    /** 带前缀的富文本组件：占位符值允许携带颜色格式。 */
    public Component prefixedRich(String key, Object... kv) {
        return Text.fmtRich(raw("prefix") + raw(key), kv);
    }

    public void send(CommandSender to, String key, Object... kv) {
        if (to == null) {
            return;
        }
        to.sendMessage(prefixed(key, kv));
    }

    public void sendRaw(CommandSender to, String key, Object... kv) {
        if (to == null) {
            return;
        }
        to.sendMessage(component(key, kv));
    }

    /** 带前缀的富文本发送，用于头衔等由管理员配置的格式文本。 */
    public void sendRich(CommandSender to, String key, Object... kv) {
        if (to == null) {
            return;
        }
        to.sendMessage(prefixedRich(key, kv));
    }

    /** 富文本版本：占位符值允许携带颜色格式（用于玩家自定义公告/简介）。 */
    public Component componentRich(String key, Object... kv) {
        return Text.fmtRich(raw(key), kv);
    }

    public void sendRawRich(CommandSender to, String key, Object... kv) {
        if (to == null) {
            return;
        }
        to.sendMessage(componentRich(key, kv));
    }
}
