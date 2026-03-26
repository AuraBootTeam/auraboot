package com.auraboot.framework.plugin.marketplace.mapper;

import com.auraboot.framework.plugin.marketplace.entity.MarketplaceVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface MarketplaceVersionMapper extends BaseMapper<MarketplaceVersion> {

    @Select("SELECT * FROM ab_marketplace_version WHERE pid = #{pid}")
    MarketplaceVersion findByPid(@Param("pid") String pid);

    @Select("SELECT * FROM ab_marketplace_version WHERE marketplace_plugin_pid = #{pluginPid} ORDER BY version_major DESC, version_minor DESC, version_patch DESC")
    List<MarketplaceVersion> findByPluginPid(@Param("pluginPid") String pluginPid);

    @Select("SELECT * FROM ab_marketplace_version WHERE marketplace_plugin_pid = #{pluginPid} AND version = #{version}")
    MarketplaceVersion findByPluginPidAndVersion(@Param("pluginPid") String pluginPid, @Param("version") String version);

    @Select("SELECT * FROM ab_marketplace_version WHERE marketplace_plugin_pid = #{pluginPid} AND status = 'published' ORDER BY version_major DESC, version_minor DESC, version_patch DESC LIMIT 1")
    MarketplaceVersion findLatestPublished(@Param("pluginPid") String pluginPid);
}
