-- P10-04：问 AI 可以中途停下；停下的回答标记出来（两台手机看到同一条「已停下」）。
ALTER TABLE messages ADD COLUMN ai_stopped boolean NOT NULL DEFAULT false;
-- AI 回答是谁问的（显示「阿栖问：…」）；已有的回答从 ai_jobs 补上
ALTER TABLE messages ADD COLUMN ai_asked_by uuid REFERENCES users(id);
UPDATE messages m SET ai_asked_by = j.requested_by FROM ai_jobs j WHERE j.id = m.id AND m.kind = 'ai';
