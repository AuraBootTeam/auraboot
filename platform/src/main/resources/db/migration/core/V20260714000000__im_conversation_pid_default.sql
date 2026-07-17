-- Follow-up to V20260713000000, which added ab_im_conversation.pid as a bare NOT NULL.
--
-- The table is inserted from paths that know nothing about pid, and MyBatis-Plus omits null fields
-- from the INSERT, so those inserts hit the NOT NULL constraint instead of getting a pid. This is
-- not hypothetical: ImConversationListByUserIntegrationTest.seedConversation calls
-- conversationMapper.insert() on an entity whose pid is unset, which against the current schema
-- fails with "null value in column pid violates not-null constraint".
--
-- A generating DEFAULT closes that without asking every caller to remember: application inserts
-- that set pid explicitly (UniqueIdGenerator ULID) keep their value, and every other insert path
-- gets a unique pid. Same 26-char truncated-uuid shape V20260713000000 used to backfill, and the
-- same shape ab_record_comment.pid has carried since V20260624021000.
--
-- V20260713000000 is not edited: it has already run in environments that took it, and a Flyway
-- migration's checksum is a promise.

ALTER TABLE ab_im_conversation
    ALTER COLUMN pid SET DEFAULT SUBSTRING(REPLACE(gen_random_uuid()::text, '-', ''), 1, 26);
