-- P9-06：文稿置顶（两个人看到的一样）、分类；正文搜索用最新版本（不另建索引，两个人的数据量很小）。
ALTER TABLE documents ADD COLUMN pinned boolean NOT NULL DEFAULT false;
ALTER TABLE documents ADD COLUMN category text CHECK (category IN ('letter', 'travel', 'diary', 'review', 'other'));
