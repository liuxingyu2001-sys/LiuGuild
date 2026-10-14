package com.liu.liuguild.module;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 跨服广播的「回音守卫」。
 *
 * <p>Redis 发布订阅会把消息回送给发送者自己。如果一个模块在收到自己刚发出的广播后
 * 去重载状态，就会和**尚未完成的异步写库**竞争 —— 结果是刚创建的头衔 / 刚上架的商品
 * 被旧数据冲掉（要等下次重载才恢复）。</p>
 *
 * <p>所以每个处理跨服消息的模块都必须：① 广播里带上 {@code server}；② 收到自己服务器的
 * 消息时直接返回。这个测试扫源码强制这两点。</p>
 */
class CrossServerEchoGuardTest {

    /** 本次新增的、会广播状态的模块。 */
    private static final List<String> MODULES = List.of(
            "src/main/java/com/liu/liuguild/title/TitleService.java",
            "src/main/java/com/liu/liuguild/shop/ShopService.java");

    @Test
    void everyBroadcastCarriesTheOriginServer() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String path : MODULES) {
            String src = Files.readString(Paths.get(path));
            if (!src.contains("bus().publish(")) {
                continue;
            }
            // 每个 publish 调用附近都必须出现 server 字段
            int idx = 0;
            while ((idx = src.indexOf("bus().publish(", idx)) >= 0) {
                String window = src.substring(idx, Math.min(src.length(), idx + 300));
                if (!window.contains("\"server\"")) {
                    bad.add(Paths.get(path).getFileName() + " @" + idx);
                }
                idx += 10;
            }
        }
        if (!bad.isEmpty()) {
            fail("这些跨服广播没有带 server 字段（会被自己的回音冲掉状态）: " + bad);
        }
    }

    @Test
    void everyModuleThatHandlesCrossServerIgnoresItsOwnEcho() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String path : MODULES) {
            String src = Files.readString(Paths.get(path));
            if (!src.contains("onCrossServer(")) {
                continue;
            }
            int start = src.indexOf("public void onCrossServer(");
            if (start < 0) {
                // 接口 default 实现，跳过
                continue;
            }
            // 方法体：到下一个方法的 public 声明或文件尾
            int end = src.indexOf("\n    public ", start + 10);
            String body = end < 0 ? src.substring(start) : src.substring(start, end);
            if (!body.contains("plugin.serverId()")) {
                bad.add(Paths.get(path).getFileName().toString());
            }
        }
        if (!bad.isEmpty()) {
            fail("这些模块处理跨服消息时没有跳过自己的回音: " + bad);
        }
    }

    /** 守卫本身要能真的找到被测文件，否则测试是假阳性空转。 */
    @Test
    void guardActuallyCoversRealFiles() {
        for (String path : MODULES) {
            assertTrue(Files.exists(Paths.get(path)), "被测文件不存在: " + path);
        }
        assertTrue(MODULES.size() >= 2);
    }
}
