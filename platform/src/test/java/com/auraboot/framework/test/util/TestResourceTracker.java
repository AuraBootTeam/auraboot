package com.auraboot.framework.test.util;

import java.util.HashSet;
import java.util.Set;

/**
 * 测试资源追踪器
 *
 * 用于记录测试期间创建的资源,以便在测试结束后进行清理
 *
 * @author AuraBoot Team
 * @since 2.1.2
 */
public class TestResourceTracker {

    private final Set<String> models = new HashSet<>();
    private final Set<Long> releases = new HashSet<>();
    private final Set<String> fields = new HashSet<>();
    private final Set<String> dicts = new HashSet<>();

    /**
     * 添加模型到追踪列表
     * @param modelCode 模型编码
     */
    public void addModel(String modelCode) {
        models.add(modelCode);
    }

    /**
     * 添加Release到追踪列表
     * @param releaseId Release ID
     */
    public void addRelease(Long releaseId) {
        releases.add(releaseId);
    }

    /**
     * 添加字段到追踪列表
     * @param code 字段键
     */
    public void addField(String code) {
        fields.add(code);
    }

    /**
     * 添加字典到追踪列表
     * @param dictCode 字典编码
     */
    public void addDict(String dictCode) {
        dicts.add(dictCode);
    }

    /**
     * 获取所有追踪的模型
     * @return 模型编码集合
     */
    public Set<String> getModels() {
        return models;
    }

    /**
     * 获取所有追踪的Release
     * @return Release ID集合
     */
    public Set<Long> getReleases() {
        return releases;
    }

    /**
     * 获取所有追踪的字段
     * @return 字段键集合
     */
    public Set<String> getFields() {
        return fields;
    }

    /**
     * 获取所有追踪的字典
     * @return 字典编码集合
     */
    public Set<String> getDicts() {
        return dicts;
    }
}
