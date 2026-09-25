-- P10-06：计划可以选一张照片做封面（不选时 App 用插画）。照片被删时封面回到插画。
ALTER TABLE plans ADD COLUMN cover_file_id uuid REFERENCES files (id) ON DELETE SET NULL;
