-- Migration-owned agent tables opt into the platform soft-delete contract.
-- Historical NULL means not deleted; preserve all explicit deletion markers.
DO $$
DECLARE
    relation_name TEXT;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'ab_agent_artifact', 'ab_agent_definition', 'ab_agent_mcp_server',
        'ab_agent_schedule', 'ab_agent_skill', 'ab_agent_task',
        'ab_agent_tool', 'ab_approval_policy'
    ] LOOP
        EXECUTE format('UPDATE public.%I SET deleted_flag = FALSE WHERE deleted_flag IS NULL', relation_name);
        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN deleted_flag SET DEFAULT FALSE', relation_name);
        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN deleted_flag SET NOT NULL', relation_name);
    END LOOP;
END
$$;
