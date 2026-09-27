-- P14-04：AI 可以提议计划相关的记录（新建计划、加阶段、加里程碑、记一笔进展、设下一步），照旧点「好」才记下。
ALTER TABLE ai_actions DROP CONSTRAINT ai_actions_kind_check;
ALTER TABLE ai_actions ADD CONSTRAINT ai_actions_kind_check CHECK (kind IN (
    'event', 'todo', 'archive_item', 'idea', 'plan', 'plan_stage', 'milestone', 'plan_log', 'next_step'
));
