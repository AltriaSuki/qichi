-- 栖迟 V1：第 1–3 阶段（账号、房间、同步、心情、待办、日程、聊天、文件、推送设备）
-- 约定见 docs/03-data-model.md；同步机制见 docs/05-sync-offline.md。
--
-- 需要同步的表都带：room_id、seq、created_at、updated_at、deleted_at、deleted_by，并有 (room_id, seq) 索引。
-- 所有写入都经过 server 的 db/RoomWrite.kt：分配 seq → 写实体 → 写 change_log，同一个事务。
-- 主键一律是客户端（或服务端）生成的 UUIDv7。时间一律 timestamptz（UTC）。
-- 枚举值是英文小写字符串，取值与 shared/model 保持一致；新增取值要写新迁移修改 CHECK。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ───────────────────────── 账号 ─────────────────────────

CREATE TABLE users (
    id                  uuid PRIMARY KEY,
    username            text        NOT NULL,
    password_hash       text        NOT NULL,                 -- Argon2id 编码串
    display_name        text        NOT NULL,
    avatar_file_id      uuid,                                 -- 外键在 files 建好后补
    notification_prefs  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    password_changed_at timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9_]{3,32}$'),
    CONSTRAINT users_display_name_len CHECK (char_length(display_name) BETWEEN 1 AND 32)
);
CREATE UNIQUE INDEX users_username_key ON users (username);

-- 刷新令牌：只存哈希。同一次登录（一台设备）产生的令牌共用 family_id，轮换时串成链。
-- 已作废的令牌被再次使用 → 整个 family 作废（docs/04-api.md「令牌」）。
CREATE TABLE refresh_tokens (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id    uuid        NOT NULL,
    token_hash   text        NOT NULL,                        -- SHA-256 十六进制
    device_name  text,                                        -- 「安全 → 登录设备」显示用
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);
CREATE UNIQUE INDEX refresh_tokens_hash_key ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

-- ───────────────────────── 房间 ─────────────────────────

CREATE TABLE rooms (
    id             uuid PRIMARY KEY,
    name           text        NOT NULL,
    avatar_file_id uuid,
    hero_file_id   uuid,                                      -- 今天页主视觉照片
    anniversary    date,
    timezone       text        NOT NULL DEFAULT 'Asia/Shanghai', -- IANA 时区名，服务端校验
    created_by     uuid        NOT NULL REFERENCES users (id),
    last_seq       bigint      NOT NULL DEFAULT 0,            -- 房间序号计数器，只增不减
    seq            bigint      NOT NULL DEFAULT 0,            -- 房间本身（设置）最后一次变化的 seq
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT rooms_name_len CHECK (char_length(name) BETWEEN 1 AND 40),
    CONSTRAINT rooms_seq_le_last CHECK (seq <= last_seq)
);

-- 每个房间最多 2 名有效成员：由服务端在锁住 rooms 行后校验。
CREATE TABLE room_members (
    id         uuid PRIMARY KEY,
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       text        NOT NULL,
    joined_at  timestamptz NOT NULL DEFAULT now(),
    seq        bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id),
    CONSTRAINT room_members_role CHECK (role IN ('owner', 'member'))
);
CREATE UNIQUE INDEX room_members_room_user_key ON room_members (room_id, user_id);
CREATE INDEX room_members_user_idx ON room_members (user_id);
CREATE INDEX room_members_room_seq_idx ON room_members (room_id, seq);

-- 邀请码不参与同步，按需查询。
CREATE TABLE invites (
    id         uuid PRIMARY KEY,
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    code       text        NOT NULL,                          -- 8 位，去掉 0/O/1/I/L 等易混字符
    created_by uuid        NOT NULL REFERENCES users (id),
    expires_at timestamptz NOT NULL,
    used_by    uuid REFERENCES users (id),
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT invites_code_format CHECK (code ~ '^[A-Z2-9]{8}$'),
    CONSTRAINT invites_used_pair CHECK ((used_by IS NULL) = (used_at IS NULL))
);
CREATE UNIQUE INDEX invites_code_key ON invites (code);
CREATE INDEX invites_room_idx ON invites (room_id);

-- ───────────────────────── 同步日志 ─────────────────────────

CREATE TABLE change_log (
    room_id     uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    seq         bigint      NOT NULL,
    entity_type text        NOT NULL,
    entity_id   uuid        NOT NULL,
    op          text        NOT NULL,
    actor_id    uuid REFERENCES users (id) ON DELETE SET NULL, -- 服务端自动产生的变化为空
    at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, seq),
    CONSTRAINT change_log_op CHECK (op IN ('upsert', 'delete')),
    CONSTRAINT change_log_entity_type CHECK (entity_type IN (
        'room', 'member', 'message', 'read_marker', 'mood', 'mood_response', 'todo', 'event'
    ))
);
CREATE INDEX change_log_entity_idx ON change_log (room_id, entity_type, entity_id);

-- ───────────────────────── 文件 ─────────────────────────

-- 文件元数据不单独同步：消息等实体在接口里内嵌所引用文件的元数据。
CREATE TABLE files (
    id           uuid PRIMARY KEY,
    room_id      uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    uploaded_by  uuid        NOT NULL REFERENCES users (id),
    kind         text        NOT NULL,
    file_name    text        NOT NULL,
    mime_type    text        NOT NULL,
    size_bytes   bigint      NOT NULL,
    sha256       text        NOT NULL,
    storage_path text        NOT NULL,                        -- 相对 FILES_DIR：{roomId}/{yyyy}/{mm}/{fileId}
    width        integer,                                     -- 仅图片
    height       integer,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT files_kind CHECK (kind IN ('image', 'file', 'avatar', 'hero', 'epub', 'review')),
    CONSTRAINT files_size CHECK (size_bytes >= 0),
    CONSTRAINT files_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT files_file_name_len CHECK (char_length(file_name) BETWEEN 1 AND 255)
);
CREATE INDEX files_room_idx ON files (room_id);

ALTER TABLE users
    ADD CONSTRAINT users_avatar_file_fk FOREIGN KEY (avatar_file_id) REFERENCES files (id) ON DELETE SET NULL;
ALTER TABLE rooms
    ADD CONSTRAINT rooms_avatar_file_fk FOREIGN KEY (avatar_file_id) REFERENCES files (id) ON DELETE SET NULL,
    ADD CONSTRAINT rooms_hero_file_fk FOREIGN KEY (hero_file_id) REFERENCES files (id) ON DELETE SET NULL;

-- ───────────────────────── 聊天 ─────────────────────────

-- created_seq：消息创建时分配的 seq，决定它在聊天里的位置，之后永不改变。
-- seq 会随撤回、删除、恢复而变大，所以翻历史（beforeSeq）、未读计数、「新消息」分隔线都用 created_seq。
CREATE TABLE messages (
    id            uuid PRIMARY KEY,
    room_id       uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    author_id     uuid REFERENCES users (id),                 -- ai / system 消息为空
    kind          text        NOT NULL,
    body          text        NOT NULL DEFAULT '',
    file_id       uuid REFERENCES files (id) ON DELETE SET NULL,
    reply_to_id     uuid REFERENCES messages (id) ON DELETE SET NULL,
    reply_author_id uuid REFERENCES users (id),               -- 原消息作者（ai / system 为空），回复时由服务端填写
    reply_excerpt   text,                                     -- 服务端截取的原消息前 60 字；原消息撤回时清空
    retracted_at  timestamptz,
    retracted_by  uuid REFERENCES users (id),
    created_seq   bigint      NOT NULL,
    seq           bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    deleted_by    uuid REFERENCES users (id),
    CONSTRAINT messages_kind CHECK (kind IN ('text', 'image', 'file', 'ai', 'system')),
    CONSTRAINT messages_body_len CHECK (char_length(body) <= 10000),
    CONSTRAINT messages_author CHECK (kind IN ('ai', 'system') OR author_id IS NOT NULL),
    CONSTRAINT messages_retracted_pair CHECK ((retracted_at IS NULL) = (retracted_by IS NULL)),
    CONSTRAINT messages_retracted_empty CHECK (retracted_at IS NULL OR (body = '' AND file_id IS NULL)),
    CONSTRAINT messages_seq_order CHECK (created_seq <= seq)
);
CREATE UNIQUE INDEX messages_room_created_seq_key ON messages (room_id, created_seq);
CREATE INDEX messages_room_seq_idx ON messages (room_id, seq);
-- 搜索：pg_trgm + ILIKE；查询条件必须同样带上「未撤回、未删除」才能用到这个部分索引。
CREATE INDEX messages_body_trgm_idx ON messages USING gin (body gin_trgm_ops)
    WHERE deleted_at IS NULL AND retracted_at IS NULL;

-- 未读位置：按用户（不是按设备）保存，只进不退。只同步给本人，不向对方暴露（不做已读回执）。
CREATE TABLE read_markers (
    id            uuid PRIMARY KEY,
    room_id       uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_seq bigint      NOT NULL DEFAULT 0,             -- 对应 messages.created_seq
    seq           bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    deleted_by    uuid REFERENCES users (id),
    CONSTRAINT read_markers_non_negative CHECK (last_read_seq >= 0)
);
CREATE UNIQUE INDEX read_markers_room_user_key ON read_markers (room_id, user_id);
CREATE INDEX read_markers_room_seq_idx ON read_markers (room_id, seq);

-- ───────────────────────── 心情 ─────────────────────────

CREATE TABLE moods (
    id            uuid PRIMARY KEY,
    room_id       uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    author_id     uuid        NOT NULL REFERENCES users (id),
    label         text        NOT NULL,
    intensity     smallint    NOT NULL,
    note          text,
    needs_comfort boolean     NOT NULL DEFAULT false,
    seq           bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    deleted_by    uuid REFERENCES users (id),
    CONSTRAINT moods_label CHECK (label IN ('calm', 'happy', 'hopeful', 'tired', 'anxious', 'down', 'angry', 'hurt')),
    CONSTRAINT moods_intensity CHECK (intensity BETWEEN 1 AND 10),
    CONSTRAINT moods_note_len CHECK (note IS NULL OR char_length(note) <= 500)
);
CREATE INDEX moods_room_seq_idx ON moods (room_id, seq);
CREATE INDEX moods_room_created_idx ON moods (room_id, created_at);

-- 接口与代码里叫 MoodReply。收回回应 = 软删除。
CREATE TABLE mood_responses (
    id         uuid PRIMARY KEY,
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    mood_id    uuid        NOT NULL REFERENCES moods (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    kind       text        NOT NULL,
    seq        bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id),
    CONSTRAINT mood_responses_kind CHECK (kind IN ('here', 'hug', 'ready'))
);
CREATE INDEX mood_responses_room_seq_idx ON mood_responses (room_id, seq);
CREATE INDEX mood_responses_mood_idx ON mood_responses (mood_id);
-- 同一个人对同一条心情的同一种回应，同时只保留一条有效的。
CREATE UNIQUE INDEX mood_responses_active_key ON mood_responses (mood_id, author_id, kind)
    WHERE deleted_at IS NULL;

-- ───────────────────────── 待办 ─────────────────────────

CREATE TABLE todos (
    id                 uuid PRIMARY KEY,
    room_id            uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    title              text        NOT NULL,
    note               text,
    created_by         uuid        NOT NULL REFERENCES users (id),
    assignee_id        uuid REFERENCES users (id),            -- 空 = 两个人
    parent_id          uuid REFERENCES todos (id) ON DELETE CASCADE, -- 子任务
    due_date           date,                                  -- 只有日期的截止（按房间时区理解）
    due_at             timestamptz,                           -- 精确到时刻的截止
    recurrence         text,                                  -- RRULE，如 FREQ=WEEKLY;INTERVAL=1;BYDAY=SU
    recurrence_prev_id uuid REFERENCES todos (id) ON DELETE SET NULL, -- 由哪一次完成生成
    done_at            timestamptz,
    done_by            uuid REFERENCES users (id),
    seq                bigint      NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz,
    deleted_by         uuid REFERENCES users (id),
    CONSTRAINT todos_title_len CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT todos_note_len CHECK (note IS NULL OR char_length(note) <= 2000),
    CONSTRAINT todos_one_due CHECK (due_date IS NULL OR due_at IS NULL),
    CONSTRAINT todos_done_pair CHECK ((done_at IS NULL) = (done_by IS NULL)),
    CONSTRAINT todos_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT todos_recurrence_needs_due CHECK (recurrence IS NULL OR due_date IS NOT NULL OR due_at IS NOT NULL)
);
CREATE INDEX todos_room_seq_idx ON todos (room_id, seq);
CREATE INDEX todos_parent_idx ON todos (parent_id);
-- 每一次完成最多生成一个下一次实例：重复提交「完成」不会生成两个。
CREATE UNIQUE INDEX todos_recurrence_prev_key ON todos (recurrence_prev_id);

-- ───────────────────────── 日程 ─────────────────────────

-- 定时日程用 starts_at / ends_at；全天日程用 start_date / end_date（含首尾，按房间时区理解）。
CREATE TABLE events (
    id              uuid PRIMARY KEY,
    room_id         uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    title           text        NOT NULL,
    note            text,
    location        text,
    all_day         boolean     NOT NULL DEFAULT false,
    starts_at       timestamptz,
    ends_at         timestamptz,
    start_date      date,
    end_date        date,
    participant_ids uuid[]      NOT NULL DEFAULT '{}',        -- 空 = 两个人
    created_by      uuid        NOT NULL REFERENCES users (id),
    ics_uid         text,                                     -- ICS 导入去重（第 4 阶段）
    seq             bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    deleted_by      uuid REFERENCES users (id),
    CONSTRAINT events_title_len CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT events_note_len CHECK (note IS NULL OR char_length(note) <= 2000),
    CONSTRAINT events_time_shape CHECK (
        (all_day AND start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date
             AND starts_at IS NULL AND ends_at IS NULL)
        OR
        (NOT all_day AND starts_at IS NOT NULL AND ends_at IS NOT NULL AND ends_at >= starts_at
             AND start_date IS NULL AND end_date IS NULL)
    )
);
CREATE INDEX events_room_seq_idx ON events (room_id, seq);
CREATE INDEX events_room_starts_idx ON events (room_id, starts_at);
CREATE INDEX events_room_start_date_idx ON events (room_id, start_date);
CREATE UNIQUE INDEX events_room_ics_uid_key ON events (room_id, ics_uid) WHERE ics_uid IS NOT NULL;

-- ───────────────────────── 推送设备 ─────────────────────────

CREATE TABLE devices (
    id                uuid PRIMARY KEY,
    user_id           uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider          text        NOT NULL,
    token             text        NOT NULL,                   -- FCM 令牌，或 UnifiedPush 的推送地址
    refresh_family_id uuid,                                   -- 所属登录；登出时一并注销
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT devices_provider CHECK (provider IN ('fcm', 'unifiedpush'))
);
CREATE UNIQUE INDEX devices_provider_token_key ON devices (provider, token);
CREATE INDEX devices_user_idx ON devices (user_id);
