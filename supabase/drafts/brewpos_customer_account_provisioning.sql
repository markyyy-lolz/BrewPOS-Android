-- BrewPOS customer accounts: additive only, separate from StorePOS/MotoPOS.
-- License plan is copied from an ACTUALLY ISSUED signed BP1 audit record by owner function.
-- Client cannot edit plan, organization membership or issue_id directly.
create table if not exists public.brew_customer_accounts (
  id uuid primary key default gen_random_uuid(),
  email text not null unique check (email = lower(email) and length(email) between 5 and 254),
  user_id uuid not null unique references auth.users(id),
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  license_issue_id uuid not null unique references public.brew_activation_issues(id),
  plan text not null check (plan in ('TRIAL','MONTHLY','LIFETIME')),
  expires_at timestamptz,
  status text not null default 'invited' check (status in ('invited','active','suspended')),
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint brew_customer_accounts_plan_expiry check (
    (plan='LIFETIME' and expires_at is null) or
    (plan in ('TRIAL','MONTHLY') and expires_at is not null)
  ),
  constraint brew_customer_accounts_branch_scope foreign key (branch_id,organization_id)
    references public.brew_branches(id,organization_id)
);
create index if not exists brew_customer_accounts_org_idx on public.brew_customer_accounts(organization_id);
alter table public.brew_customer_accounts enable row level security;
revoke all on public.brew_customer_accounts from public, anon, authenticated, service_role;
grant select, insert, update on public.brew_customer_accounts to service_role;
comment on table public.brew_customer_accounts is
 'Azurate-owner managed, verified invitation accounts. Plans cannot be self-edited and are tied to signed BP1 audit issuance. Only service-role server paths can access.';

-- Do not permit direct public client access. Existing brew_sales, brew_memberships,
-- products, device records and pending local transactions are untouched.
