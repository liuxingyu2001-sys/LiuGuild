package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 消息 key 一致性守卫。
 *
 * <p>代码里 {@code messages().send(player, "xxx")} 用的 key 若在 messages.yml 里没有定义，
 * 玩家会看到字面的 key（如 {@code checkin-succes}），这类拼写错误极难靠人工发现。
 * 这里扫描全部源码，逐一核对。</p>
 */
class MessageKeyTest {

    /**
     * 提取 {@code messages().send/...} 调用里的**第一个参数**（消息 key）。
     *
     * <p>第一个参数只接受字符串字面量或字面量三元表达式；如果它是变量
     * （如 {@code component(key, ...)}），静态无法得知真实 key，直接跳过——
     * 否则会把后续占位符名（如 "enemy"）误当成 key。</p>
     */
    /**
     * 已知的“接收者”参数名。第一个参数是这些名字时，说明 key 在第二个参数上；
     * 否则第一个参数就是 key（可能是字面量，也可能是变量，变量则跳过）。
     */
    private static final java.util.Set<String> RECIPIENTS = java.util.Set.of(
            "player", "sender", "actor", "victim", "killer", "tp", "p", "to", "target", "viewer");

    private static final Pattern CALL_HEAD = Pattern.compile(
            "messages\\(\\)\\.\\s*(?:send|sendRaw|sendRawRich|sendRich|component|prefixed|prefixedRich)\\(\\s*");

    /** 只接受字面量形式的 key（单个字符串，或三元里的两个字符串）。 */
    private static final Pattern LITERAL_KEY = Pattern.compile(
            "^\\s*\"([a-z][a-z0-9-]*)\"");
    private static final Pattern TERNARY_KEY = Pattern.compile(
            "^\\s*[^,()]*?\\?\\s*\"([a-z][a-z0-9-]*)\"\\s*:\\s*\"([a-z][a-z0-9-]*)\"");

    private static Set<String> definedKeys() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(Path.of("src/main/resources/messages.yml")));
        Set<String> keys = new LinkedHashSet<>();
        for (String section : cfg.getKeys(false)) {
            var sec = cfg.getConfigurationSection(section);
            if (sec != null) {
                keys.addAll(sec.getKeys(true).stream()
                        .filter(k -> !sec.isConfigurationSection(k))
                        .toList());
            }
        }
        return keys;
    }

    private static List<String> usedKeys() throws Exception {
        Set<String> used = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                Matcher head = CALL_HEAD.matcher(src);
                while (head.find()) {
                    String rest = src.substring(head.end());
                    // 第一个参数是接收者（player/sender/...）时，跳过它，key 在下一个参数
                    Matcher firstArg = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.]*(?:\\([^()]*\\))?)\\s*,\\s*")
                            .matcher(rest);
                    if (firstArg.find() && RECIPIENTS.contains(firstArg.group(1))) {
                        rest = rest.substring(firstArg.end());
                    }
                    Matcher t = TERNARY_KEY.matcher(rest);
                    if (t.find()) {
                        used.add(t.group(1));
                        used.add(t.group(2));
                        continue;
                    }
                    Matcher lit = LITERAL_KEY.matcher(rest);
                    if (lit.find()) {
                        used.add(lit.group(1));
                    }
                }
            }
        }
        return new ArrayList<>(used);
    }

    @Test
    void everyMessageKeyUsedInCodeIsDefined() throws Exception {
        Set<String> defined = definedKeys();
        List<String> missing = new ArrayList<>();
        for (String key : usedKeys()) {
            if (!defined.contains(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            fail("这些消息 key 在代码里用了但 messages.yml 没定义（玩家会看到原始 key）: " + missing);
        }
    }

    @Test
    void messageKeysLookSane() throws Exception {
        assertTrue(usedKeys().size() > 100, "应扫描到大量消息 key，否则正则可能失效");
    }
}
