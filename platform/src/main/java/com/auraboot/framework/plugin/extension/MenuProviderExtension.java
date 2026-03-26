package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.List;
import java.util.Map;

/**
 * Extension point for dynamic menu providers.
 * Plugins can implement this interface to add menu items dynamically.
 *
 * Example usage:
 * <pre>
 * {@code
 * @Extension
 * public class BillingMenuProvider implements MenuProviderExtension {
 *     @Override
 *     public String getMenuGroup() {
 *         return "main-sidebar";
 *     }
 *
 *     @Override
 *     public List<MenuItem> getMenuItems(MenuContext context) {
 *         return List.of(
 *             MenuItem.builder()
 *                 .key("billing-invoices")
 *                 .label("Invoices")
 *                 .icon("receipt")
 *                 .path("/billing/invoices")
 *                 .order(100)
 *                 .build()
 *         );
 *     }
 * }
 * }
 * </pre>
 */
public interface MenuProviderExtension extends ExtensionPoint {

    /**
     * Get the menu group this provider contributes to.
     * Common groups: "main-sidebar", "header-menu", "user-menu", "settings-menu"
     *
     * @return menu group identifier
     */
    String getMenuGroup();

    /**
     * Get the menu items to add.
     *
     * @param context menu context containing user and tenant info
     * @return list of menu items
     */
    List<MenuItem> getMenuItems(MenuContext context);

    /**
     * Check if this provider should be active for the given context.
     * Default is true.
     *
     * @param context menu context
     * @return true if this provider should contribute menu items
     */
    default boolean isActive(MenuContext context) {
        return true;
    }

    /**
     * Get the order of this provider within the menu group.
     * Lower values appear first.
     * Default is 100.
     *
     * @return provider order
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Menu context containing user and tenant information.
     */
    record MenuContext(
            Long tenantId,
            String pluginId,
            String namespace,
            Long userId,
            List<String> userRoles,
            List<String> userPermissions,
            Map<String, Object> settings
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public boolean hasRole(String role) {
            return userRoles != null && userRoles.contains(role);
        }

        public boolean hasPermission(String permission) {
            return userPermissions != null && userPermissions.contains(permission);
        }

        public static class Builder {
            private Long tenantId;
            private String pluginId;
            private String namespace;
            private Long userId;
            private List<String> userRoles = List.of();
            private List<String> userPermissions = List.of();
            private Map<String, Object> settings = Map.of();

            public Builder tenantId(Long tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public Builder pluginId(String pluginId) {
                this.pluginId = pluginId;
                return this;
            }

            public Builder namespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            public Builder userId(Long userId) {
                this.userId = userId;
                return this;
            }

            public Builder userRoles(List<String> userRoles) {
                this.userRoles = userRoles;
                return this;
            }

            public Builder userPermissions(List<String> userPermissions) {
                this.userPermissions = userPermissions;
                return this;
            }

            public Builder settings(Map<String, Object> settings) {
                this.settings = settings;
                return this;
            }

            public MenuContext build() {
                return new MenuContext(tenantId, pluginId, namespace, userId, userRoles, userPermissions, settings);
            }
        }
    }

    /**
     * Menu item definition.
     */
    record MenuItem(
            String key,
            String label,
            String icon,
            String path,
            String target,
            int order,
            List<MenuItem> children,
            List<String> requiredRoles,
            List<String> requiredPermissions,
            Map<String, Object> metadata
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String key;
            private String label;
            private String icon;
            private String path;
            private String target = "_self";
            private int order = 100;
            private List<MenuItem> children = List.of();
            private List<String> requiredRoles = List.of();
            private List<String> requiredPermissions = List.of();
            private Map<String, Object> metadata = Map.of();

            public Builder key(String key) {
                this.key = key;
                return this;
            }

            public Builder label(String label) {
                this.label = label;
                return this;
            }

            public Builder icon(String icon) {
                this.icon = icon;
                return this;
            }

            public Builder path(String path) {
                this.path = path;
                return this;
            }

            public Builder target(String target) {
                this.target = target;
                return this;
            }

            public Builder order(int order) {
                this.order = order;
                return this;
            }

            public Builder children(List<MenuItem> children) {
                this.children = children;
                return this;
            }

            public Builder requiredRoles(List<String> requiredRoles) {
                this.requiredRoles = requiredRoles;
                return this;
            }

            public Builder requiredPermissions(List<String> requiredPermissions) {
                this.requiredPermissions = requiredPermissions;
                return this;
            }

            public Builder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            public MenuItem build() {
                return new MenuItem(key, label, icon, path, target, order, children, requiredRoles, requiredPermissions, metadata);
            }
        }
    }
}
