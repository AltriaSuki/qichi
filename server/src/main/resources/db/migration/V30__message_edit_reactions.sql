-- P16-05：自己发的文字消息 24 小时内可以改（显示「已编辑」，改前的不保留）；
-- 每人可以给一条消息一个回应（喜欢 / 拥抱 / 支持），存在消息上：{"用户 id": "like"}。
ALTER TABLE messages ADD COLUMN edited_at timestamptz;
ALTER TABLE messages ADD COLUMN reactions jsonb NOT NULL DEFAULT '{}'::jsonb;
