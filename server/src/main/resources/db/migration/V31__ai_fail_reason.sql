-- AI 任务失败的种类（P16-08）：quota / unreachable / provider / too_long / other，App 据此说清原因
ALTER TABLE ai_jobs ADD COLUMN fail_reason text
    CHECK (fail_reason IN ('quota', 'unreachable', 'provider', 'too_long', 'other'));
