-- ───────────────────────── 待办挂到计划 ─────────────────────────

ALTER TABLE todos
    ADD COLUMN plan_id uuid REFERENCES plans (id) ON DELETE SET NULL;

CREATE INDEX todos_plan_idx ON todos (plan_id) WHERE plan_id IS NOT NULL;
