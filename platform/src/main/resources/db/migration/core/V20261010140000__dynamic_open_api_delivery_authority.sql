-- Private source context is never included in the HTTP payload or public operations views.
ALTER TABLE ab_webhook_delivery_log ADD COLUMN authorization_context TEXT;
COMMENT ON COLUMN ab_webhook_delivery_log.authorization_context IS
    'Server-owned immutable source event used to recheck current installation authority before each send';
