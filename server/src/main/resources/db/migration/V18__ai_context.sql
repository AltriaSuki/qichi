-- AI 生活助手第一步：AI 能看什么（每人一份），问 AI 的回答带上引用的房间资料。
ALTER TABLE users ADD COLUMN ai_prefs jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE messages ADD COLUMN ai_sources jsonb NOT NULL DEFAULT '[]'::jsonb;
