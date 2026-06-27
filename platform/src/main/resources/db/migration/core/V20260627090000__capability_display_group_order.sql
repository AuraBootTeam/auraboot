-- R5-fix: persist a declared capability's group display order so the v2 permission
-- page can sort an admin/org capability group without relying on an underlying
-- permission's displayGroupOrder extension. Without this column the field was
-- dropped on import (toRecord/toDto ignored it) and every declared group fell to
-- the 10000 floor, burying 组织与权限管理 below the convention-derived model noise.
ALTER TABLE ab_permission_capability ADD COLUMN IF NOT EXISTS display_group_order INTEGER;

COMMENT ON COLUMN ab_permission_capability.display_group_order IS 'Group display order for the v2 permission page (declaration-level; permission-extension group order still wins when present; null -> 10000 floor)';
