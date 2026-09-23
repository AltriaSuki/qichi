-- P4-09：灵感（随手记下的想法）。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea'
));

CREATE TABLE ideas (
    id         uuid PRIMARY KEY,
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    body       text        NOT NULL,
    seq        bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id),
    CONSTRAINT ideas_body_len CHECK (char_length(body) BETWEEN 1 AND 2000)
);
CREATE INDEX ideas_room_seq_idx ON ideas (room_id, seq);
