-- P8-02：AI 提议、人确认（同步实体 ai_action）。人点「好」才由服务端建成真正的实体；没有回收站。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item', 'decision', 'book', 'reading_progress', 'highlight',
    'summary', 'review_document', 'review_version', 'annotation', 'annotation_reply', 'ai_finding', 'ai_action'
));

CREATE TABLE ai_actions (
    id           uuid PRIMARY KEY,
    room_id      uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    message_id   uuid        NOT NULL,                      -- AI 回答（= jobId）；不加外键，回答删了卡片也就不显示了
    position     integer     NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('event', 'todo', 'archive_item', 'idea')),
    draft        jsonb       NOT NULL,
    status       text        NOT NULL DEFAULT 'proposed' CHECK (status IN ('proposed', 'accepted', 'dismissed')),
    result_id    uuid,
    decided_by   uuid REFERENCES users (id),
    requested_by uuid REFERENCES users (id),
    seq          bigint      NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    deleted_by   uuid REFERENCES users (id)
);
CREATE INDEX ai_actions_room_seq_idx ON ai_actions (room_id, seq);
CREATE INDEX ai_actions_message_idx ON ai_actions (message_id);
