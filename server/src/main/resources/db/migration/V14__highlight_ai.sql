-- P6-05：阅读里 AI 的解释 / 对比写成 kind = ai 的标记。
ALTER TABLE highlights DROP CONSTRAINT highlights_kind_check;
ALTER TABLE highlights ADD CONSTRAINT highlights_kind_check CHECK (kind IN ('highlight', 'bookmark', 'excerpt', 'ai'));
