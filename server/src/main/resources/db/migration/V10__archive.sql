-- P6-01：档案。条目是同步实体（带当前标题与正文）；修订不可变、只插入、不进同步。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item'
));

CREATE TABLE archive_items (
    id                uuid PRIMARY KEY,
    room_id           uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    kind              text        NOT NULL CHECK (kind IN ('preference', 'consensus', 'decision', 'boundary', 'concern', 'milestone')),
    title             text        NOT NULL,
    body              text        NOT NULL DEFAULT '',
    created_by        uuid        NOT NULL REFERENCES users (id),
    current_revision  integer     NOT NULL DEFAULT 1,
    revised_by        uuid        NOT NULL REFERENCES users (id),
    source_message_id uuid REFERENCES messages (id) ON DELETE SET NULL,
    seq               bigint      NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    deleted_by        uuid REFERENCES users (id),
    CONSTRAINT archive_items_title_len CHECK (char_length(title) BETWEEN 1 AND 80),
    CONSTRAINT archive_items_body_len CHECK (char_length(body) <= 5000)
);
CREATE INDEX archive_items_room_seq_idx ON archive_items (room_id, seq);

CREATE TABLE archive_revisions (
    id                uuid PRIMARY KEY,
    item_id           uuid        NOT NULL REFERENCES archive_items (id) ON DELETE CASCADE,
    revision          integer     NOT NULL,
    author_id         uuid        NOT NULL REFERENCES users (id),
    title             text        NOT NULL,
    body              text        NOT NULL,
    source_message_id uuid REFERENCES messages (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL,
    CONSTRAINT archive_revisions_unique UNIQUE (item_id, revision)
);
