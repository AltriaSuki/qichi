-- P5-03：留言板。主题、留言、回应是同步实体；修订历史不可变、只插入、不进同步。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction'
));

CREATE TABLE board_topics (
    id         uuid PRIMARY KEY,
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    title      text        NOT NULL,
    author_id  uuid        NOT NULL REFERENCES users (id),
    pinned_at  timestamptz,
    seq        bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id),
    CONSTRAINT board_topics_title_len CHECK (char_length(title) BETWEEN 1 AND 60)
);
CREATE INDEX board_topics_room_seq_idx ON board_topics (room_id, seq);

CREATE TABLE board_posts (
    id              uuid PRIMARY KEY,
    room_id         uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    topic_id        uuid        NOT NULL REFERENCES board_topics (id) ON DELETE CASCADE,
    author_id       uuid        NOT NULL REFERENCES users (id),
    body            text        NOT NULL,
    quote_post_id   uuid REFERENCES board_posts (id) ON DELETE SET NULL,
    quote_author_id uuid REFERENCES users (id),
    quote_excerpt   text,
    revision        integer     NOT NULL DEFAULT 1,
    revised_at      timestamptz,
    seq             bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    deleted_by      uuid REFERENCES users (id),
    CONSTRAINT board_posts_body_len CHECK (char_length(body) BETWEEN 1 AND 10000),
    CONSTRAINT board_posts_revision_pos CHECK (revision >= 1)
);
CREATE INDEX board_posts_room_seq_idx ON board_posts (room_id, seq);
CREATE INDEX board_posts_topic_idx ON board_posts (topic_id, created_at);

CREATE TABLE board_post_revisions (
    post_id    uuid        NOT NULL REFERENCES board_posts (id) ON DELETE CASCADE,
    revision   integer     NOT NULL,
    body       text        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (post_id, revision)
);

CREATE TABLE board_reactions (
    id         uuid PRIMARY KEY,
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    post_id    uuid        NOT NULL REFERENCES board_posts (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    kind       text        NOT NULL CHECK (kind IN ('like', 'hug', 'support')),
    seq        bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id)
);
CREATE INDEX board_reactions_room_seq_idx ON board_reactions (room_id, seq);
-- 同一个人对同一条留言的同一种回应，同时只有一条有效
CREATE UNIQUE INDEX board_reactions_active_uq ON board_reactions (post_id, author_id, kind) WHERE deleted_at IS NULL;
