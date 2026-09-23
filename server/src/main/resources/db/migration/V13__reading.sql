-- P6-04：阅读。书、各自进度、书签 / 标注 / 摘录都是同步实体；EPUB 文件在 files 里（kind = epub）。
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type CHECK (entity_type IN (
    'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event',
    'question', 'qna_round', 'answer', 'plan', 'plan_stage', 'milestone', 'plan_log', 'idea', 'document',
    'board_topic', 'board_post', 'board_reaction', 'archive_item', 'decision', 'book', 'reading_progress', 'highlight'
));

CREATE TABLE books (
    id               uuid PRIMARY KEY,
    room_id          uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    title            text        NOT NULL,
    author           text,
    file_id          uuid        NOT NULL REFERENCES files (id),
    added_by         uuid        NOT NULL REFERENCES users (id),
    plan_target_date date,
    plan_note        text,
    seq              bigint      NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    deleted_by       uuid REFERENCES users (id),
    CONSTRAINT books_title_len CHECK (char_length(title) BETWEEN 1 AND 200)
);
CREATE INDEX books_room_seq_idx ON books (room_id, seq);

CREATE TABLE reading_progress (
    id         uuid PRIMARY KEY,
    room_id    uuid             NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    book_id    uuid             NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    user_id    uuid             NOT NULL REFERENCES users (id),
    locator    text             NOT NULL,
    progress   double precision NOT NULL CHECK (progress >= 0 AND progress <= 1),
    seq        bigint           NOT NULL,
    created_at timestamptz      NOT NULL DEFAULT now(),
    updated_at timestamptz      NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id),
    CONSTRAINT reading_progress_unique UNIQUE (book_id, user_id)
);
CREATE INDEX reading_progress_room_seq_idx ON reading_progress (room_id, seq);

CREATE TABLE highlights (
    id         uuid PRIMARY KEY,
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    book_id    uuid        NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id),
    kind       text        NOT NULL CHECK (kind IN ('highlight', 'bookmark', 'excerpt')),
    locator    text        NOT NULL,
    text       text        NOT NULL DEFAULT '',
    note       text,
    shared     boolean     NOT NULL DEFAULT false,
    seq        bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id)
);
CREATE INDEX highlights_room_seq_idx ON highlights (room_id, seq);
CREATE INDEX highlights_book_idx ON highlights (book_id);
