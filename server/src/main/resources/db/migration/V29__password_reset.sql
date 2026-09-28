-- P16-04：忘了密码。房间里另一个人（或服务器上的命令）生成一次性重置码，15 分钟有效，用过就删。
-- 每人最多一个有效的码，再生成就换掉旧的。只存哈希。
CREATE TABLE password_reset_codes (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    code_hash  text        NOT NULL,
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,   -- 空 = 服务器上的命令
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
