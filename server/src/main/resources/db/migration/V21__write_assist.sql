-- P9-04 / P9-05：写作助手。结果是一段文字（只给发起的人看），存在 ai_jobs.result_text，不写进任何实体。
ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_kind;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_kind CHECK (kind IN (
    'chat_answer', 'question_suggest', 'read_explain', 'review_findings', 'summary', 'yearly_review', 'write_assist'
));
ALTER TABLE ai_jobs ADD COLUMN result_text text;
