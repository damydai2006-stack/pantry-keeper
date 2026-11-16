-- Pantry Keeper V1 (M1). PostgreSQL 16.
-- Timestamps are timestamptz. Calendar dates are date, interpreted in the phone's local zone.
-- C-05: used_up and discarded rows are ordinary items and can be permanently deleted; the tombstone remains.
-- Photo bytes are summed per family and capped by PANTRY_FAMILY_PHOTO_MB (default 1024 = 1 GB).

create sequence item_rev_seq;

create table users (
  id uuid primary key,
  phone_enc bytea not null,
  phone_hmac bytea not null unique,
  display_name text not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table auth_codes (
  phone_hmac bytea primary key,
  code_hash text not null,
  expires_at timestamptz not null,
  attempts int not null default 0,
  created_at timestamptz not null
);

create table sessions (
  id uuid primary key,
  user_id uuid not null references users (id),
  token_hash bytea not null unique,
  client_kind text not null check (client_kind in ('app', 'web')),
  device_name text,
  created_at timestamptz not null,
  last_used_at timestamptz not null,
  expires_at timestamptz not null
);

create table user_settings (
  user_id uuid primary key references users (id),
  notify_time time not null default '09:00',
  updated_at timestamptz not null,
  rev bigint not null default 0
);

create table families (
  id uuid primary key,
  name text not null check (char_length(name) between 2 and 20),
  admin_user_id uuid references users (id),
  invite_code char(6) unique,
  invite_expires_at timestamptz,
  empty_since timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  rev bigint not null default 0
);

create table family_members (
  family_id uuid not null references families (id),
  user_id uuid not null references users (id),
  role text not null check (role in ('admin', 'member')),
  joined_at timestamptz not null,
  left_at timestamptz,
  left_reason text check (left_reason in ('left', 'removed')),
  primary key (family_id, user_id, joined_at)
);

create unique index one_active_family on family_members (user_id) where left_at is null;

create table items (
  id uuid primary key,
  family_id uuid not null references families (id),
  name text not null check (char_length(name) <= 40),
  category text not null default 'food' check (category in ('food', 'medicine', 'condiment', 'other')),
  quantity int not null default 1 check (quantity >= 0),
  location text check (location is null or char_length(location) <= 12),
  note text check (note is null or char_length(note) <= 200),
  barcode text,
  expiry_date date not null,
  production_date date,
  shelf_life_value int,
  shelf_life_unit text check (shelf_life_unit is null or shelf_life_unit in ('day', 'month', 'year')),
  expiry_basis text not null check (expiry_basis in (
    'ocr_expiry', 'ocr_prod_plus_life', 'manual_duration_from_prod', 'manual_duration_from_today', 'manual_date'
  )),
  opened_date date,
  after_open_days int,
  reminder_tiers text[] not null default '{D30,D5}',
  status text not null default 'in_stock' check (status in ('in_stock', 'used_up', 'discarded')),
  status_changed_at timestamptz,
  status_changed_by uuid,
  photo_id uuid,
  field_meta jsonb not null default '{}',
  created_by uuid,
  created_at timestamptz not null,
  updated_by uuid,
  updated_at timestamptz not null,
  rev bigint not null default nextval('item_rev_seq'),
  check (reminder_tiers <@ array['D5', 'D10', 'D30', 'M2', 'M3']::text[])
);

create index items_family_rev on items (family_id, rev);
create index items_family_status_expiry on items (family_id, status, expiry_date);

create table item_tombstones (
  item_id uuid primary key,
  family_id uuid not null,
  deleted_by uuid,
  deleted_at timestamptz not null,
  rev bigint not null
);

create table item_events (
  id bigserial primary key,
  family_id uuid not null,
  item_id uuid not null,
  actor_id uuid,
  action text not null,
  changed_fields text[],
  at timestamptz not null
);

create table photos (
  id uuid primary key,
  family_id uuid not null references families (id),
  item_id uuid,
  path text not null,
  thumb_path text,
  bytes int not null check (bytes >= 0),
  sha256 bytea,
  created_by uuid,
  created_at timestamptz not null
);

create index photos_family on photos (family_id);

comment on column photos.bytes is
  'Sum per family is capped by PANTRY_FAMILY_PHOTO_MB (default 1024 MB = 1 GB). Uploads also stop when free disk is under PANTRY_MIN_FREE_MB (default 1500).';

create table batch_ops (
  undo_token uuid primary key,
  family_id uuid not null,
  user_id uuid not null,
  action text not null,
  prior jsonb not null,
  created_at timestamptz not null
);

-- TECH lists primary key (barcode, scope, family_id) with a nullable family_id.
-- PostgreSQL primary keys reject NULL, so global rows keep family_id null and
-- uniqueness is a NULLS NOT DISTINCT index. id is the surrogate primary key.
create table barcode_cache (
  id uuid primary key,
  barcode text not null,
  scope text not null check (scope in ('global', 'family')),
  family_id uuid,
  name text not null,
  default_category text,
  source text not null,
  hit_count int not null default 0,
  updated_at timestamptz not null,
  check (
    (scope = 'global' and family_id is null) or
    (scope = 'family' and family_id is not null)
  )
);

create unique index barcode_cache_key on barcode_cache (barcode, scope, family_id) nulls not distinct;

create table devices (
  id uuid primary key,
  user_id uuid not null references users (id),
  platform text not null,
  push_provider text,
  push_token text,
  app_version text,
  last_seen_at timestamptz
);
