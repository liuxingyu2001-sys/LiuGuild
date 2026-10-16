package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class BuffSettingsTest {
    @Test void bundledDefaultsProvideFourBuffsAndLevelSlots() throws Exception {
        var yaml = new YamlConfiguration(); yaml.load(new File("src/main/resources/buffs.yml"));
        var settings = BuffSettings.parse(yaml);
        assertEquals(4, settings.buffs.size());
        assertEquals(1, settings.slots(1)); assertEquals(2, settings.slots(5)); assertEquals(3, settings.slots(10));
        assertEquals(0.1, settings.buffs.get("experience").experienceBonus());
        yaml.set("buffs.speed.cost", -1);
        assertThrows(IllegalArgumentException.class, () -> BuffSettings.parse(yaml));
    }
    @Test void rejectsUnsupportedEffectsAndExcessDuration() throws Exception {
        var yaml = new YamlConfiguration(); yaml.load(new File("src/main/resources/buffs.yml"));
        yaml.set("buffs.speed.effect", "INCREASE_DAMAGE");
        assertThrows(IllegalArgumentException.class, () -> BuffSettings.parse(yaml));
        yaml.set("buffs.speed.effect", "SPEED"); yaml.set("buffs.speed.duration-seconds", 999999);
        assertThrows(IllegalArgumentException.class, () -> BuffSettings.parse(yaml));
    }
}
