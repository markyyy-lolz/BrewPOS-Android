-- BrewPOS only: private verified Azurate-owner allowlist.
-- Do not add this table to StorePOS or MotoPOS projects.
-- Authorized operators are bound to stable auth.users UUIDs, NEVER user_metadata,
-- app_metadata provided by the browser, an email string, or a client-supplied role.
create table if not exists public.brew_owner_accounts (
  user_id uuid primary key references auth.users(id) on delete restrict,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);
alter table public.brew_owner_accounts enable row level security;
revoke all on public.brew_owner_accounts from public, anon, authenticated, service_role;
grant select on public.brew_owner_accounts to service_role;
drop policy if exists brew_owner_accounts_deny_direct_reads on public.brew_owner_accounts;
create policy brew_owner_accounts_deny_direct_reads on public.brew_owner_accounts
  for select to authenticated using (false);
comment on table public.brew_owner_accounts is
  'Server-only BrewPOS owner UUID allowlist. Grant/revoke via audited management SQL; no public update, no self-promotion.';
