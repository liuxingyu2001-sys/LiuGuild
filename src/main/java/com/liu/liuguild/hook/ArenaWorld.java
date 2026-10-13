package com.liu.liuguild.hook;

import org.bukkit.World;

/** 已就绪的竞技场世界（对 liudungeon 句柄的薄封装，避免类型泄漏）。 */
public record ArenaWorld(String worldName, World world, Object handle, boolean external) {
}
