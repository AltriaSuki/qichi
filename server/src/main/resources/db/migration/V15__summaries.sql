-- P6-06：总结与年度回顾（AI 派生）。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item', 'decision', 'book', 'reading_progress', 'highlight', 'summary'
));

CREATE TABLE summaries (
    id           uuid PRIMARY KEY,
    room_id      uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    kind         text        NOT NULL CHECK (kind IN ('week', 'month', 'custom', 'year')),
    range_start  date        NOT NULL,
    range_end    date        NOT NULL,
    body         text        NOT NULL,
    sources      jsonb       NOT NULL DEFAULT '[]',
    ai_derived   boolean     NOT NULL DEFAULT true,
    locked       boolean     NOT NULL DEFAULT false,
    requested_by uuid REFERENCES users (id),
    seq          bigint      NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    deleted_by   uuid REFERENCES users (id),
    CONSTRAINT summaries_range CHECK (range_start <= range_end)
);
CREATE INDEX summaries_room_seq_idx ON summaries (room_id, seq);
-- 每个房间每年只有一份年度回顾
CREATE UNIQUE INDEX summaries_year_uq ON summaries (room_id, range_start) WHERE kind = 'year';
