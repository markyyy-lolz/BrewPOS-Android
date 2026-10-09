-- Additive, fail-closed owner issuance ledger for offline BP1 licenses.
-- The owner Edge Function is the ONLY API writer (Supabase service_role).
-- Original codes and private keys are NEVER stored here.
create table if not exists public.brew_activation_issues (
 id uuid primary key,
 customer_name text not null check (char_length(customer_name) between 2 and 80 and position('|' in customer_name)=0),
 android_device_id text not null check (android_device_id ~ '^[a-f0-9]{16}$'),
 plan text not null check (plan in ('TRIAL','MONTHLY','LIFETIME')),
 expires_at timestamptz,
 issued_by uuid not null references auth.users(id),
 token_sha256 text not null unique check (token_sha256 ~ '^[0-9a-f]{64}$'),
 issued_at timestamptz not null default now(),
 constraint brew_activation_issues_expiry check (
  (plan = 'LIFETIME' and expires_at is null) or
  (plan in ('TRIAL','MONTHLY') and expires_at is not null)
 )
);
create index if not exists brew_activation_issues_issued_at_idx
 on public.brew_activation_issues (issued_at desc);
create index if not exists brew_activation_issues_issued_by_date_idx
 on public.brew_activation_issues (issued_by,issued_at desc);
create index if not exists brew_activation_issues_device_idx
 on public.brew_activation_issues (android_device_id,issued_at desc);
alter table public.brew_activation_issues enable row level security;
revoke all on public.brew_activation_issues from public, anon, authenticated;
grant select, insert on public.brew_activation_issues to service_role;
comment on table public.brew_activation_issues is
 'Owner-issued BP1 activation audit records. Full activation tokens and ECDSA secret keys are never stored. No direct merchant/anonymous access.';
