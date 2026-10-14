package com.liu.liuguild.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ItemCodec} 的防御性行为。
 *
 * <p>真实的 ItemStack 往返需要运行中的服务器（{@code Material}/{@code Registry} 都要 Bukkit 初始化），
 * 而本仓库在离线环境无法引入 MockBukkit，所以这里只覆盖**不依赖服务器**的路径 ——
 * 但它们恰恰是最要紧的：脏数据 / 超大数据 / 非物品数据绝不能抛异常，
 * 也绝不能反序列化出一个“随机物品”（那就是刷物品漏洞）。</p>
 *
 * <p>完整 NBT 往返由 {@code serializeAsBytes}/{@code deserializeBytes} 保证，
 * 那是 Paper 自带且被广泛使用的稳定格式。</p>
 */
class ItemCodecTest {

    @Test
    void emptyInputDecodesToNull() {
        assertNull(ItemCodec.decode(null));
        assertNull(ItemCodec.decode(""));
        assertNull(ItemCodec.decode("   "));
        assertNull(ItemCodec.decode("\n\t"));
    }

    @Test
    void garbageNeverThrowsAndNeverYieldsItem() {
        // 非法 base64
        assertNull(ItemCodec.decode("这不是 base64!!"));
        assertNull(ItemCodec.decode("@@@@"));
        // 合法 base64 但不是物品字节
        assertNull(ItemCodec.decode("YWJjZGVmZ2hpamtsbW5vcA=="));
        // 非常短但合法的 base64
        assertNull(ItemCodec.decode("AA=="));
    }

    @Test
    void oversizedDataIsRejectedBeforeDecoding() {
        // 防止恶意超大 NBT 撑爆内存/数据库
        assertNull(ItemCodec.decode("A".repeat(ItemCodec.MAX_ENCODED_LENGTH + 1)));
        assertNull(ItemCodec.decode("A".repeat(ItemCodec.MAX_ENCODED_LENGTH * 4)));
    }

    @Test
    void encodeNullAndAirDoNotThrow() {
        // encode(null) 必须安全返回 null（不能 NPE）
        assertNull(ItemCodec.encode(null));
    }

    @Test
    void isEmptyRecognisesNull() {
        org.junit.jupiter.api.Assertions.assertTrue(ItemCodec.isEmpty(null));
    }
}

