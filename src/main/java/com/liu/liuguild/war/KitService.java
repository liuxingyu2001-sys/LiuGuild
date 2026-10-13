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

/**
 * 工会战统一装备：所有参赛者进入竞技场时发放完全相同的装备与增益，
 * 保证公平（这是「专门承办工会战的服务器」的核心机制）。
 */
public final class KitService {

    private final LiuGuild plugin;

    public KitService(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public KitSettings kit() {
        return plugin.settings().kit;
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

    public Snapshot snapshot(Player player) {
        ItemStack[] contents = deepCopy(player.getInventory().getContents());
        ItemStack[] armor = deepCopy(player.getInventory().getArmorContents());
        ItemStack offhand = player.getInventory().getItemInOffHand().clone();
        return new Snapshot(contents, armor, offhand, player.getLocation().clone(),
                player.getGameMode(), player.getHealth(), player.getFoodLevel(),
                player.getLevel(), player.getExp(), new java.util.ArrayList<>(player.getActivePotionEffects()));
    }

    /** 还原状态（主线程）。 */
    public void restore(Player player, Snapshot snap) {
        if (player == null || snap == null) {
            return;
        }
        player.getInventory().setContents(deepCopy(snap.contents()));
        player.getInventory().setArmorContents(deepCopy(snap.armor()));
        player.getInventory().setItemInOffHand(snap.offhand() == null ? null : snap.offhand().clone());
        player.setGameMode(snap.gameMode());
        player.setFoodLevel(snap.food());
        player.setLevel(snap.level());
        player.setExp(snap.exp());
        for (PotionEffect e : new java.util.ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(e.getType());
        }
        for (PotionEffect e : snap.effects()) {
            player.addPotionEffect(e);
        }
        if (snap.location() != null && snap.location().getWorld() != null) {
            player.teleport(snap.location());
            player.setHealth(Math.max(1.0, Math.min(snap.health(), player.getMaxHealth())));
        }
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
        ItemStack[] contents = player.getInventory().getContents();
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
        if (c.clearInventory) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setItemInOffHand(null);
        }
        if (c.hasSnapshot()) {
            applySnapshot(player, c, mode);
            return;
        }
        // 护甲
        if (!c.armor.isEmpty()) {
            ItemStack[] armor = new ItemStack[4];
            armor[3] = resolve(c.armor.get("helmet"), Material.DIAMOND_HELMET);
            armor[2] = resolve(c.armor.get("chestplate"), Material.DIAMOND_CHESTPLATE);
            armor[1] = resolve(c.armor.get("leggings"), Material.DIAMOND_LEGGINGS);
            armor[0] = resolve(c.armor.get("boots"), Material.DIAMOND_BOOTS);
            player.getInventory().setArmorContents(armor);
        }
        // 物品栏
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
        fullState(player, mode);
    }

    /** 应用直接保存的装备快照。 */
    private void applySnapshot(Player player, KitProfile c, WarMode mode) {
        ItemStack[] data = ItemUtil.decode(c.snapshot);
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
        player.getInventory().setContents(contents);
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
                PotionEffectType type = PotionEffectType.getByName(parts[0].trim().toUpperCase(java.util.Locale.ROOT));
                if (type == null) {
                    continue;
                }
                int amp = parts.length > 1 ? NumberUtil.toInt(parts[1], 0) : 0;
                player.addPotionEffect(new PotionEffect(type, duration, Math.max(0, amp), true, false));
            }
        }
        player.setHealth(player.getMaxHealth());
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
