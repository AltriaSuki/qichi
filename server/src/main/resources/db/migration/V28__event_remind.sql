-- P16-01：日程提前多久提醒（分钟）；null = 不提醒。提醒由手机本地排，服务端只存和同步这个字段。
-- 已有的日程不提醒（和改版前一样）；新建时 App 默认填 15。
ALTER TABLE events ADD COLUMN remind_minutes integer;
ALTER TABLE events ADD CONSTRAINT events_remind_minutes CHECK (remind_minutes IS NULL OR remind_minutes IN (0, 5, 15, 60, 1440));
