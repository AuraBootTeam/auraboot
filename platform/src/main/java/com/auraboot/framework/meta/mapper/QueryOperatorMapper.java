package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.QueryOperator;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 查询操作符Mapper接口
 * 对应表：ab_query_operator
 */
@Mapper
public interface QueryOperatorMapper extends BaseMapper<QueryOperator> {

    /**
     * 根据操作符编码查询操作符
     * @param opCode 操作符编码
     * @return 查询操作符
     */
    @Select("SELECT * FROM ab_query_operator WHERE op_code = #{opCode}")
    QueryOperator findByOpCode(@Param("opCode") String opCode);

    /**
     * 查询所有操作符
     * @return 操作符列表
     */
    @Select("SELECT * FROM ab_query_operator ORDER BY op_code")
    List<QueryOperator> findAll();

    /**
     * 根据值类型查询操作符
     * @param valueType 值类型
     * @return 操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE value_type = #{valueType} ORDER BY op_code")
    List<QueryOperator> findByValueType(@Param("valueType") String valueType);

    /**
     * 查询支持指定数据类型的操作符
     * @param dataType 数据类型
     * @return 操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE value_type = 'any' OR " +
            "(#{dataType} = 'string' AND value_type IN ('string', 'any')) OR " +
            "(#{dataType} = 'number' AND value_type IN ('number', 'any')) OR " +
            "(#{dataType} = 'date' AND value_type IN ('number', 'any')) OR " +
            "(#{dataType} = 'boolean' AND value_type = 'any') " +
            "ORDER BY op_code")
    List<QueryOperator> findByDataType(@Param("dataType") String dataType);

    /**
     * 查询比较操作符
     * @return 比较操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE op_code IN ('eq', 'ne', 'gt', 'gte', 'lt', 'lte') ORDER BY op_code")
    List<QueryOperator> findComparisonOperators();

    /**
     * 查询字符串操作符
     * @return 字符串操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE op_code IN ('like', 'ilike', 'starts_with', 'ends_with', 'contains') ORDER BY op_code")
    List<QueryOperator> findStringOperators();

    /**
     * 查询数组操作符
     * @return 数组操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE op_code IN ('in', 'not_in') ORDER BY op_code")
    List<QueryOperator> findArrayOperators();

    /**
     * 查询空值操作符
     * @return 空值操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE op_code IN ('is_null', 'is_not_null') ORDER BY op_code")
    List<QueryOperator> findNullOperators();

    /**
     * 查询范围操作符
     * @return 范围操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE op_code = 'between'")
    List<QueryOperator> findRangeOperators();

    /**
     * 查询不需要值的操作符
     * @return 不需要值的操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE value_type = 'none' ORDER BY op_code")
    List<QueryOperator> findNoValueOperators();

    /**
     * 根据SQL模板模糊查询操作符
     * @param sqlPattern SQL模板模式
     * @return 操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE sql_tpl LIKE #{sqlPattern} ORDER BY op_code")
    List<QueryOperator> findBySqlPattern(@Param("sqlPattern") String sqlPattern);

    /**
     * 根据说明模糊查询操作符
     * @param notesPattern 说明模式
     * @return 操作符列表
     */
    @Select("SELECT * FROM ab_query_operator WHERE notes LIKE #{notesPattern} ORDER BY op_code")
    List<QueryOperator> findByNotesPattern(@Param("notesPattern") String notesPattern);

    /**
     * 检查操作符编码是否存在
     * @param opCode 操作符编码
     * @return 是否存在
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_query_operator WHERE op_code = #{opCode}")
    boolean existsByOpCode(@Param("opCode") String opCode);

    /**
     * 获取操作符总数
     * @return 操作符总数
     */
    @Select("SELECT COUNT(*) FROM ab_query_operator")
    int countAll();

    /**
     * 更新操作符的SQL模板
     * @param opCode 操作符编码
     * @param sqlTpl 新的SQL模板
     * @return 更新的记录数
     */
    @Update("UPDATE ab_query_operator SET sql_tpl = #{sqlTpl} WHERE op_code = #{opCode}")
    int updateSqlTpl(@Param("opCode") String opCode, @Param("sqlTpl") String sqlTpl);

    /**
     * 更新操作符的值类型
     * @param opCode 操作符编码
     * @param valueType 新的值类型
     * @return 更新的记录数
     */
    @Update("UPDATE ab_query_operator SET value_type = #{valueType} WHERE op_code = #{opCode}")
    int updateValueType(@Param("opCode") String opCode, @Param("valueType") String valueType);

    /**
     * 更新操作符的说明
     * @param opCode 操作符编码
     * @param notes 新的说明
     * @return 更新的记录数
     */
    @Update("UPDATE ab_query_operator SET notes = #{notes} WHERE op_code = #{opCode}")
    int updateNotes(@Param("opCode") String opCode, @Param("notes") String notes);

    /**
     * 批量插入操作符
     * @param operators 操作符列表
     * @return 插入的记录数
     */
    default int batchInsert(List<QueryOperator> operators) {
        int count = 0;
        for (QueryOperator operator : operators) {
            count += insert(operator);
        }
        return count;
    }

    /**
     * 根据操作符编码列表查询操作符
     * @param opCodes 操作符编码列表
     * @return 操作符列表
     */
    @Select("<script>" +
            "SELECT * FROM ab_query_operator WHERE op_code IN " +
            "<foreach collection='opCodes' item='opCode' open='(' separator=',' close=')'>" +
            "#{opCode}" +
            "</foreach>" +
            " ORDER BY op_code" +
            "</script>")
    List<QueryOperator> findByOpCodes(@Param("opCodes") List<String> opCodes);

    /**
     * 验证操作符编码列表的有效性
     * @param opCodes 操作符编码列表
     * @return 有效的操作符编码列表
     */
    @Select("<script>" +
            "SELECT op_code FROM ab_query_operator WHERE op_code IN " +
            "<foreach collection='opCodes' item='opCode' open='(' separator=',' close=')'>" +
            "#{opCode}" +
            "</foreach>" +
            " ORDER BY op_code" +
            "</script>")
    List<String> validateOpCodes(@Param("opCodes") List<String> opCodes);

    /**
     * 物理删除测试数据 - 根据操作符编码删除记录
     * @param opCode 操作符编码
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_query_operator WHERE op_code = #{opCode}")
    int deleteByOpCode(@Param("opCode") String opCode);

    /**
     * 物理删除所有测试数据
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_query_operator")
    int deleteAll();
}
