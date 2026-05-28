-- APS V2 Strategy Comparison Fixtures
-- Seeds 50 planned orders + 5 resources + 30-day calendar with mixed
-- urgency / due-date / quantity to exercise the 5 strategies meaningfully.
--
-- All rows tagged via pe_plo_remark / pe_res_remark = 'APS_FIXTURE'
-- (and pe_rc_remark for calendars) so clear.sql can wipe them cleanly.
--
-- Usage: psql "$PGURL" -f scripts/aps-fixtures/seed.sql
--
-- tenant_id is hardcoded to 1 (the default tenant in reset-and-init.sh).
-- For multi-tenant fixtures, copy this file and substitute the `1` literals.
-- Avoided psql `:tenant_id` because it does NOT substitute inside DO $$ ... $$
-- dollar-quoted blocks, which would cause inconsistent rows.

BEGIN;

-- ---- resources (5) ----

INSERT INTO mt_pe_resource (pid, tenant_id, created_at, updated_at,
  pe_res_code, pe_res_name, pe_res_type, pe_res_subtype, pe_res_status,
  pe_res_capacity_per_hour, pe_res_remark)
VALUES
  ('APS_FIX_RES_SMT_1',  1, NOW(), NOW(), 'SMT-01',  'SMT 贴片机 1', 'machine', 'smt',        'active', 50, 'APS_FIXTURE'),
  ('APS_FIX_RES_SMT_2',  1, NOW(), NOW(), 'SMT-02',  'SMT 贴片机 2', 'machine', 'smt',        'active', 45, 'APS_FIXTURE'),
  ('APS_FIX_RES_WAVE_1', 1, NOW(), NOW(), 'WAVE-01', '波峰焊',       'machine', 'soldering',  'active', 30, 'APS_FIXTURE'),
  ('APS_FIX_RES_AOI_1',  1, NOW(), NOW(), 'AOI-01',  'AOI 检测',     'machine', 'inspection', 'active', 80, 'APS_FIXTURE'),
  ('APS_FIX_RES_PKG_1',  1, NOW(), NOW(), 'PKG-01',  '包装线',       'line',    'packaging',  'active', 100, 'APS_FIXTURE')
ON CONFLICT (pid) DO UPDATE SET updated_at = NOW();

-- ---- calendars (5 resources × 30 days, Mon-Fri 08:00-16:00) ----

DO $$
DECLARE
  res_pid TEXT;
  d INT;
  cal_date DATE;
  cal_pid TEXT;
BEGIN
  FOR res_pid IN SELECT unnest(ARRAY[
    'APS_FIX_RES_SMT_1', 'APS_FIX_RES_SMT_2', 'APS_FIX_RES_WAVE_1',
    'APS_FIX_RES_AOI_1', 'APS_FIX_RES_PKG_1'])
  LOOP
    FOR d IN 0..29 LOOP
      cal_date := CURRENT_DATE + d;
      -- Skip weekends
      IF EXTRACT(DOW FROM cal_date) IN (0, 6) THEN
        CONTINUE;
      END IF;
      cal_pid := 'APS_FIX_CAL_' || res_pid || '_' || d;
      INSERT INTO mt_pe_resource_calendar (pid, tenant_id, created_at, updated_at,
        pe_rc_resource_id, pe_rc_date, pe_rc_start_time, pe_rc_end_time,
        pe_rc_shift, pe_rc_available_hours, pe_rc_is_holiday, pe_rc_remark)
      VALUES (cal_pid, 1, NOW(), NOW(),
        res_pid, cal_date, '08:00'::time, '16:00'::time,
        'day', 8, false, 'APS_FIXTURE')
      ON CONFLICT (pid) DO UPDATE SET updated_at = NOW();
    END LOOP;
  END LOOP;
END $$;

-- ---- planned orders (50) ----
-- Spread across:
--   priority: 10 firmed (urgent) + 40 planned
--   due dates: clustered around D+5 / D+15 / D+25 to expose bottlenecks
--   quantities: 5/10/20/50/100 to exercise capacity calculations
--   product names: 5 distinct part numbers to test product grouping

DO $$
DECLARE
  i INT;
  pid_val TEXT;
  status_val TEXT;
  due_offset INT;
  qty INT;
  product_idx INT;
  product_names TEXT[] := ARRAY['PCB-A board', 'PCB-B controller', 'PCB-C sensor', 'Module-D power', 'Module-E IO'];
  qtys INT[] := ARRAY[5, 10, 20, 50, 100];
BEGIN
  FOR i IN 1..50 LOOP
    pid_val := 'APS_FIX_PLO_' || lpad(i::text, 3, '0');
    -- 10 firmed (high priority) + 40 planned
    status_val := CASE WHEN i <= 10 THEN 'firmed' ELSE 'planned' END;
    -- Due dates: 1/3 each at D+5, D+15, D+25
    due_offset := CASE
      WHEN i % 3 = 0 THEN 5
      WHEN i % 3 = 1 THEN 15
      ELSE 25
    END;
    qty := qtys[(i % 5) + 1];
    product_idx := (i % 5) + 1;

    INSERT INTO mt_pe_planned_order (pid, tenant_id, created_at, updated_at,
      pe_plo_material_name, pe_plo_order_type, pe_plo_order_qty,
      pe_plo_order_date, pe_plo_need_date, pe_plo_lead_time_days,
      pe_plo_status, pe_plo_lot_sizing_policy, pe_plo_remark)
    VALUES (pid_val, 1, NOW(), NOW(),
      product_names[product_idx], 'production', qty,
      CURRENT_DATE::text, (CURRENT_DATE + due_offset)::text, 3,
      status_val, 'lot_for_lot', 'APS_FIXTURE')
    ON CONFLICT (pid) DO UPDATE SET updated_at = NOW();
  END LOOP;
END $$;

COMMIT;

-- ---- summary ----

SELECT 'resources' AS kind, COUNT(*) AS n FROM mt_pe_resource WHERE pe_res_remark = 'APS_FIXTURE'
UNION ALL
SELECT 'calendars', COUNT(*) FROM mt_pe_resource_calendar WHERE pe_rc_remark = 'APS_FIXTURE'
UNION ALL
SELECT 'planned_orders (firmed)', COUNT(*) FROM mt_pe_planned_order WHERE pe_plo_remark = 'APS_FIXTURE' AND pe_plo_status = 'firmed'
UNION ALL
SELECT 'planned_orders (planned)', COUNT(*) FROM mt_pe_planned_order WHERE pe_plo_remark = 'APS_FIXTURE' AND pe_plo_status = 'planned';
