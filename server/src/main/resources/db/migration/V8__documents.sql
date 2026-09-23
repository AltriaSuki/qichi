-- P5-01：共同写作。文稿是同步实体；版本不可变、只插入，正文不进同步。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document'
));

CREATE TABLE documents (
    id               uuid PRIMARY KEY,
    room_id          uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    title            text        NOT NULL,
    created_by       uuid        NOT NULL REFERENCES users (id),
    latest_version   integer     NOT NULL DEFAULT 0,
    latest_author_id uuid REFERENCES users (id),
    char_count       integer     NOT NULL DEFAULT 0,
    seq              bigint      NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    deleted_by       uuid REFERENCES users (id),
    CONSTRAINT documents_title_len CHECK (char_length(title) BETWEEN 1 AND 100),
    CONSTRAINT documents_latest_version_nonneg CHECK (latest_version >= 0)
);
CREATE INDEX documents_room_seq_idx ON documents (room_id, seq);

CREATE TABLE document_versions (
    id                    uuid PRIMARY KEY,
    document_id           uuid        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version               integer     NOT NULL,
    base_version          integer     NOT NULL,
    author_id             uuid        NOT NULL REFERENCES users (id),
    body                  text        NOT NULL,
    char_count            integer     NOT NULL,
    restored_from_version integer,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_versions_unique UNIQUE (document_id, version),
    CONSTRAINT document_versions_version_pos CHECK (version >= 1),
    CONSTRAINT document_versions_body_len CHECK (char_length(body) <= 200000)
);
