# 03 · 数据模型

> 第 1–3 阶段的表已经写成 `server/src/main/resources/db/migration/V1__init.sql`（已在 PostgreSQL 16 上验证可执行）。
> 后续阶段的表在本文件中先定义字段，到对应阶段再写成 `V2`、`V3`… 迁移。

## 1. 通用约定

- **主键**：`uuid`，由客户端生成的 UUIDv7（服务端自己产生的数据也用 UUIDv7）。
- **时间**：`timestamptz`，一律存 UTC。纯日期（纪念日、截止日）用 `date`。
- **需要同步的表**都带这几列：

  | 列 | 含义 |
  |---|---|
  | `room_id` | 所属房间，`ON DELETE CASCADE` |
  | `seq` | 最后一次变化时分配的房间序号（见 `05-sync-offline.md`） |
  | `created_at` / `updated_at` | 创建、最后修改时间 |
  | `deleted_at` / `deleted_by` | 软删除（进回收站）；为空表示正常 |

  并且有索引 `(room_id, seq)`。
- **位置与版本分开**：`seq` 表示「最后一次变化」，会随修改、删除、恢复变大；需要稳定排序的实体另存创建时的序号（目前只有消息的 `created_seq`）。
- **作者**：谁写的就记谁（`author_id` / `created_by`），「我的 → 我写下的内容」靠它归总。
- **不可变内容**（文稿版本、审稿版本、档案修订）只插入、不更新。
- **枚举值**存英文小写字符串，取值定义在 `shared/model/`，中文显示名只在客户端。
- 命名：表名复数 `snake_case`；Kotlin 里对应单数 `PascalCase`。

## 2. 第 1–3 阶段（V1，已写好）

| 表 | 用途 | 关键字段 |
|---|---|---|
| `users` | 账号 | `username` 唯一、`password_hash`（Argon2id）、`display_name`、`avatar_file_id`、`notification_prefs` |
| `refresh_tokens` | 刷新令牌 | 只存哈希；`family_id`（同一次登录 = 一台设备，重复使用检测时整组作废）、`device_name`、`expires_at`、`revoked_at`、`replaced_by`（轮换链） |
| `rooms` | 房间 | `name`、`avatar_file_id`、`hero_file_id`（今天页主视觉）、`anniversary`、`timezone`、`last_seq` |
| `room_members` | 成员 | `role`：owner / member；每房间最多 2 人（服务端校验） |
| `invites` | 邀请码 | `code` 唯一、`expires_at`、`used_by` |
| `change_log` | 同步日志 | 主键 `(room_id, seq)`；`entity_type`、`entity_id`、`op` |
| `files` | 文件元数据 | `kind`：image / file / avatar / hero / epub / review；`sha256`；`storage_path` |
| `messages` | 聊天消息 | `kind`：text / image / file / ai / system；`created_seq`（创建时的 seq，决定消息位置，永不改变）；`reply_to_id` + `reply_author_id` + `reply_excerpt`；`retracted_at/by`（撤回时清空 `body`、`file_id`，以及回复它的消息的 `reply_excerpt`）；`body` 上有三元组索引用于搜索 |
| `read_markers` | 未读位置 | 每人每房间一行；`last_read_seq`（对应 `messages.created_seq`）只增不减；只同步给本人 |
| `moods` | 心情 | `label`、`intensity` 1–10、`note`、`needs_comfort` |
| `mood_responses` | 对心情的回应（接口与代码里叫 `MoodReply`，避免和 HTTP response 混淆） | `kind`：here（我在这里）/ hug（给你一个拥抱）/ ready（等你准备好） |
| `todos` | 待办 | `assignee_id`（空 = 两人）、`parent_id`（子任务，只有一层）、`due_date` 或 `due_at`、`recurrence`（RRULE）、`recurrence_prev_id`（由哪一次完成生成，唯一，防止重复生成）、`done_at/by` |
| `events` | 日程 | 定时：`starts_at`、`ends_at`；全天（`all_day`）：`start_date`、`end_date`（含首尾，按房间时区）；`participant_ids`（空 = 两人）、`ics_uid`（导入去重） |
| `devices` | 推送设备 | `provider`：fcm / unifiedpush；`token` |

`entity_type` 取值（与 `shared/model/EntityType` 一致）：
`room` `member` `message` `read_marker` `mood` `mood_response` `todo` `event`，后续阶段追加。

心情标签 `label` 的取值：`calm` 平静、`happy` 开心、`hopeful` 期待、`tired` 疲惫、`anxious` 焦虑、`down` 低落、`angry` 生气、`hurt` 委屈。

## 3. 后续阶段（待写迁移）

### 第 4 阶段：问答、计划、日历扩展、灵感、AI、任务队列

| 表 | 关键字段 |
|---|---|
| `questions` | 题库：`text`、`source`（ai / user / preset）、`suggested_by_job_id`、`adopted_by`、`adopted_at`（未采纳的 AI 建议 `adopted_at` 为空） |
| `qna_rounds` | 某天的一问：`question_id`、`round_date`（房间时区）、`revealed_at`；同房间同日期唯一 |
| `answers` | `round_id`、`author_id`、`body`、`confirmed_at`；**揭晓前接口只返回对方「是否已确认」，不返回 `body`** |
| `plans` | `title`、`owner_id`、`status`（active / done / archived）、`target_date`（可空）、`next_step`、`next_step_owner_id`、`next_step_due`、`completed_at`、`completion_note` |
| `plan_stages` | `plan_id`、`title`、`sort_order`、`done_at` |
| `milestones` | `plan_id`、`title`、`target_date`、`done_at` |
| `plan_logs` | `plan_id`、`author_id`、`body`（过程记录，不可变） |
| `todos` 增加列 | `plan_id` 引用 `plans` |
| `rooms` 增加列 | `ics_token`（只读订阅链接用的随机令牌，可重置） |
| `ideas` | 灵感：`author_id`、`body` |
| `jobs` | 任务队列：`kind`、`payload` jsonb、`status`（queued / running / done / failed）、`run_at`、`attempts`、`last_error`、`locked_at` |
| `ai_jobs` | AI 调用记录：`room_id`、`requested_by`、`kind`（chat_answer / question_suggest / read_explain / review_findings / summary / yearly_review）、`status`、`model`、`input_tokens`、`output_tokens`、`result_ref`（结果写到了哪个实体）、`error`、时间戳 |

### 第 5 阶段：共同写作、留言

| 表 | 关键字段 |
|---|---|
| `documents` | 同步实体（`document`）：`title`、`created_by`、`latest_version`（0 = 还没有版本）、`latest_author_id`、`char_count`（最新版本的字数）；正文不进同步 |
| `document_versions` | **不可变**：`document_id`、`version`（从 1 递增）、`base_version`、`author_id`、`body`（Markdown）、`char_count`、`restored_from_version`（旧版另存为新版时） |
| `board_topics` | 同步实体（`board_topic`）：`title`、`author_id`、`pinned_at` |
| `board_posts` | 同步实体（`board_post`）：`topic_id`、`author_id`、`body`、`quote_post_id` + `quote_author_id` + `quote_excerpt`、`revision`（从 1 开始）、`revised_at` |
| `board_post_revisions` | 修订历史（不可变，不进同步）：`post_id`、`revision`、`body` |
| `board_reactions` | 同步实体（`board_reaction`）：`post_id`、`author_id`、`kind`（like / hug / support）；收回是软删除，未收回的唯一 `(post_id, author_id, kind)` |

### 第 6 阶段：档案、决定、时间线、阅读、总结

| 表 | 关键字段 |
|---|---|
| `archive_items` | 同步实体（`archive_item`）：`kind`（preference / consensus / decision / boundary / concern / milestone）、当前的 `title` 与 `body`、`created_by`、`current_revision`、`revised_by`、`source_message_id` |
| `archive_revisions` | **不可变**、不进同步：`item_id`、`revision`、`author_id`、`title`、`body`、`source_message_id` |
| `decisions` | 同步实体（`decision`）：`question`、`options` jsonb（字符串数组）、`concerns` jsonb（`[{userId, text}]`，每人一条）、`final_choice`、`decided_at`、`decided_by`、`review_date`、`created_by` |
| `timeline_picks` | 共同时间线里「双方都选中的照片」：`file_id`、`user_id`，两人都选才显示 |
| `books` | 同步实体（`book`）：`title`、`author`、`file_id`（EPUB）、`added_by`、共读计划 `plan_target_date` + `plan_note` |
| `reading_progress` | 同步实体（`reading_progress`）：`book_id`、`user_id`、`locator`（Readium 定位的 JSON 文本）、`progress` 0–1；每人每本一条，各自独立 |
| `highlights` | 同步实体（`highlight`）：`book_id`、`user_id`、`locator`、`text`、`note`、`kind`（highlight / bookmark / excerpt / ai：AI 的解释或对比，note 是回答，由服务端写入）、`shared`（共同可见）；对方没共享的不同步给你 |
| `summaries` | 同步实体（`summary`）：`kind`（week / month / custom / year）、`range_start`、`range_end`、`body`（Markdown，用 [n] 引用）、`sources` jsonb（`[{number, type, id, label, at}]`，只存被引用的）、`ai_derived`、`locked`（年度回顾为 true，不可删除）、`requested_by`（年度回顾为空）；每个房间每年一份年度回顾 |

时间线不单独存数据，由决定、灵感、计划完成、`timeline_picks` 按时间查询拼出。

### 第 7 阶段：审稿

| 表 | 关键字段 |
|---|---|
| `review_documents` | `title`、`created_by`、`latest_version` |
| `review_versions` | **不可变**：`document_id`、`version`、`file_id`（原文件）、`preview_status`、`page_count` |
| `review_pages` | 预览页：`version_id`、`page_no`、`image_file_id`、`text_layer` jsonb（段落/单元格坐标，用于定位） |
| `annotations` | `version_id`、`anchor` jsonb（`{page, kind, rect?, ref?}`，kind 取 region / paragraph / cell / slide / image）、`author_id`、`kind`（comment / proposal）、`status`（open / accepted / archived）、`body`、`carried_from_id`（跨版本追踪） |
| `annotation_replies` | 讨论：`annotation_id`、`author_id`、`body` |
| `ai_findings` | `version_id`、`anchor`、`evidence`（原文摘录）、`body`、`status`（new / dismissed / converted）、`converted_annotation_id`、`job_id` |
