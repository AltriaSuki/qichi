-- P7-03：审稿 AI 的发现（同步实体 ai_finding）。只能忽略或转成人工批注，没有回收站。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item', 'decision', 'book', 'reading_progress', 'highlight',
    'summary', 'review_document', 'review_version', 'annotation', 'annotation_reply', 'ai_finding'
));

CREATE TABLE ai_findings (
    id                      uuid PRIMARY KEY,
    room_id                 uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    document_id             uuid        NOT NULL REFERENCES review_documents (id) ON DELETE CASCADE,
    version_id              uuid        NOT NULL REFERENCES review_versions (id) ON DELETE CASCADE,
    job_id                  uuid        NOT NULL,
    requested_by            uuid REFERENCES users (id),
    title                   text        NOT NULL,
    body                    text        NOT NULL DEFAULT '',
    evidence                jsonb       NOT NULL,
    status                  text        NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'dismissed', 'converted')),
    converted_annotation_id uuid REFERENCES annotations (id) ON DELETE SET NULL,
    carried_from_id         uuid REFERENCES ai_findings (id) ON DELETE SET NULL,
    gone_in_version         integer,
    resolved_by             uuid REFERENCES users (id),
    seq                     bigint      NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    deleted_at              timestamptz,
    deleted_by              uuid REFERENCES users (id)
);
CREATE INDEX ai_findings_room_seq_idx ON ai_findings (room_id, seq);
CREATE INDEX ai_findings_version_idx ON ai_findings (version_id);
