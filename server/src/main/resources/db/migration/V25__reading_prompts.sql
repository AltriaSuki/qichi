-- 阅读里的常用提示词（P14-05）：每人一套，存在账号上，对方看不到。
-- [{id, title, instruction}]，顺序即显示顺序；条数和长度的上限在 shared/rules/Limits.kt，服务端写入前检查。
ALTER TABLE users ADD COLUMN reading_prompts jsonb NOT NULL DEFAULT '[]'::jsonb;
