create table if not exists pace_notifications (
    id bigserial primary key,
    title text not null default '',
    body text not null,
    category text not null default 'reminder',
    session_id text not null default '',
    source text not null default 'hermes',
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);

create index if not exists idx_pace_notifications_id_desc on pace_notifications (id desc);
create index if not exists idx_pace_notifications_unread on pace_notifications (is_read, id desc);
