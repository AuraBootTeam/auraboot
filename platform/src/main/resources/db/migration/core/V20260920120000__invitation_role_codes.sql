-- School join codes carry the roles a joining teacher receives. Roles come from
-- the invitation record, never from the client (self-escalation guard).
ALTER TABLE ab_invitation ADD COLUMN role_codes VARCHAR(200);
