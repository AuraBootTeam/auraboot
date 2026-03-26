package com.auraboot.framework.plugin.marketplace.mapper;

import com.auraboot.framework.plugin.marketplace.entity.MarketplaceInstall;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface MarketplaceInstallMapper extends BaseMapper<MarketplaceInstall> {

    @Select("SELECT * FROM ab_marketplace_install WHERE tenant_id = #{tenantId} ORDER BY installed_at DESC")
    List<MarketplaceInstall> findByTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_marketplace_install WHERE tenant_id = #{tenantId} AND marketplace_plugin_pid = #{pluginPid}")
    MarketplaceInstall findByTenantAndPlugin(@Param("tenantId") Long tenantId, @Param("pluginPid") String pluginPid);

    @Update("UPDATE ab_marketplace_install SET marketplace_version_pid = #{versionPid}, " +
            "installed_version = #{version}, updated_at = NOW() " +
            "WHERE tenant_id = #{tenantId} AND marketplace_plugin_pid = #{pluginPid}")
    void updateInstalledVersion(@Param("tenantId") Long tenantId,
                                @Param("pluginPid") String pluginPid,
                                @Param("versionPid") String versionPid,
                                @Param("version") String version);

    @Update("UPDATE ab_marketplace_install SET last_notified_version = #{version} " +
            "WHERE tenant_id = #{tenantId} AND marketplace_plugin_pid = #{pluginPid}")
    void updateLastNotifiedVersion(@Param("tenantId") Long tenantId,
                                   @Param("pluginPid") String pluginPid,
                                   @Param("version") String version);

    @Select("SELECT DISTINCT tenant_id FROM ab_marketplace_install")
    List<Long> findAllTenantIds();

    @Select("SELECT mi.* FROM ab_marketplace_install mi " +
            "JOIN ab_marketplace_plugin mp ON mi.marketplace_plugin_pid = mp.pid " +
            "WHERE mi.tenant_id = #{tenantId} " +
            "AND mi.installed_version != mp.latest_version " +
            "AND (mp.deleted_flag = FALSE OR mp.deleted_flag IS NULL)")
    List<MarketplaceInstall> findUpgradable(@Param("tenantId") Long tenantId);
}
