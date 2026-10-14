package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 守卫测试：保证打包进 jar 的所有资源 YAML 都能被正确解析。
 * 之前 gui.yml 曾因多出一个引号导致线上启动崩溃，这里把该问题挡在构建阶段。
 */
class ResourceYamlTest {

    @Test
    void everyResourceYamlParses() throws Exception {
        Path dir = Paths.get("src/main/resources");
        assertTrue(Files.isDirectory(dir), "找不到资源目录: " + dir.toAbsolutePath());
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        assertTrue(files.size() >= 10, "资源 YAML 数量异常: " + files.size());
        for (Path p : files) {
            String text = Files.readString(p);
            YamlConfiguration cfg = new YamlConfiguration();
            try {
                cfg.loadFromString(text);
            } catch (Exception e) {
                fail(p.getFileName() + " 解析失败: " + e.getMessage());
            }
        }
    }
}
