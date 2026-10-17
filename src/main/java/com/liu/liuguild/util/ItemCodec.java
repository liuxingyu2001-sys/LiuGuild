package com.liu.liuguild.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 物品序列化 / 反序列化（仓库、商店共用）。
 *
 * <p>优先使用 Paper 自带的 {@link ItemStack#serializeAsBytes()}：它是版本稳定的二进制格式，
 * 能完整保留 NBT / 1.20.5+ 数据组件（自定义名、lore、附魔、CE 物品、玩家头颅皮肤等），
 * 不依赖任何第三方库。仅当该方法不可用（旧版本）时才回退 Bukkit 对象流。</p>
 *
 * <p>编码结果以 <b>Base64</b> 存入 TEXT 列，避免二进制入库问题（MySQL/SQLite 通用）。</p>
 */
public final class ItemCodec {

    /** 单件物品编码后的最大长度；超过视为异常数据直接拒绝，防止恶意超大 NBT 撑爆数据库。 */
    public static final int MAX_ENCODED_LENGTH = 60_000;

    private static final Logger LOG = Logger.getLogger("LiuGuild");

    private ItemCodec() {
    }

    /** 物品是否为“空”（null 或空气）。 */
    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    /**
     * 序列化为 Base64 字符串。
     *
     * @return 空物品返回 {@code null}
     */
    public static String encode(ItemStack stack) {
        if (isEmpty(stack)) {
            return null;
        }
        ItemStack copy = stack.clone();
        try {
            byte[] bytes = copy.serializeAsBytes();
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return bounded(Base64.getEncoder().encodeToString(bytes));
        } catch (Throwable first) {
            // 回退：极少数情况下 serializeAsBytes 会因自定义物品异常
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (BukkitObjectOutputStream oos = new BukkitObjectOutputStream(out)) {
                    oos.writeObject(copy);
                }
                return bounded(Base64.getEncoder().encodeToString(out.toByteArray()));
            } catch (Throwable second) {
                LOG.log(Level.WARNING, "物品序列化失败，已跳过该物品: " + copy.getType(), second);
                return null;
            }
        }
    }

    private static String bounded(String encoded) {
        if (encoded == null) return null;
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            LOG.warning("物品编码过大（" + encoded.length() + " 字符），已拒绝写入。");
            return null;
        }
        return encoded;
    }

    /**
     * 反序列化 Base64 字符串。
     *
     * @return 数据为空/损坏时返回 {@code null}（不会抛异常，调用方按空槽处理）
     */
    public static ItemStack decode(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        if (data.length() > MAX_ENCODED_LENGTH) {
            LOG.warning("仓库/商店物品数据过大（" + data.length() + " 字符），已拒绝反序列化。");
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            return ItemStack.deserializeBytes(bytes);
        } catch (Throwable first) {
            // 回退：兼容旧版对象流格式
            try {
                byte[] bytes = Base64.getDecoder().decode(data);
                try (BukkitObjectInputStream ois = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                    Object o = ois.readObject();
                    return o instanceof ItemStack stack ? stack : null;
                }
            } catch (Throwable second) {
                LOG.log(Level.WARNING, "物品反序列化失败，该槽位按空处理。", second);
                return null;
            }
        }
    }

    /** 反序列化并保证数量在 1..maxStack（防止脏数据导致超堆叠）。 */
    public static ItemStack decodeClamped(String data) {
        ItemStack stack = decode(data);
        if (stack == null) {
            return null;
        }
        int max = Math.max(1, stack.getMaxStackSize());
        if (stack.getAmount() <= 0) {
            stack.setAmount(1);
        } else if (stack.getAmount() > max) {
            stack.setAmount(max);
        }
        return stack;
    }
}
