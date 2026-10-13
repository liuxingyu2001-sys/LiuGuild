package com.liu.liuguild.module;

import com.liu.liuguild.LiuGuild;

import java.util.Map;

/**
 * 功能模块接口。所有功能（工会核心、工会战、GUI、PAPI、跨服…）都实现它，
 * 由 {@link ModuleRegistry} 统一按序启停与重载，便于增删功能与二次开发。
 */
public interface GuildModule {

    /** 唯一 id（日志用）。 */
    String id();

    /** 启动顺序，越小越早。 */
    default int order() {
        return 100;
    }

    /** 启动模块。 */
    void enable(LiuGuild plugin) throws Exception;

    /** 关闭模块（可选）。 */
    default void disable() {
    }

    /** 配置热重载（可选）。 */
    default void reload() {
    }

    /** 收到跨服消息（可选）。 */
    default void onCrossServer(String type, Map<String, String> data) {
    }
}
