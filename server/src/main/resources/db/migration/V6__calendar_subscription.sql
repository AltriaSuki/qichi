-- 只读日历订阅链接的随机凭证；重置时旧链接立即失效。
ALTER TABLE rooms ADD COLUMN ics_token text;
ALTER TABLE rooms ADD CONSTRAINT rooms_ics_token_format
    CHECK (ics_token IS NULL OR ics_token ~ '^[A-Za-z0-9_-]{43}$');
CREATE UNIQUE INDEX rooms_ics_token_key ON rooms (ics_token) WHERE ics_token IS NOT NULL;
