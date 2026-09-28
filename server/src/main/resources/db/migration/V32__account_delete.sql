-- 注销账号（P16-07）：不删用户这一行（写过的内容还指着它），只标注销时间；
-- 用户名换成 deleted_ 开头的占位、密码作废、显示名改成「已注销的成员」
ALTER TABLE users ADD COLUMN deleted_at timestamptz;
