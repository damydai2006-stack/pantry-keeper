-- Cursor older than purged tombstones must full-reset. Horizon is the highest tombstone rev already removed.
alter table families add column sync_horizon bigint not null default 0;
