-- 2026-04-15: Remove V2 blocks kind=dashboard pages and seed OSS dashboards into ab_dashboard
--
-- Background: ab_page_schema kind='dashboard' is no longer valid (kind enum restricted to
-- list/form/detail). OSS showcase dashboards are migrated to the dedicated ab_dashboard table.
--
-- Column notes (actual schema):
--   pid         VARCHAR(26) UNIQUE NOT NULL  -- ULID-style identifier
--   tenant_id   BIGINT NOT NULL              -- use 1 (default OSS tenant)
--   title       VARCHAR(200) NOT NULL        -- plain string, NOT JSONB
--   scope       VARCHAR(20)  CHECK IN ('personal','team','global','workbench')
--   status      VARCHAR(20)  CHECK IN ('draft','published')
--   code        unique per (tenant_id, code) where deleted_flag=false

DELETE FROM ab_page_schema WHERE kind = 'dashboard';

-- Seed 3 OSS showcase dashboards into ab_dashboard.
-- pid values are deterministic short ULIDs safe for OSS seed data.
-- Widgets are empty arrays initially; real widget config is populated via
-- plugin-import pipeline or manual seed scripts.
INSERT INTO ab_dashboard (
    pid, tenant_id, code, title, description,
    layout_config, widgets,
    scope, status,
    created_at, updated_at
) VALUES
    ('01HZOSS001ARSENAL000001',
     1,
     'sc_arsenal_dashboard',
     'Showcase Arsenal Dashboard',
     'Showcase arsenal demo dashboard',
     '{"columns":12,"rowHeight":80,"gap":12}'::jsonb,
     '[]'::jsonb,
     'global', 'published',
     NOW(), NOW()),

    ('01HZOSS001WORKFLOW000002',
     1,
     'sc_workflow_dashboard',
     'Showcase Workflow Dashboard',
     'Showcase workflow demo dashboard',
     '{"columns":12,"rowHeight":80,"gap":12}'::jsonb,
     '[]'::jsonb,
     'global', 'published',
     NOW(), NOW()),

    ('01HZOSS001ACSDASHB000003',
     1,
     'acs_dashboard',
     'ACP Showcase Dashboard',
     'ACP showcase demo dashboard',
     '{"columns":12,"rowHeight":80,"gap":12}'::jsonb,
     '[]'::jsonb,
     'global', 'published',
     NOW(), NOW())
ON CONFLICT (pid) DO NOTHING;
