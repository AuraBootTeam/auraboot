-- SavedView vNext compatibility backfill.
--
-- 1) Legacy plugin-imported SavedViews were GLOBAL rows without the newer
--    view_config.meta ownership envelope. Plugin imports historically did not
--    stamp owner/created_by, so those rows can be distinguished from manually
--    created global views.
-- 2) Advanced views created before strict capability validation may be missing
--    the required config fields. Mark them as blocked so the frontend can show
--    a capability diagnostic instead of failing silently.

UPDATE ab_saved_view
SET view_config = jsonb_set(
    COALESCE(view_config, '{}'::jsonb),
    '{meta}',
    jsonb_build_object(
        'viewKey',
        model_code || '::' || COALESCE(page_key, '') || '::' || name || '::' || COALESCE(view_type, 'table'),
        'managedBy',
        'plugin',
        'locked',
        true,
        'allowUserCopy',
        true,
        'allowUserOverride',
        true
    ),
    true
)
WHERE deleted_flag = false
  AND scope = 'global'
  AND (created_by IS NULL OR owner_id IS NULL)
  AND (view_config->'meta' IS NULL OR view_config->'meta' = 'null'::jsonb);

UPDATE ab_saved_view
SET view_config = jsonb_set(
    COALESCE(view_config, '{}'::jsonb),
    '{meta}',
    COALESCE(view_config->'meta', '{}'::jsonb)
      || jsonb_build_object('capabilityStatus', 'blocked'),
    true
)
WHERE deleted_flag = false
  AND COALESCE(view_config->'meta'->>'capabilityStatus', '') = ''
  AND (
      (
          LOWER(COALESCE(view_type, 'table')) = 'kanban'
          AND (
              COALESCE(view_config->>'groupByField', '') = ''
              OR COALESCE(view_config->>'titleField', '') = ''
          )
      )
      OR (
          LOWER(COALESCE(view_type, 'table')) = 'calendar'
          AND COALESCE(view_config->>'calendarDateField', '') = ''
      )
      OR (
          LOWER(COALESCE(view_type, 'table')) = 'gantt'
          AND (
              COALESCE(view_config->>'ganttStartDateField', '') = ''
              OR COALESCE(view_config->>'ganttEndDateField', '') = ''
          )
      )
  );

-- Existing tenants created before SavedView vNext may already have the split
-- saved-view permission rows but not the role grants introduced in
-- default-bootstrap.json. Keep this additive and role-scoped:
--   * viewer: can open dashboards and read/copy accessible SavedViews
--   * operator: can also create/save personal SavedViews through VIEW_MANAGE
-- Team/global write boundaries are still enforced by SavedViewServiceImpl.
INSERT INTO ab_role_permission (
    pid,
    tenant_id,
    role_id,
    permission_id,
    grant_type,
    status,
    deleted_flag,
    created_at,
    updated_at
)
SELECT
    'sv' || substr(md5(r.id::text || ':' || p.id::text), 1, 30),
    r.tenant_id,
    r.id,
    p.id,
    'grant',
    'active',
    FALSE,
    NOW(),
    NOW()
FROM ab_role r
JOIN ab_permission p
  ON p.tenant_id = r.tenant_id
 AND p.deleted_flag = FALSE
 AND p.status = 'active'
WHERE r.deleted_flag = FALSE
  AND r.status = 'active'
  AND (
      (
          r.code = 'viewer'
          AND p.code IN ('dashboard.read', 'dashboard.saved_view.read')
      )
      OR (
          r.code = 'operator'
          AND p.code IN (
              'dashboard.read',
              'dashboard.saved_view.read',
              'dashboard.saved_view.update'
          )
      )
  )
  AND NOT EXISTS (
      SELECT 1
      FROM ab_role_permission ex
      WHERE ex.tenant_id = r.tenant_id
        AND ex.role_id = r.id
        AND ex.permission_id = p.id
        AND ex.deleted_flag = FALSE
  );
