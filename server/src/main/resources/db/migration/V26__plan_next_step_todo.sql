-- P14-03：计划的下一步可以直接用计划里的一件待办。连着时下一步的内容、谁来做、截止跟着那件待办；
-- 它做完或删掉时服务端把下一步清空（写变化）。彻底删除待办前已经断开，外键置空只是兜底。
ALTER TABLE plans ADD COLUMN next_step_todo_id uuid REFERENCES todos (id) ON DELETE SET NULL;

CREATE INDEX plans_next_step_todo_idx ON plans (next_step_todo_id) WHERE next_step_todo_id IS NOT NULL;
