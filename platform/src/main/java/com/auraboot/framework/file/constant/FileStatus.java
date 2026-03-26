package com.auraboot.framework.file.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 文件状态枚举
 */
@Getter
public enum FileStatus {
    ACTIVE("active", "正常"),
    DELETED("deleted", "已删除");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    FileStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}