package com.liu.liuguild.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单个 GUI 菜单定义。 */
public final class MenuDef {

    public final String key;
    public final String title;
    public final int rows;
    public final Map<String, ButtonDef> buttons = new LinkedHashMap<>();
    public ButtonDef entry;
    public ButtonDef back;

    public MenuDef(String key, String title, int rows) {
        this.key = key;
        this.title = title == null ? "" : title;
        this.rows = Math.max(1, Math.min(6, rows));
    }

    public ButtonDef button(String id) {
        return buttons.get(id);
    }
}
