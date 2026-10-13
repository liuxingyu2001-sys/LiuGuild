package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工会战统一装备模块（kit.yml）。
 * <p>支持多套装备：{@code kits.<key>}，不同工会战模式可通过 {@code war.modes.<mode>.kit} 选用不同套装。
 * 未定义 {@code kits} 时，兼容旧的 {@code kit:} 单套装写法。</p>
 */
public final class KitSettings {

    public boolean enabled = true;
    public String defaultKit = "default";
    public Map<String, KitProfile> kits = new LinkedHashMap<>();

    public KitProfile profile(String key) {
        if (key != null && kits.containsKey(key.toLowerCase(Locale.ROOT))) {
            return kits.get(key.toLowerCase(Locale.ROOT));
        }
        KitProfile def = kits.get(defaultKit);
        if (def != null) {
            return def;
        }
        if (!kits.isEmpty()) {
            return kits.values().iterator().next();
        }
        return new KitProfile("default");
    }

    public static KitSettings parse(FileConfiguration c) {
        KitSettings s = new KitSettings();
        s.enabled = c.getBoolean("enabled", c.getBoolean("kit.enabled", true));
        s.defaultKit = c.getString("default-kit", s.defaultKit).toLowerCase(Locale.ROOT);

        ConfigurationSection kits = c.getConfigurationSection("kits");
        if (kits != null) {
            for (String key : kits.getKeys(false)) {
                s.kits.put(key.toLowerCase(Locale.ROOT),
                        KitProfile.parse(key.toLowerCase(Locale.ROOT), kits.getConfigurationSection(key)));
            }
        }
        if (s.kits.isEmpty()) {
            // 兼容旧版：根节点 kit:
            KitProfile legacy = KitProfile.parse("default", c.getConfigurationSection("kit"));
            s.kits.put("default", legacy);
            s.defaultKit = "default";
        }
        if (!s.kits.containsKey(s.defaultKit)) {
            s.defaultKit = s.kits.keySet().iterator().next();
        }
        return s;
    }

    /** 单套统一装备。 */
    public static final class KitProfile {
        public String key;
        public boolean enabled = true;
        public boolean clearInventory = true;
        public Map<String, String> armor = new LinkedHashMap<>();
        public List<String> items = new ArrayList<>();
        public List<String> effects = new ArrayList<>();
        /**
         * 直接保存的玩家装备快照（Base64，布局：36 背包 + 4 护甲 + 1 副手）。
         * 由 {@code /guildadmin kit save <key>} 现场采集，非空时优先于 armor/items。
         */
        public String snapshot = "";

        public KitProfile(String key) {
            this.key = key;
        }

        public boolean hasSnapshot() {
            return snapshot != null && !snapshot.isBlank();
        }

        public static KitProfile parse(String key, ConfigurationSection sec) {
            KitProfile k = new KitProfile(key);
            if (sec == null) {
                return k;
            }
            k.enabled = sec.getBoolean("enabled", k.enabled);
            k.clearInventory = sec.getBoolean("clear-inventory", k.clearInventory);
            k.items = new ArrayList<>(sec.getStringList("items"));
            k.effects = new ArrayList<>(sec.getStringList("effects"));
            k.snapshot = sec.getString("snapshot", k.snapshot);
            ConfigurationSection armor = sec.getConfigurationSection("armor");
            if (armor != null) {
                for (String a : armor.getKeys(false)) {
                    k.armor.put(a.toLowerCase(Locale.ROOT), String.valueOf(armor.get(a)));
                }
            }
            return k;
        }
    }
}
