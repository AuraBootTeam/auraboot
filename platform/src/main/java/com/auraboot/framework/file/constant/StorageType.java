package com.auraboot.framework.file.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 存储类型枚举
 */
@Getter
public enum StorageType {
    LOCAL("local", "本地存储"),
    MINIO("minio", "MinIO Object Storage"),
    OSS("oss", "阿里云OSS"),
    S3("s3", "AWS S3");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    StorageType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}

