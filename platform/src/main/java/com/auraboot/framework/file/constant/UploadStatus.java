package com.auraboot.framework.file.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum UploadStatus {
    UPLOADING("uploading", "上传中"),
    SUCCESS("success", "上传成功"),
    FAILED("failed", "上传失败"),
    DELETED("deleted", "已删除");

    @EnumValue
    @JsonValue
    public final String code;
    private final String desc;

    UploadStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}