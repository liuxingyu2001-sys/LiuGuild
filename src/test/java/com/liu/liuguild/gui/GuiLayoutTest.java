package com.liu.liuguild.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    @Test
    void configuredButtonsHaveUniqueSlotsAndListsKeepTheirFooter() throws Exception {
        var config = new YamlConfiguration();
        config.load(new File("src/main/resources/gui.yml"));
        var gui = config.getConfigurationSection("gui");
        assertNotNull(gui);
        for (String name : gui.getKeys(false)) {
            var menu = gui.getConfigurationSection(name);
            if (menu == null || !menu.contains("rows")) continue;
            int size = menu.getInt("rows") * 9;
            Set<Integer> occupied = new HashSet<>();
            var buttons = menu.getConfigurationSection("buttons");
            if (buttons != null) {
                for (String id : buttons.getKeys(false)) {
                    int slot = buttons.getInt(id + ".slot");
                    assertTrue(slot >= 0 && slot < size, name + "." + id + " 越界");
                    assertTrue(occupied.add(slot), name + " 槽位重叠: " + slot);
                    if (menu.isConfigurationSection("entry")) {
                        assertTrue(slot >= size - 9, name + "." + id + " 遮挡列表内容");
                    }
                }
            }
            if (menu.isConfigurationSection("back")) {
                int back = menu.getInt("back.slot");
                assertTrue(back >= size - 9 && back < size, name + " 返回按钮必须在底栏");
                assertTrue(occupied.add(back), name + " 返回按钮被覆盖");
                assertNotEquals(size - 9, back, name + " 返回占用上一页");
                assertNotEquals(size - 1, back, name + " 返回占用下一页");
            }
        }
    }
}
