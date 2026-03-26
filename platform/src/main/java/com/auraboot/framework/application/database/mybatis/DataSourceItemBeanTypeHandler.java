package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import com.fasterxml.jackson.databind.JavaType;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DataSourceItemsBean类型处理器
 * 用于处理数据库JSONB字段与DataSourceItemsBean对象之间的转换
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Component
@MappedTypes({List.class})
    public class DataSourceItemBeanTypeHandler extends GenericJavaTypeJacksonTypeHandler<List<DataSourceItemBean>> {

        private static final JavaType JAVA_TYPE =
                objectMapper.getTypeFactory().constructCollectionType(List.class, DataSourceItemBean.class);

        public DataSourceItemBeanTypeHandler() {
            super(JAVA_TYPE);
        }
    }