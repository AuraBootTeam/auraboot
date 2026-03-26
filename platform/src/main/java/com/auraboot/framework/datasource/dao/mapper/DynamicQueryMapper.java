package com.auraboot.framework.datasource.dao.mapper;

import com.auraboot.framework.exception.BusinessException;
import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 动态查询数据访问服务
 * 使用 MyBatis-Plus SqlRunner 执行原生 SQL 查询
 */
@Slf4j
@Service
public class DynamicQueryMapper {
    
    /**
     * 基础查询数据（带分页）
     * @param sql 完整的SQL语句
     * @return 查询结果
     */
    public List<Map<String, Object>> queryData(String sql) {
        log.debug("执行查询SQL: {}", sql);
        try {
            return SqlRunner.db().selectList(sql);
            
        } catch (Exception e) {
            log.error("查询数据失败, SQL: {}", sql, e);
            throw new BusinessException("Query data failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 统计总记录数
     * @param sql 完整的COUNT SQL语句
     * @return 记录总数
     */
    public Long countData(String sql) {
        log.debug("执行统计SQL: {}", sql);
        try {
            Object result = SqlRunner.db().selectObj(sql);
            if (result == null) {
                return 0L;
            }
            if (result instanceof Number) {
                return ((Number) result).longValue();
            }
            return Long.parseLong(result.toString());
        } catch (Exception e) {
            log.error("统计数据失败, SQL: {}", sql, e);
            throw new BusinessException("Count data failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 创建数据
     * @param sql 完整的INSERT SQL语句
     * @return 影响的行数
     */
    public int createData(String sql) {
        log.debug("执行插入SQL: {}", sql);
        try {
            boolean result = SqlRunner.db().insert(sql);
            return result ? 1 : 0;
        } catch (Exception e) {
            log.error("插入数据失败, SQL: {}", sql, e);
            throw new BusinessException("Insert data failed: " + sql, e);
        }
    }
}