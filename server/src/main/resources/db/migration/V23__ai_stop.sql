-- P10-04：问 AI 可以中途停下；停下的回答标记出来（两台手机看到同一条「已停下」）。
ALTER TABLE messages ADD COLUMN ai_stopped boolean NOT NULL DEFAULT false;
