-- P6-03：共同时间线里「双方都选中的照片」。时间线本身不存数据，由决定、灵感、计划完成和这里拼出。
CREATE TABLE timeline_picks (
    room_id    uuid        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    file_id    uuid        NOT NULL REFERENCES files (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (file_id, user_id)
);
CREATE INDEX timeline_picks_room_idx ON timeline_picks (room_id);
