-- Seed conversations for the conversation → FAQ loop golden.
--
-- Two on purpose. The support thread is the happy path: real questions, real answers, so the
-- distiller should produce candidates from it. The chit-chat thread is the control: if
-- candidates appear from it, the model is inventing, and the review queue would be feeding
-- made-up answers into a customer-facing knowledge base. A golden that only seeds the happy
-- path cannot tell a working distiller from an eager one.
--
-- :tenant is bound by the caller (psql -v tenant=...).

\set ON_ERROR_STOP on

DELETE FROM ab_im_message WHERE conversation_id IN (
    SELECT id FROM ab_im_conversation WHERE pid IN ('faqseedsupport0000000001', 'faqseedchitchat0000000001')
);
DELETE FROM ab_im_conversation WHERE pid IN ('faqseedsupport0000000001', 'faqseedchitchat0000000001');

-- ---- 1. a real support thread: three questions, three answers ----------------------------
INSERT INTO ab_im_conversation (pid, tenant_id, type, name, owner_id, max_seq, created_at, updated_at)
VALUES ('faqseedsupport0000000001', :tenant, 'group', '客服会话 — 退款与发票', 1, 6, NOW() - INTERVAL '2 hours', NOW());
UPDATE ab_im_conversation SET last_message_at = NOW() - INTERVAL '90 minutes' WHERE pid = 'faqseedsupport0000000001';

INSERT INTO ab_im_message (conversation_id, tenant_id, sender_id, sender_type, seq, message_type, content, created_at)
SELECT c.id, :tenant, s.sender_id, s.sender_type, s.seq, 'text', s.content, NOW()
FROM ab_im_conversation c,
     (VALUES
        (1, 1::bigint, 'human', '你好，我上周申请了退款，请问多久能到账？'),
        (2, 0::bigint, 'agent', '您好！退款审核通过后我们会在 1 个工作日内提交银行，银行入账通常需要 3-5 个工作日。'),
        (3, 1::bigint, 'human', '好的。另外发票可以重新开吗？抬头写错了。'),
        (4, 0::bigint, 'agent', '可以的。发票开具后 30 天内支持换开一次，请在「我的发票」里点击「申请换开」并填写正确抬头，我们会在 2 个工作日内重新开具。'),
        (5, 1::bigint, 'human', '明白了，谢谢！'),
        (6, 0::bigint, 'agent', '不客气，还有其他问题随时联系我们。')
     ) AS s(seq, sender_id, sender_type, content)
WHERE c.pid = 'faqseedsupport0000000001';

-- ---- 2. the control: pleasantries, no question, nothing to distil ------------------------
INSERT INTO ab_im_conversation (pid, tenant_id, type, name, owner_id, max_seq, created_at, updated_at)
VALUES ('faqseedchitchat0000000001', :tenant, 'group', '客服会话 — 闲聊', 1, 4, NOW() - INTERVAL '5 hours', NOW());
UPDATE ab_im_conversation SET last_message_at = NOW() - INTERVAL '4 hours' WHERE pid = 'faqseedchitchat0000000001';

INSERT INTO ab_im_message (conversation_id, tenant_id, sender_id, sender_type, seq, message_type, content, created_at)
SELECT c.id, :tenant, s.sender_id, s.sender_type, s.seq, 'text', s.content, NOW()
FROM ab_im_conversation c,
     (VALUES
        (1, 1::bigint, 'human', '早上好'),
        (2, 0::bigint, 'agent', '早上好！有什么可以帮您的吗？'),
        (3, 1::bigint, 'human', '没事，随便看看，谢谢'),
        (4, 0::bigint, 'agent', '好的，祝您愉快！')
     ) AS s(seq, sender_id, sender_type, content)
WHERE c.pid = 'faqseedchitchat0000000001';

-- ---- 3. a second support thread, owned by the pages/menu golden ------------------------
-- The review golden works its two drafts down to nothing (edit, reject, approve+publish), so a
-- spec that runs after it would find an empty queue. This thread is its own material: it distils
-- from the queue UI like everything else, and nothing else touches it.
INSERT INTO ab_im_conversation (pid, tenant_id, type, name, owner_id, max_seq, created_at, updated_at)
VALUES ('faqseedsupport0000000002', :tenant, 'group', '客服会话 — 配送与保修', 1, 4, NOW() - INTERVAL '30 minutes', NOW());
UPDATE ab_im_conversation SET last_message_at = NOW() - INTERVAL '20 minutes' WHERE pid = 'faqseedsupport0000000002';

INSERT INTO ab_im_message (conversation_id, tenant_id, sender_id, sender_type, seq, message_type, content, created_at)
SELECT c.id, :tenant, s.sender_id, s.sender_type, s.seq, 'text', s.content, NOW()
FROM ab_im_conversation c,
     (VALUES
        (1, 1::bigint, 'human', '下单后一般多久发货？'),
        (2, 0::bigint, 'agent', '现货商品在您付款后 48 小时内发出，预售商品以商品页标注的发货时间为准。'),
        (3, 1::bigint, 'human', '保修期是多久？'),
        (4, 0::bigint, 'agent', '整机保修 12 个月，配件保修 6 个月，自签收之日起算。')
     ) AS s(seq, sender_id, sender_type, content)
WHERE c.pid = 'faqseedsupport0000000002';

SELECT pid, name, max_seq FROM ab_im_conversation WHERE pid LIKE 'faqseed%' ORDER BY pid;
