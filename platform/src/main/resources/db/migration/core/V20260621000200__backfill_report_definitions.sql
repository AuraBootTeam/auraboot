-- Phase 4 "report storage graduation" — slice 3: one-time, idempotent BACKFILL.
--
-- Copies every existing low-code report that still lives as a page-schema shell
-- (ab_page_schema with kind='list', profile='report', extension.reportDsl) into the
-- first-class ab_report table, keyed by the SAME pid as the page. After this runs,
-- every report is present in ab_report, so the slice 2b-1 dual-write and the
-- slice 2b-2 read fallback become belt-and-suspenders and slice 4 can drop them.
--
-- IDEMPOTENT: re-running changes nothing — the insert is gated by NOT EXISTS on the
-- target pid, so a second run inserts zero rows and never raises a duplicate key.
--
-- NON-DESTRUCTIVE: a report that is ALREADY in ab_report (e.g. one the dual-write
-- already synced after a fresh save) is skipped entirely — its dsl/title/status are
-- left untouched. The page-schema row is also left untouched (this is a copy, not a
-- move — the page shell is removed in a later slice once reads no longer need it).
--
-- ID STRATEGY (snowflake-collision-safe):
--   ab_report.id is normally minted by the app via MyBatis-Plus IdType.ASSIGN_ID
--   (a Snowflake: epoch 2010-11-04, ~19-digit LARGE POSITIVE time-based longs, today
--   already ~1.9e18 and only ever growing). We mint each backfilled id as the NEGATION
--   of the page's ab_page_schema.id (a small positive BIGSERIAL primary key): id = -p.id.
--   A negative value can NEVER equal a positive Snowflake, so there is zero risk of
--   collision with any app-minted id, now or in the future. It is also deterministic
--   (page id is a PK -> unique, stable) which keeps the backfill clean to reason about.
--   We deliberately do NOT use MAX(id)+row_number(): real ab_report ids are Snowflakes
--   in the ~1e18 range, so MAX(id)+n would land right next to live ids and could collide
--   with the next app insert. The negative space is provably disjoint instead.
--
-- WHERE THE reportDsl LIVES:
--   The shell is written through PageSchemaService (the /api/pages controller path the
--   report designer save uses), which routes the posted extension map through
--   ExtensionConverter.toBean -> ExtensionBean.extension, serialized by the Jackson
--   type handler as NESTED json: extension -> 'extension' -> 'reportDsl'. The flat
--   form (extension -> 'reportDsl', via @JsonAnySetter/dynamicProperties) is also
--   readable by the runtime fallback (ExtensionBean.get checks nested then flat), so we
--   COALESCE both forms to be sure we never miss a backfill-able report.
--
-- RUNS ON: Flyway-based environments only (prod deploy, reset-db). The host-first golden
-- harness builds its DB from database/schema.sql (NOT Flyway), so this data migration does
-- not run there — that is fine, a fresh harness has no legacy page-schema reports to copy.
-- Coverage is proven by ReportBackfillIT against the real Flyway/test DB.

INSERT INTO ab_report (
    id,
    pid,
    tenant_id,
    code,
    title,
    profile,
    dsl,
    status,
    version,
    created_by,
    created_at,
    updated_by,
    updated_at,
    deleted_flag
)
SELECT
    -p.id                                                     AS id,
    p.pid                                                     AS pid,
    p.tenant_id                                               AS tenant_id,
    p.page_key                                                AS code,
    -- title VARCHAR(255): prefer the reportDsl title (what the dual-write copies), then the
    -- localized page title (zh-CN > en > first value), then the page name — truncate to fit.
    LEFT(
        COALESCE(
            NULLIF(BTRIM(COALESCE(
                p.extension #>> '{extension,reportDsl,title}',
                p.extension #>> '{reportDsl,title}'
            )), ''),
            NULLIF(BTRIM(COALESCE(
                p.title #>> '{zh-CN}',
                p.title #>> '{en}'
            )), ''),
            p.name
        ),
        255
    )                                                         AS title,
    'paged-media'                                             AS profile,
    -- the exact ReportDsl json blob (nested form first, then flat form)
    COALESCE(
        p.extension #> '{extension,reportDsl}',
        p.extension #> '{reportDsl}'
    )                                                         AS dsl,
    -- carry a meaningful status from the page when it is published, else draft
    CASE WHEN p.status = 'published' THEN 'published' ELSE 'draft' END AS status,
    1                                                         AS version,
    p.created_by                                              AS created_by,
    p.created_at                                              AS created_at,
    p.updated_by                                              AS updated_by,
    p.updated_at                                              AS updated_at,
    FALSE                                                     AS deleted_flag
FROM ab_page_schema p
WHERE p.kind = 'list'
  AND p.profile = 'report'
  AND p.deleted_flag = FALSE
  AND p.is_current = TRUE
  -- has a reportDsl in either the nested or the flat extension form
  AND (
        (p.extension #> '{extension,reportDsl}') IS NOT NULL
        OR (p.extension #> '{reportDsl}') IS NOT NULL
      )
  -- idempotent + non-overwriting: skip any report already present in ab_report by pid
  AND NOT EXISTS (
        SELECT 1 FROM ab_report r WHERE r.pid = p.pid
      );
