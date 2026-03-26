package com.auraboot.framework.menu.constant;

public enum MenuType {
    DIRECTORY(0, "目录"),
    MENU(1, "菜单");

    private final int value;
    private final String description;

    MenuType(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public static MenuType fromValue(int value) {
        for (MenuType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown menu type value: " + value);
    }
}

