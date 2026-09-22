-- P4-03：每日一问。所有实体都有房间 seq，答案揭晓前按作者过滤。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer'
));

CREATE TABLE questions (
    id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    deleted_at timestamptz, deleted_by uuid REFERENCES users(id),
    text text NOT NULL, source text NOT NULL,
    created_by uuid REFERENCES users(id), suggested_by_job_id uuid REFERENCES ai_jobs(id),
    adopted_by uuid REFERENCES users(id), adopted_at timestamptz,
    CONSTRAINT questions_text_len CHECK (char_length(text) BETWEEN 1 AND 500),
    CONSTRAINT questions_source CHECK (source IN ('ai', 'user', 'preset'))
);
CREATE INDEX questions_room_idx ON questions(room_id, adopted_at) WHERE deleted_at IS NULL;

CREATE TABLE qna_rounds (
    id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    deleted_at timestamptz, deleted_by uuid REFERENCES users(id),
    question_id uuid NOT NULL REFERENCES questions(id), round_date date NOT NULL,
    revealed_at timestamptz,
    UNIQUE(room_id, round_date)
);
CREATE INDEX qna_rounds_question_idx ON qna_rounds(question_id, round_date DESC);

CREATE TABLE answers (
    id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    deleted_at timestamptz, deleted_by uuid REFERENCES users(id),
    round_id uuid NOT NULL REFERENCES qna_rounds(id), author_id uuid NOT NULL REFERENCES users(id),
    body text NOT NULL, confirmed_at timestamptz,
    UNIQUE(round_id, author_id),
    CONSTRAINT answers_body_len CHECK (char_length(body) BETWEEN 1 AND 10000)
);
CREATE INDEX answers_room_idx ON answers(room_id, round_id);
