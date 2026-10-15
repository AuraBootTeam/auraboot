-- Soft-delete flags must be BOOLEAN NOT NULL: SoftDeleteColumnInitializer (see #2277's
-- corrected scan) fails closed on nullable flags. The June baseline and several later
-- migrations created these static-schema columns without NOT NULL. Backfill, pin the
-- default, then enforce. mt_* dynamic tables are excluded on purpose: the initializer
-- provisions them with BOOLEAN NOT NULL DEFAULT FALSE at model publication.
-- Generated from the deployed schema (information_schema), 2026-10-15.

UPDATE public.ab_agent_artifact SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_artifact ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_artifact ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_batch_job SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_batch_job ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_batch_job ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_capability SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_capability ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_capability ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_definition SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_definition ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_definition ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_eval_case SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_eval_case ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_eval_case ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_mcp_server SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_mcp_server ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_mcp_server ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_memory SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_memory ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_memory ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_schedule SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_schedule ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_schedule ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_skill SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_skill ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_skill ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_task SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_task ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_task ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_tool SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_tool ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_tool ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_agent_user_profile SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_agent_user_profile ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_agent_user_profile ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_announcement SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_announcement ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_announcement ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_approval_policy SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_approval_policy ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_approval_policy ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_capability SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_capability ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_capability ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_category SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_category ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_category ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_cloud_config SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_cloud_config ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_cloud_config ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_data_domain SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_data_domain ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_data_domain ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_edi_message_type SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_edi_message_type ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_edi_message_type ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_edi_partner SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_edi_partner ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_edi_partner ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_email_account SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_email_account ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_email_account ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_email_sequence SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_email_sequence ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_email_sequence ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_file SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_file ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_file ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_file_relation SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_file_relation ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_file_relation ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_kb_document SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_kb_document ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_kb_document ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_knowledge_base SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_knowledge_base ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_knowledge_base ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_marketplace_plugin SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_marketplace_plugin ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_marketplace_plugin ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_marketplace_solution SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_marketplace_solution ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_marketplace_solution ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_notification_rule SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_notification_rule ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_notification_rule ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_object_alias SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_object_alias ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_object_alias ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_ot_device SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_ot_device ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_ot_device ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_plugin_import_log SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_plugin_import_log ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_plugin_import_log ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_push_device_token SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_push_device_token ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_push_device_token ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_reconciliation_profile SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_reconciliation_profile ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_reconciliation_profile ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_semantic_term SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_semantic_term ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_semantic_term ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_sod_rule SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_sod_rule ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_sod_rule ALTER COLUMN deleted_flag SET NOT NULL;

UPDATE public.ab_user SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_user ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_user ALTER COLUMN deleted_flag SET NOT NULL;
