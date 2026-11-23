create table if not exists pace_proactive_state (
    id int primary key check (id = 1),
    enabled boolean not null default true,
    intensity text not null default 'balanced',
    quiet_start text not null default '23:30',
    quiet_end text not null default '08:30',
    lab_focus_protect boolean not null default true,
    strict_supervision boolean not null default true,
    today_date text not null default '',
    today_budget int not null default 3,
    today_mood text not null default 'cozy',
    sent_today int not null default 0,
    strict_override_used int not null default 0,
    unanswered_count int not null default 0,
    accumulated_urge double precision not null default 0,
    last_sent_at timestamptz,
    last_eval_at timestamptz,
    next_eval_at timestamptz,
    last_decision_reason text not null default '',
    updated_at timestamptz not null default now()
);

insert into pace_proactive_state (id) values (1) on conflict (id) do nothing;
