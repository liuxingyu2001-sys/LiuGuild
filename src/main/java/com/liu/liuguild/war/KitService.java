package com.liu.liuguild.war;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.GuildConfig;
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

    public GuildConfig conf() {
        return plugin.config();
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

    /** 发放统一装备。主线程调用。 */
    public void apply(Player player) {
        GuildConfig c = conf();
        if (player == null || !c.kitEnabled) {
            return;
        }
        if (c.kitClearInventory) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setItemInOffHand(null);
        }
        // 护甲
        if (!c.kitArmor.isEmpty()) {
            ItemStack[] armor = new ItemStack[4];
            armor[3] = resolve(c.kitArmor.get("helmet"), Material.DIAMOND_HELMET);
            armor[2] = resolve(c.kitArmor.get("chestplate"), Material.DIAMOND_CHESTPLATE);
            armor[1] = resolve(c.kitArmor.get("leggings"), Material.DIAMOND_LEGGINGS);
            armor[0] = resolve(c.kitArmor.get("boots"), Material.DIAMOND_BOOTS);
            player.getInventory().setArmorContents(armor);
        }
        // 物品栏
        int slot = 0;
        for (String spec : c.kitItems) {
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
        // 药水效果
        int duration = (c.durationSeconds + c.prepareSeconds + 60) * 20;
        for (String eff : c.kitEffects) {
            if (eff == null || eff.isBlank()) {
                continue;
            }
            String[] parts = eff.split(":");
            PotionEffectType type = PotionEffectType.getByName(parts[0].trim().toUpperCase(java.util.Locale.ROOT));
            if (type == null) {
                continue;
            }
            int amp = parts.length > 1 ? parseInt(parts[1], 0) : 0;
            player.addPotionEffect(new PotionEffect(type, duration, Math.max(0, amp), true, false));
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
