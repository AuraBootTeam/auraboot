package com.auraboot.framework.menu.service;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.tenant.service.UserSpaceService;
import java.util.ArrayList;
import java.util.List;

/** Backend-owned system navigation; opening a link never grants application authority. */
@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class PlatformManagementNavigation {
    private final UserSpaceService spaces;
    public List<Menu> append(List<Menu> business, Long userId, Long tenantId) {
        if (tenantId == null || userId == null) return business;
        List<Menu> children = new ArrayList<>();
        boolean platform = spaces.listForUser(userId).stream().anyMatch(space ->
                "platform".equals(space.getSpaceType()) && space.getRoleCodes().contains("platform_admin"));
        if (platform) {
            Menu console = entry("platform_console_entry", "Platform Console", "header.platform_console", "/platform/plugins", "settings");
            ExtensionBean extension = new ExtensionBean(); extension.setDynamicProperty("navigationOpening", "platform");
            console.setExtension(extension); children.add(console);
        }
        Menu open = entry("open_platform_entry", "Open Platform", "openPlatform.title", "/settings/api-docs", "code");
        open.setChildren(List.of(
                entry("open_platform_applications", "Applications", "openPlatform.applications", "/settings/api-docs?view=applications", "grid"),
                entry("open_platform_calls", "API call logs", "openPlatform.callLogs", "/settings/api-docs?view=calls", "list"),
                entry("open_platform_deliveries", "Webhook deliveries", "openPlatform.webhookDeliveries", "/settings/api-docs?view=webhooks", "send")));
        children.add(open);
        Menu group = entry("platform_management_navigation", "Platform management", "sidebar.platformManagement", "", "settings");
        group.setType(0); group.setOrderNo(10000); group.setChildren(children);
        List<Menu> result = new ArrayList<>(business); result.add(group); return result;
    }
    private Menu entry(String code, String name, String key, String path, String icon) {
        Menu item = new Menu(); item.setPid("system-nav-" + code); item.setCode(code); item.setName(name);
        item.setI18nKey(key); item.setPath(path); item.setIcon(icon); item.setType(1); return item;
    }
}
