-- P4-05：计划、阶段、里程碑与不可变过程记录。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log'
));

CREATE TABLE plans (
    id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    deleted_at timestamptz, deleted_by uuid REFERENCES users(id),
    title text NOT NULL, owner_id uuid NOT NULL REFERENCES users(id),
    status text NOT NULL DEFAULT 'active', target_date date,
    next_step text, next_step_owner_id uuid REFERENCES users(id), next_step_due date,
    completed_at timestamptz, completion_note text,
    CONSTRAINT plans_title_len CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT plans_status CHECK (status IN ('active', 'done', 'archived')),
    CONSTRAINT plans_step_len CHECK (next_step IS NULL OR char_length(next_step) BETWEEN 1 AND 1000),
    CONSTRAINT plans_completion_len CHECK (completion_note IS NULL OR char_length(completion_note) BETWEEN 1 AND 10000)
);
CREATE INDEX plans_room_idx ON plans(room_id, status) WHERE deleted_at IS NULL;

CREATE TABLE plan_stages (
    id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    deleted_at timestamptz, deleted_by uuid REFERENCES users(id),
    plan_id uuid NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    title text NOT NULL, sort_order integer NOT NULL, done_at timestamptz,
    CONSTRAINT plan_stages_title_len CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT plan_stages_sort_order CHECK (sort_order >= 0)
);
CREATE INDEX plan_stages_plan_idx ON plan_stages(plan_id, sort_order) WHERE deleted_at IS NULL;

CREATE TABLE milestones (
    id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    deleted_at timestamptz, deleted_by uuid REFERENCES users(id),
    plan_id uuid NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    title text NOT NULL, target_date date, done_at timestamptz,
    CONSTRAINT milestones_title_len CHECK (char_length(title) BETWEEN 1 AND 200)
);
CREATE INDEX milestones_plan_idx ON milestones(plan_id, target_date) WHERE deleted_at IS NULL;

CREATE TABLE plan_logs (
    id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    deleted_at timestamptz, deleted_by uuid REFERENCES users(id),
    plan_id uuid NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    author_id uuid NOT NULL REFERENCES users(id), body text NOT NULL,
    CONSTRAINT plan_logs_body_len CHECK (char_length(body) BETWEEN 1 AND 10000)
);
CREATE INDEX plan_logs_plan_idx ON plan_logs(plan_id, created_at DESC);
