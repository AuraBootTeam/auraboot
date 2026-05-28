-- Clean up APS V2 fixtures (by remark tag)

BEGIN;

DELETE FROM mt_pe_schedule_result WHERE pe_sched_work_order_id LIKE 'APS_FIX_%';
DELETE FROM mt_pe_resource_calendar WHERE pe_rc_remark = 'APS_FIXTURE';
DELETE FROM mt_pe_planned_order WHERE pe_plo_remark = 'APS_FIXTURE';
DELETE FROM mt_pe_resource WHERE pe_res_remark = 'APS_FIXTURE';

COMMIT;
