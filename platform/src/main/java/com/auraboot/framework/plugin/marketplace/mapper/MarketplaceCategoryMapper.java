package com.auraboot.framework.plugin.marketplace.mapper;

import com.auraboot.framework.plugin.marketplace.entity.MarketplaceCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface MarketplaceCategoryMapper extends BaseMapper<MarketplaceCategory> {

    @Select("SELECT * FROM ab_marketplace_category ORDER BY sort_order ASC")
    List<MarketplaceCategory> findAll();

    @Select("SELECT * FROM ab_marketplace_category WHERE code = #{code}")
    MarketplaceCategory findByCode(@Param("code") String code);

    @Update("UPDATE ab_marketplace_category SET plugin_count = (SELECT COUNT(*) FROM ab_marketplace_plugin WHERE category_code = #{code} AND status = 'published' AND deleted_flag = FALSE) WHERE code = #{code}")
    int refreshPluginCount(@Param("code") String code);
}
