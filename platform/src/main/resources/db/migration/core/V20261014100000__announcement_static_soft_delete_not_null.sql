-- Complete the migration-owned OSS plugin soft-delete schema contract.
UPDATE public.ab_announcement SET deleted_flag = FALSE WHERE deleted_flag IS NULL;
ALTER TABLE public.ab_announcement ALTER COLUMN deleted_flag SET DEFAULT FALSE;
ALTER TABLE public.ab_announcement ALTER COLUMN deleted_flag SET NOT NULL;
