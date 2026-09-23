-- P7-01：审稿。审稿文件、不可变版本、批注、讨论是同步实体；预览页（图片 + 文字层）不走同步，按需取。
-- 原文件和渲染好的页面图片都在 files 里（kind = review）。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item', 'decision', 'book', 'reading_progress', 'highlight',
    'summary', 'review_document', 'review_version', 'annotation', 'annotation_reply'
));

CREATE TABLE review_documents (
    id             uuid PRIMARY KEY,
    room_id        uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    title          text        NOT NULL,
    created_by     uuid        NOT NULL REFERENCES users (id),
    latest_version integer     NOT NULL DEFAULT 1,
    seq            bigint      NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz,
    deleted_by     uuid REFERENCES users (id),
    CONSTRAINT review_documents_title_len CHECK (char_length(title) BETWEEN 1 AND 100)
);
CREATE INDEX review_documents_room_seq_idx ON review_documents (room_id, seq);

-- 版本：原文件不可变；只有预览相关的列会在后台生成完后更新
CREATE TABLE review_versions (
    id             uuid PRIMARY KEY,
    room_id        uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    document_id    uuid        NOT NULL REFERENCES review_documents (id) ON DELETE CASCADE,
    version        integer     NOT NULL,
    file_id        uuid        NOT NULL REFERENCES files (id),
    format         text        NOT NULL CHECK (format IN ('pdf', 'text', 'sheet', 'slides')),
    uploaded_by    uuid        NOT NULL REFERENCES users (id),
    preview_status text        NOT NULL DEFAULT 'pending' CHECK (preview_status IN ('pending', 'ready', 'failed')),
    page_count     integer,
    preview_error  text,
    seq            bigint      NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz,
    deleted_by     uuid REFERENCES users (id),
    CONSTRAINT review_versions_unique UNIQUE (document_id, version)
);
CREATE INDEX review_versions_room_seq_idx ON review_versions (room_id, seq);

CREATE TABLE review_pages (
    version_id    uuid             NOT NULL REFERENCES review_versions (id) ON DELETE CASCADE,
    page_no       integer          NOT NULL,
    width         double precision NOT NULL,
    height        double precision NOT NULL,
    image_file_id uuid             NOT NULL REFERENCES files (id),
    text_layer    jsonb            NOT NULL,
    images        jsonb            NOT NULL DEFAULT '[]',
    PRIMARY KEY (version_id, page_no)
);

CREATE TABLE annotations (
    id              uuid PRIMARY KEY,
    room_id         uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    document_id     uuid        NOT NULL REFERENCES review_documents (id) ON DELETE CASCADE,
    version_id      uuid        NOT NULL REFERENCES review_versions (id) ON DELETE CASCADE,
    anchor          jsonb       NOT NULL,
    author_id       uuid        NOT NULL REFERENCES users (id),
    kind            text        NOT NULL CHECK (kind IN ('comment', 'proposal')),
    status          text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'accepted', 'archived')),
    body            text        NOT NULL,
    carried_from_id uuid REFERENCES annotations (id) ON DELETE SET NULL,
    anchor_lost     boolean     NOT NULL DEFAULT false,
    resolved_by     uuid REFERENCES users (id),
    resolved_at     timestamptz,
    seq             bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    deleted_by      uuid REFERENCES users (id),
    CONSTRAINT annotations_body_len CHECK (char_length(body) BETWEEN 1 AND 2000)
);
CREATE INDEX annotations_room_seq_idx ON annotations (room_id, seq);
CREATE INDEX annotations_version_idx ON annotations (version_id);

CREATE TABLE annotation_replies (
    id            uuid PRIMARY KEY,
    room_id       uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    annotation_id uuid        NOT NULL REFERENCES annotations (id) ON DELETE CASCADE,
    author_id     uuid        NOT NULL REFERENCES users (id),
    body          text        NOT NULL,
    seq           bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    deleted_by    uuid REFERENCES users (id),
    CONSTRAINT annotation_replies_body_len CHECK (char_length(body) BETWEEN 1 AND 2000)
);
CREATE INDEX annotation_replies_room_seq_idx ON annotation_replies (room_id, seq);
CREATE INDEX annotation_replies_annotation_idx ON annotation_replies (annotation_id);
