package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.PageSchemaVersionDTO;
import com.auraboot.framework.meta.dto.PageSchemaVersionComparisonDTO;
import com.auraboot.framework.exception.ValidationException;

import java.util.List;

/**
 * 页面Schema版本管理服务接口
 * 提供版本控制、历史记录管理和版本比较功能
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
public interface PageSchemaVersionService {

    // ==================== 版本创建和管理 ====================

    /**
     * 创建新版本
     * 在页面Schema更新时自动创建版本历史记录
     * 
     * @param pagePid 页面Schema的业务主键
     * @param operation 操作类型（CREATE, UPDATE, PUBLISH等）
     * @param operatorPid 操作人PID
     * @param description 版本描述/变更原因
     * @return 创建的版本信息
     * @throws ValidationException 当参数验证失败时抛出
     */
    PageSchemaVersionDTO createVersion(String pagePid, String operation, String operatorPid, String description);

    /**
     * 获取页面的版本历史列表
     * 按操作时间倒序排列
     * 
     * @param pagePid 页面Schema的业务主键
     * @return 版本历史列表
     */
    List<PageSchemaVersionDTO> getVersionHistory(String pagePid);

    /**
     * 获取指定版本的详细信息
     * 
     * @param historyId 历史记录ID
     * @return 版本详细信息
     * @throws ValidationException 当版本不存在时抛出
     */
    PageSchemaVersionDTO getVersionById(Long historyId);

    /**
     * 获取页面的最新版本
     * 
     * @param pagePid 页面Schema的业务主键
     * @return 最新版本信息，如果不存在则返回null
     */
    PageSchemaVersionDTO getLatestVersion(String pagePid);

    // ==================== 版本比较功能 ====================

    /**
     * 比较两个版本之间的差异
     * 
     * @param sourceHistoryId 源版本历史记录ID
     * @param targetHistoryId 目标版本历史记录ID
     * @return 版本比较结果
     * @throws ValidationException 当版本不存在时抛出
     */
    PageSchemaVersionComparisonDTO compareVersions(Long sourceHistoryId, Long targetHistoryId);

    /**
     * 比较指定版本与当前版本的差异
     * 
     * @param pagePid 页面Schema的业务主键
     * @param historyId 要比较的历史版本ID
     * @return 版本比较结果
     * @throws ValidationException 当页面或版本不存在时抛出
     */
    PageSchemaVersionComparisonDTO compareWithCurrent(String pagePid, Long historyId);

    // ==================== 版本回滚功能 ====================

    /**
     * 回滚到指定版本
     * 将页面Schema恢复到历史版本的状态
     * 
     * @param pagePid 页面Schema的业务主键
     * @param historyId 要回滚到的历史版本ID
     * @param operatorPid 操作人PID
     * @param reason 回滚原因
     * @return 回滚后的版本信息
     * @throws ValidationException 当页面或版本不存在，或回滚验证失败时抛出
     */
    PageSchemaVersionDTO rollbackToVersion(String pagePid, Long historyId, String operatorPid, String reason);

    /**
     * 验证是否可以回滚到指定版本
     * 
     * @param pagePid 页面Schema的业务主键
     * @param historyId 要回滚到的历史版本ID
     * @return 是否可以回滚
     */
    boolean canRollbackToVersion(String pagePid, Long historyId);

    // ==================== 版本发布管理 ====================

    /**
     * 发布指定版本
     * 将历史版本设置为已发布状态，并更新当前页面Schema
     * 
     * @param pagePid 页面Schema的业务主键
     * @param historyId 要发布的历史版本ID
     * @param operatorPid 操作人PID
     * @return 发布后的版本信息
     * @throws ValidationException 当页面或版本不存在，或发布验证失败时抛出
     */
    PageSchemaVersionDTO publishVersion(String pagePid, Long historyId, String operatorPid);

    /**
     * 取消发布指定版本
     * 
     * @param pagePid 页面Schema的业务主键
     * @param historyId 要取消发布的历史版本ID
     * @param operatorPid 操作人PID
     * @return 取消发布后的版本信息
     * @throws ValidationException 当页面或版本不存在时抛出
     */
    PageSchemaVersionDTO unpublishVersion(String pagePid, Long historyId, String operatorPid);

    // ==================== 版本统计和查询 ====================

    /**
     * 统计页面的版本数量
     * 
     * @param pagePid 页面Schema的业务主键
     * @return 版本数量
     */
    Long countVersions(String pagePid);

    /**
     * 统计指定操作类型的版本数量
     * 
     * @param pagePid 页面Schema的业务主键
     * @param operation 操作类型
     * @return 版本数量
     */
    Long countVersionsByOperation(String pagePid, String operation);

    /**
     * 获取已发布的版本列表
     * 
     * @param pagePid 页面Schema的业务主键
     * @return 已发布版本列表
     */
    List<PageSchemaVersionDTO> getPublishedVersions(String pagePid);

    // ==================== 版本清理和维护 ====================

    /**
     * 清理过期的版本历史
     * 保留最近的N个版本，删除更早的版本
     * 
     * @param pagePid 页面Schema的业务主键
     * @param keepCount 保留的版本数量
     * @return 清理的版本数量
     */
    Integer cleanupOldVersions(String pagePid, Integer keepCount);

    /**
     * 验证版本数据完整性
     * 检查版本快照数据是否完整和有效
     * 
     * @param historyId 历史记录ID
     * @return 验证结果
     */
    boolean validateVersionIntegrity(Long historyId);
}