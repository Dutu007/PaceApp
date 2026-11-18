create table if not exists pace_entries (
    id text primary key,
    kind text not null default 'note',
    title text not null default '',
    content text not null,
    tags jsonb not null default '[]'::jsonb,
    completed boolean not null default false,
    pinned boolean not null default false,
    source text not null default 'app',
    doc_path text,
    card_json jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_pace_entries_created_at on pace_entries (created_at desc);
create index if not exists idx_pace_entries_pinned on pace_entries (pinned, updated_at desc);
create index if not exists idx_pace_entries_kind on pace_entries (kind, created_at desc);
