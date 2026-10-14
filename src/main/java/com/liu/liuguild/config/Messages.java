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
        File file = new File(root, "messages.yml");
        // 先铺内置默认，再用用户文件覆盖：新增键在旧配置里也能正常显示，不会回退成键名。
        try (java.io.InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                collect(defaults, map);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取内置 messages.yml 失败: " + e.getMessage());
        }
        if (file.exists()) {
            try {
                collect(YamlConfiguration.loadConfiguration(file), map);
            } catch (RuntimeException e) {
                // 用户 messages.yml 损坏时不要中断启动，保留内置默认。
                plugin.getLogger().warning("messages.yml 解析失败，已回退内置默认: " + e.getMessage());
            }
        }
        this.table = map;
    }

    /** 把指定语言的键值写入 map（用户文件后写入，自然覆盖内置默认）。 */
    private void collect(YamlConfiguration yml, Map<String, String> map) {
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
            if (v != null) {
                map.put(key, String.valueOf(v));
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

    public Component component(String key, Object... kv) {
        return Text.fmt(raw(key), kv);
    }

    /** 带前缀（prefix）的组件。 */
    public Component prefixed(String key, Object... kv) {
        return Text.fmt(raw("prefix") + raw(key), kv);
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
