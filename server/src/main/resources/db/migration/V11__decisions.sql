-- P6-02：决定记录。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item', 'decision'
));

CREATE TABLE decisions (
    id           uuid PRIMARY KEY,
    room_id      uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    question     text        NOT NULL,
    options      jsonb       NOT NULL DEFAULT '[]',
    concerns     jsonb       NOT NULL DEFAULT '[]',
    final_choice text,
    decided_at   timestamptz,
    decided_by   uuid REFERENCES users (id),
    review_date  date,
    created_by   uuid        NOT NULL REFERENCES users (id),
    seq          bigint      NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    deleted_by   uuid REFERENCES users (id),
    CONSTRAINT decisions_question_len CHECK (char_length(question) BETWEEN 1 AND 200)
);
CREATE INDEX decisions_room_seq_idx ON decisions (room_id, seq);
