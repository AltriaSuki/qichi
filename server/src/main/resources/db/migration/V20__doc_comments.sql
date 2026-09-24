-- P9-03：文稿段落旁的留言（同步实体 doc_comment）。开头钉在 quote 上，回复带 parent_id；开头可以删进回收站。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item', 'decision', 'book', 'reading_progress', 'highlight',
    'summary', 'review_document', 'review_version', 'annotation', 'annotation_reply', 'ai_finding', 'ai_action', 'doc_comment'
));

CREATE TABLE doc_comments (
    id          uuid PRIMARY KEY,
    room_id     uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    document_id uuid        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    parent_id   uuid REFERENCES doc_comments (id) ON DELETE CASCADE,
    author_id   uuid        NOT NULL REFERENCES users (id),
    body        text        NOT NULL,
    quote       text,
    version     integer,
    resolved_at timestamptz,
    resolved_by uuid REFERENCES users (id),
    seq         bigint      NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    deleted_by  uuid REFERENCES users (id),
    CONSTRAINT doc_comments_body_len CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT doc_comments_quote_len CHECK (quote IS NULL OR char_length(quote) BETWEEN 1 AND 200),
    -- 开头一定钉在原文上，回复一定不钉
    CONSTRAINT doc_comments_anchor CHECK ((parent_id IS NULL) = (quote IS NOT NULL))
);
CREATE INDEX doc_comments_room_seq_idx ON doc_comments (room_id, seq);
CREATE INDEX doc_comments_document_idx ON doc_comments (document_id);
