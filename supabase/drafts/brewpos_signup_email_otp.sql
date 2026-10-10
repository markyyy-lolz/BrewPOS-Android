-- BrewPOS-only self-registration queue. Email verification creates an Auth user, NOT a license or tenant.
-- Privileged mutations are handled by the existing verified BrewPOS Edge Functions.
create table if not exists public.brew_signup_requests (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null unique check (email = lower(email) and char_length(email) between 5 and 254),
  business_name text not null check (char_length(business_name) between 2 and 80),
  android_device_id text not null check (android_device_id ~ '^[0-9a-f]{16}$'),
  status text not null default 'pending' check (status in ('pending','approved')),
  requested_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  reviewed_at timestamptz
);
alter table public.brew_signup_requests enable row level security;
revoke all on public.brew_signup_requests from public, anon, authenticated, service_role;
grant select, insert, update on public.brew_signup_requests to service_role;
-- Explicit fail-closed policy; neither anonymous nor customer JWT can read pending applications.
drop policy if exists brew_signup_requests_deny_client_select on public.brew_signup_requests;
create policy brew_signup_requests_deny_client_select
on public.brew_signup_requests for select to authenticated using (false);
comment on table public.brew_signup_requests is
 'Verified email OTP account requests awaiting Azurate owner authorization and a real device-bound BP1 issuance. No customer self-assigned plan, organization or role.';
