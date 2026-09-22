-- 第 4 阶段 · AI 基础（P4-01）：任务队列、AI 调用记录；AI 回答消息带上提问。

-- 任务队列：后台协程用 SELECT … FOR UPDATE SKIP LOCKED 领取（docs/02-architecture.md「任务队列」）。
-- run_at 用于定时与重试退避；locked_at 超时的 running 任务在启动时放回队列。
CREATE TABLE jobs (
    id           uuid PRIMARY KEY,
    kind         text        NOT NULL,
    payload      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status       text        NOT NULL DEFAULT 'queued',
    run_at       timestamptz NOT NULL DEFAULT now(),
    attempts     integer     NOT NULL DEFAULT 0,
    max_attempts integer     NOT NULL DEFAULT 3,
    last_error   text,
    locked_at    timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT jobs_status CHECK (status IN ('queued', 'running', 'done', 'failed')),
    CONSTRAINT jobs_attempts CHECK (attempts >= 0 AND max_attempts >= 1)
);
CREATE INDEX jobs_ready_idx ON jobs (run_at) WHERE status = 'queued';

-- AI 调用记录：id 就是客户端生成的 jobId；按作者统计用量（「我发起的 AI 使用」），按月统计额度。
-- requested_by 为空表示系统发起（年度回顾）。result_ref 形如 "message:<uuid>"。
CREATE TABLE ai_jobs (
    id            uuid PRIMARY KEY,
    room_id       uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    requested_by  uuid REFERENCES users (id),
    kind          text        NOT NULL,
    status        text        NOT NULL DEFAULT 'queued',
    request       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    model         text,
    input_tokens  integer     NOT NULL DEFAULT 0,
    output_tokens integer     NOT NULL DEFAULT 0,
    result_ref    text,
    error         text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    CONSTRAINT ai_jobs_kind CHECK (kind IN ('chat_answer', 'question_suggest', 'read_explain', 'review_findings', 'summary', 'yearly_review')),
    CONSTRAINT ai_jobs_status CHECK (status IN ('queued', 'running', 'done', 'failed')),
    CONSTRAINT ai_jobs_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0)
);
CREATE INDEX ai_jobs_created_idx ON ai_jobs (created_at);
CREATE INDEX ai_jobs_requested_by_idx ON ai_jobs (requested_by, created_at);

-- AI 回答（kind = 'ai' 的消息）记下是回答哪个问题的，界面上显示在回答上方。
ALTER TABLE messages ADD COLUMN ai_prompt text;
ALTER TABLE messages ADD CONSTRAINT messages_ai_prompt_len CHECK (ai_prompt IS NULL OR char_length(ai_prompt) <= 2000);
