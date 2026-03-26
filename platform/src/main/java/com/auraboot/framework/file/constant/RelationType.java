package com.auraboot.framework.file.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum RelationType {
    ATTACHMENT("attachment", "附件"),
    AVATAR("avatar", "头像"),
    DOCUMENT("document", "文档"),
    IMAGE("image", "图片");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    RelationType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}