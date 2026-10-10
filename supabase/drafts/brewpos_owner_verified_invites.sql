-- Additive BrewPOS-only, explicit Azurate invitation mechanism.
-- No self-promotion: invites are inserted by a privileged administrator and
-- redemption requires BOTH a verified auth.users identity and its matching email.
create table if not exists public.brew_owner_invitations (
  email text primary key check (email = lower(email) and char_length(email) between 5 and 254),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  claimed_by uuid references auth.users(id),
  claimed_at timestamptz,
  constraint brew_owner_invitation_claim_pair
    check ((claimed_by is null and claimed_at is null) or
           (claimed_by is not null and claimed_at is not null))
);
alter table public.brew_owner_invitations enable row level security;
revoke all on public.brew_owner_invitations from public, anon, authenticated, service_role;
drop policy if exists brew_owner_invitations_deny_client on public.brew_owner_invitations;
create policy brew_owner_invitations_deny_client
  on public.brew_owner_invitations for select to authenticated using (false);

-- Redeem invitation and bind verified Auth UUID in one database transaction.
-- Invoking this from the server with a service role is mandatory; callers cannot
-- choose their role or email in public browser data.
create or replace function public.brew_redeem_owner_invitation(
  verified_user_id uuid, verified_email text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  normalized_email text := lower(btrim(verified_email));
  invite_email text;
begin
  if not exists (
    select 1 from auth.users
    where id = verified_user_id
      and lower(email) = normalized_email
      and email_confirmed_at is not null
  ) then
    return false;
  end if;
  -- Never restore an intentionally disabled owner.
  if exists (
    select 1 from public.brew_owner_accounts
    where user_id = verified_user_id
  ) then
    return false;
  end if;
  update public.brew_owner_invitations
  set claimed_by = verified_user_id, claimed_at = now()
  where email = normalized_email
    and claimed_by is null
    and expires_at > now()
  returning email into invite_email;
  if invite_email is null then
    return false;
  end if;
  insert into public.brew_owner_accounts(user_id,is_active)
    values(verified_user_id,true);
  return true;
end;
$$;
revoke all on function public.brew_redeem_owner_invitation(uuid,text)
  from public, anon, authenticated;
grant execute on function public.brew_redeem_owner_invitation(uuid,text)
  to service_role;
comment on function public.brew_redeem_owner_invitation(uuid,text) is
  'Owner-invitation redemption, called only by BrewPOS owner Edge Function using a verified Supabase Auth JWT. Never executable by public clients.';
