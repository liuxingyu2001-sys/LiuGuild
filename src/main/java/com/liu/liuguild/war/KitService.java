package com.liu.liuguild.war;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.KitSettings;
import com.liu.liuguild.config.KitSettings.KitProfile;
import com.liu.liuguild.config.WarMode;
import com.liu.liuguild.util.ItemUtil;
import com.liu.liuguild.util.NumberUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

/**
 * 工会战统一装备：所有参赛者进入竞技场时发放完全相同的装备与增益，
 * 保证公平（这是「专门承办工会战的服务器」的核心机制）。
 */
public final class KitService {

    private final LiuGuild plugin;
    /** 预解析缓存：套装 key -> 护甲/物品。配置重载时重建。 */
    private final java.util.Map<String, ItemStack[]> armorCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, ItemStack[]> itemsCache = new java.util.concurrent.ConcurrentHashMap<>();

    public KitService(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /**
     * 配置加载/重载后调用：预解析各套装的护甲与物品、预解码快照，
     * 让入场发装备时不再重复解析材质 / Base64 解码。
     */
    public void preload() {
        armorCache.clear();
        itemsCache.clear();
        for (KitProfile c : kit().kits.values()) {
            if (!c.armor.isEmpty()) {
                ItemStack[] armor = new ItemStack[4];
                armor[3] = resolve(c.armor.get("helmet"), Material.DIAMOND_HELMET);
                armor[2] = resolve(c.armor.get("chestplate"), Material.DIAMOND_CHESTPLATE);
                armor[1] = resolve(c.armor.get("leggings"), Material.DIAMOND_LEGGINGS);
                armor[0] = resolve(c.armor.get("boots"), Material.DIAMOND_BOOTS);
                armorCache.put(c.key, armor);
            }
            ItemStack[] items = new ItemStack[36];
            int slot = 0;
            for (String spec : c.items) {
                if (spec == null || spec.isBlank()) {
                    continue;
                }
                ItemStack item = parseItem(spec);
                if (item == null) {
                    continue;
                }
                items[slot++] = item;
                if (slot >= 36) {
                    break;
                }
            }
            itemsCache.put(c.key, items);
            c.decodedSnapshot(); // 预解码快照
        }
    }

    private static ItemStack[] cloneAll(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] == null ? null : in[i].clone();
        }
        return out;
    }

    public KitSettings kit() {
        return plugin.settings().kit;
    }

    /** 已预解析缓存的套装数量。 */
    public int preloadedKits() {
        return itemsCache.size();
    }

    /** 解析某模式的装备套装。 */
    public KitProfile profile(WarMode mode) {
        return kit().profile(mode == null ? null : mode.kit);
    }

    /** 参赛前的状态快照，赛后完整还原（避免统一装备误删玩家原有物品）。 */
    public record Snapshot(ItemStack[] contents, ItemStack[] armor, ItemStack offhand,
                           Location location, GameMode gameMode, double health, int food,
                           int level, float exp, java.util.List<PotionEffect> effects) {
    }

    /** 持久化用的托管记录（数据库恢复用）。 */
    public record PersistedEscrow(UUID playerUuid, String serverId, String warId,
                                  UUID sparrowSnapshotId, String itemsData,
                                  String worldName, double x, double y, double z, float yaw, float pitch,
                                  String gameMode, double health, int food, int level, float exp,
                                  long createdAt) {
        /**
         * 解码为可还原的快照；物品数据不完整时返回 {@code null}。
         *
         * <p>绝不能把「解码失败 → 全 null 背包」当成合法快照：那会在恢复时直接清空玩家背包。
         * 完整快照必定是 41 槽（36 背包 + 4 护甲 + 1 副手），少于 41 一律视为不可用。</p>
         */
        public Snapshot toSnapshot() {
            ItemStack[] decoded = ItemUtil.decode(itemsData);
            if (decoded.length < 41) {
                return null;
            }
            ItemStack[] contents = new ItemStack[36];
            ItemStack[] armor = new ItemStack[4];
            ItemStack offhand = null;
            for (int i = 0; i < 36; i++) {
                contents[i] = decoded[i];
            }
            for (int i = 0; i < 4; i++) {
                armor[i] = decoded[36 + i];
            }
            offhand = decoded[40];
            Location loc = null;
            if (worldName != null && !worldName.isBlank()) {
                org.bukkit.World w = com.liu.liuguild.util.WorldResolver.resolve(worldName);
                if (w != null) {
                    loc = new Location(w, x, y, z, yaw, pitch);
                }
            }
            GameMode gm = GameMode.SURVIVAL;
            if (gameMode != null) {
                try {
                    gm = GameMode.valueOf(gameMode);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return new Snapshot(contents, armor, offhand, loc, gm, health, food, level, exp, java.util.List.of());
        }
    }

    public Snapshot snapshot(Player player) {
        ItemStack[] contents = deepCopy(player.getInventory().getStorageContents());
        ItemStack[] armor = deepCopy(player.getInventory().getArmorContents());
        ItemStack offhand = player.getInventory().getItemInOffHand().clone();
        return new Snapshot(contents, armor, offhand, player.getLocation().clone(),
                player.getGameMode(), player.getHealth(), player.getFoodLevel(),
                player.getLevel(), player.getExp(), new java.util.ArrayList<>(player.getActivePotionEffects()));
    }

    /** 把 Snapshot 中的 36 格背包 + 4 格护甲 + 1 格副手编码成 41 槽位 Base64。 */
    public String encodeSnapshotItems(Snapshot snap) {
        if (snap == null) {
            return "";
        }
        ItemStack[] data = new ItemStack[41];
        ItemStack[] c = snap.contents();
        if (c != null) {
            for (int i = 0; i < 36 && i < c.length; i++) {
                data[i] = c[i];
            }
        }
        ItemStack[] a = snap.armor();
        if (a != null) {
            for (int i = 0; i < 4 && i < a.length; i++) {
                data[36 + i] = a[i];
            }
        }
        data[40] = snap.offhand();
        return ItemUtil.encode(data);
    }

    /** 还原状态（主线程）。若 skipTeleport=true，则不覆盖当前坐标（交由 finishTeleport 处理）。 */
    public void restore(Player player, Snapshot snap) {
        restore(player, snap, false);
    }

    /** 还原状态（主线程）。 */
    public void restore(Player player, Snapshot snap, boolean skipTeleport) {
        if (player == null || snap == null) {
            return;
        }
        player.getInventory().setStorageContents(deepCopy(snap.contents()));
        player.getInventory().setArmorContents(deepCopy(snap.armor()));
        player.getInventory().setItemInOffHand(snap.offhand() == null ? null : snap.offhand().clone());
        if (snap.gameMode() != null) {
            player.setGameMode(snap.gameMode());
        }
        player.setFoodLevel(snap.food());
        player.setLevel(snap.level());
        player.setExp(snap.exp());
        for (PotionEffect e : new java.util.ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(e.getType());
        }
        if (snap.effects() != null) {
            for (PotionEffect e : snap.effects()) {
                player.addPotionEffect(e);
            }
        }
        if (!skipTeleport && snap.location() != null && snap.location().getWorld() != null) {
            player.teleport(snap.location());
        }
        com.liu.liuguild.util.ApiCompat.setHealthSafely(player, snap.health());
    }

    private static ItemStack[] deepCopy(ItemStack[] in) {
        if (in == null) {
            return new ItemStack[0];
        }
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] == null ? null : in[i].clone();
        }
        return out;
    }

    /**
     * 采集玩家当前「身上穿的装备 + 背包 + 副手」为 Base64 快照。
     * 布局：0-35 背包、36-39 护甲（boots→helmet）、40 副手。
     */
    public String capture(Player player) {
        ItemStack[] data = new ItemStack[41];
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < 36 && i < contents.length; i++) {
            data[i] = contents[i];
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < 4 && i < armor.length; i++) {
            data[36 + i] = armor[i];
        }
        data[40] = player.getInventory().getItemInOffHand();
        return ItemUtil.encode(data);
    }

    /** 发放某工会战模式的统一装备。主线程调用。 */
    public void apply(Player player, WarMode mode) {
        if (player == null || mode == null) {
            return;
        }
        KitProfile c = profile(mode);
        if (!kit().enabled || !mode.fairEquipment || c == null || !c.enabled) {
            // 非公平模式：仅恢复满状态，不动装备
            fullState(player, mode);
            return;
        }
        // 公平模式必须清空背包：否则未填满的槽位会残留玩家自带物品，形成不公平。
        if (c.clearInventory || mode.fairEquipment) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setItemInOffHand(null);
        }
        if (c.hasSnapshot()) {
            applySnapshot(player, c, mode);
            return;
        }
        // 护甲：优先用预解析缓存，避免现场解析
        if (!c.armor.isEmpty()) {
            ItemStack[] cachedArmor = armorCache.get(c.key);
            if (cachedArmor != null) {
                player.getInventory().setArmorContents(cloneAll(cachedArmor));
            } else {
                ItemStack[] armor = new ItemStack[4];
                armor[3] = resolve(c.armor.get("helmet"), Material.DIAMOND_HELMET);
                armor[2] = resolve(c.armor.get("chestplate"), Material.DIAMOND_CHESTPLATE);
                armor[1] = resolve(c.armor.get("leggings"), Material.DIAMOND_LEGGINGS);
                armor[0] = resolve(c.armor.get("boots"), Material.DIAMOND_BOOTS);
                player.getInventory().setArmorContents(armor);
            }
        }
        // 物品栏：优先用预解析缓存
        ItemStack[] cachedItems = itemsCache.get(c.key);
        if (cachedItems != null) {
            for (int i = 0; i < 36 && i < cachedItems.length; i++) {
                if (cachedItems[i] != null) {
                    player.getInventory().setItem(i, cachedItems[i].clone());
                }
            }
        } else {
            int slot = 0;
            for (String spec : c.items) {
                if (spec == null || spec.isBlank()) {
                    continue;
                }
                ItemStack item = parseItem(spec);
                if (item == null) {
                    continue;
                }
                player.getInventory().setItem(slot, item);
                slot++;
                if (slot >= 36) {
                    break;
                }
            }
        }
        fullState(player, mode);
    }

    /** 应用直接保存的装备快照。 */
    private void applySnapshot(Player player, KitProfile c, WarMode mode) {
        ItemStack[] data = c.decodedSnapshot();
        if (data.length < 41) {
            plugin.getLogger().warning("装备套装 " + c.key + " 快照损坏，已忽略。");
            fullState(player, mode);
            return;
        }
        ItemStack[] contents = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 36; i++) {
            contents[i] = data[i] == null ? null : data[i].clone();
        }
        for (int i = 0; i < 4; i++) {
            armor[i] = data[36 + i] == null ? null : data[36 + i].clone();
        }
        player.getInventory().setStorageContents(contents);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(data[40] == null ? null : data[40].clone());
        fullState(player, mode);
    }

    /** 药水效果 + 回满状态。 */
    private void fullState(Player player, WarMode mode) {
        KitProfile c = profile(mode);
        int duration = (mode.durationSeconds + mode.prepareSeconds + 60) * 20;
        if (c != null) {
            for (String eff : c.effects) {
                if (eff == null || eff.isBlank()) {
                    continue;
                }
                String[] parts = eff.split(":");
                PotionEffectType type = com.liu.liuguild.util.ApiCompat.potionEffect(parts[0]);
                if (type == null) {
                    org.bukkit.Bukkit.getLogger().warning("[LiuGuild] 未知的药水效果名称：" + parts[0]
                            + "（已跳过，请检查 kit.yml）");
                    continue;
                }
                int amp = parts.length > 1 ? NumberUtil.toInt(parts[1], 0) : 0;
                player.addPotionEffect(new PotionEffect(type, duration, Math.max(0, amp), true, false));
            }
        }
        com.liu.liuguild.util.ApiCompat.setHealthSafely(player, com.liu.liuguild.util.ApiCompat.maxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
    }

    private ItemStack resolve(String spec, Material fallback) {
        ItemStack ce = plugin.craftEngine() == null ? null : plugin.craftEngine().resolve(spec, fallback);
        return ce == null ? new ItemStack(fallback) : ce;
    }

    /** 解析 "SPEC:amount"，SPEC 可为 CE:namespace:id 或 Material。 */
    private ItemStack parseItem(String spec) {
        String key = spec.trim();
        int amount = 1;
        int idx = key.lastIndexOf(':');
        // 仅当冒号后是纯数字时才当作数量（避免误切 CE:namespace:id）
        if (idx > 0 && idx + 1 < key.length()) {
            String tail = key.substring(idx + 1);
            if (tail.chars().allMatch(Character::isDigit)) {
                amount = Math.max(1, parseInt(tail, 1));
                key = key.substring(0, idx);
            }
        }
        ItemStack item;
        if (key.regionMatches(true, 0, "CE:", 0, 3)) {
            item = plugin.craftEngine() == null ? null : plugin.craftEngine().byId(key.substring(3));
            if (item == null) {
                return null;
            }
        } else {
            Material mat = Material.matchMaterial(key.toUpperCase(java.util.Locale.ROOT));
            if (mat == null) {
                return null;
            }
            item = new ItemStack(mat);
        }
        item.setAmount(amount);
        return item;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
