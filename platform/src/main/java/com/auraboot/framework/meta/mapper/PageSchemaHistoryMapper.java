package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.PageSchemaHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * 页面Schema历史记录Mapper接口（简化版本）
 * 对应表：ab_page_schema_history
 *
 * 基于简化设计的查询接口：
 * - 支持按页面PID查询历史记录
 * - 支持按操作类型和时间范围查询
 * - 支持JSONB快照内容查询
 * - 自动处理租户隔离
 */
@Mapper
public interface PageSchemaHistoryMapper extends BaseMapper<PageSchemaHistory> {

    /**
     * 根据页面PID查询历史记录
     *
     * @param pagePid 页面PID
     * @return 历史记录列表，按操作时间倒序
     */
    @Select("SELECT * FROM ab_page_schema_history WHERE pid = #{pagePid} ORDER BY op_at DESC")
    List<PageSchemaHistory> findByPagePid(@Param("pagePid") String pagePid);

    /**
     * 分页查询页面历史记录
     *
     * @param pagePid 页面PID
     * @param page 分页参数
     * @param size 每页大小
     * @return 分页结果
     */
    default Page<PageSchemaHistory> findByPagePidWithPagination(String pagePid, int page, int size) {
        return selectPageByPagePid(new Page<>(page, size), pagePid);
    }

    /**
     * 按页面PID分页查询
     */
    @Select("SELECT * FROM ab_page_schema_history WHERE pid = #{pagePid} ORDER BY op_at DESC")
    Page<PageSchemaHistory> selectPageByPagePid(Page<PageSchemaHistory> page, @Param("pagePid") String pagePid);

    /**
     * 根据操作类型查询历史记录
     *
     * @param pagePid 页面PID
     * @param op 操作类型
     * @return 历史记录列表
     */
    @Select("SELECT * FROM ab_page_schema_history WHERE pid = #{pagePid} AND op = #{op} ORDER BY op_at DESC")
    List<PageSchemaHistory> findByPagePidAndOp(@Param("pagePid") String pagePid, @Param("op") String op);

    /**
     * 根据操作人查询历史记录
     *
     * @param opBy 操作人PID
     * @return 历史记录列表
     */
    @Select("SELECT * FROM ab_page_schema_history WHERE op_by = #{opBy} ORDER BY op_at DESC")
    List<PageSchemaHistory> findByOperator(@Param("opBy") String opBy);

    /**
     * 根据时间范围查询历史记录
     *
     * @param pagePid 页面PID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 历史记录列表
     */
    @Select("SELECT * FROM ab_page_schema_history WHERE pid = #{pagePid} AND op_at BETWEEN #{startTime} AND #{endTime} ORDER BY op_at DESC")
    List<PageSchemaHistory> findByPagePidAndTimeRange(
        @Param("pagePid") String pagePid,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    /**
     * 查询最新的历史记录
     *
     * @param pagePid 页面PID
     * @return 最新的历史记录
     */
    @Select("SELECT * FROM ab_page_schema_history WHERE pid = #{pagePid} ORDER BY op_at DESC LIMIT 1")
    PageSchemaHistory findLatestByPagePid(@Param("pagePid") String pagePid);

    /**
     * 统计页面的历史记录数量
     *
     * @param pagePid 页面PID
     * @return 历史记录数量
     */
    @Select("SELECT COUNT(*) FROM ab_page_schema_history WHERE pid = #{pagePid}")
    Long countByPagePid(@Param("pagePid") String pagePid);

    /**
     * 根据操作类型统计历史记录
     *
     * @param pagePid 页面PID
     * @param op 操作类型
     * @return 历史记录数量
     */
    @Select("SELECT COUNT(*) FROM ab_page_schema_history WHERE pid = #{pagePid} AND op = #{op}")
    Long countByPagePidAndOp(@Param("pagePid") String pagePid, @Param("op") String op);

     /**
     * 查询快照中包含特定JSON结构的历史记录
     *
     * 安全提示：jsonQuery参数必须是可信来源，不能直接使用用户输入
     * 建议：在Service层进行JSON格式验证
     *
     * @param pagePid 页面PID
     * @param jsonQuery JSONB查询条件（必须是有效的JSON字符串）
     * @return 历史记录列表
     */
    @Select("""
            SELECT * FROM ab_page_schema_history
            WHERE pid = #{pagePid}
              AND snapshot @> #{jsonQuery}::jsonb
            ORDER BY op_at DESC
            LIMIT 100
            """)
    List<PageSchemaHistory> findBySnapshotContains(@Param("pagePid") String pagePid,
                                                   @Param("jsonQuery") String jsonQuery);
}
