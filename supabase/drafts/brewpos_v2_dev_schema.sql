-- BrewPOS v2.0 HYBRID SCHEMA DRAFT — FOR A NEW DEVELOPMENT PROJECT ONLY
-- Review and test this SQL in a separate Supabase DEVELOPMENT instance before use.
-- DO NOT apply to a project with production transactions without backup + migration review.
-- All money is stored as integer Philippine centavos (not floats).
-- Client writes are DENIED here; authenticated members have read-only access.
-- Future Edge Function must authenticate user + device, verify branch/org membership,
-- validate event schema, then insert sales, items, payments and event receipts atomically.
-- This draft does NOT implement the Edge Function or live synchronization.
create extension if not exists pgcrypto;

create table if not exists public.brew_organizations (
  id uuid primary key default gen_random_uuid(),
  name text not null check(length(trim(name)) >= 2),
  slug text not null unique,
  created_at timestamptz not null default now()
);
create table if not exists public.brew_memberships (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  user_id uuid not null references auth.users(id),
  role text not null check(role in ('owner','manager','cashier','barista','viewer')),
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  unique(organization_id,user_id)
);
create table if not exists public.brew_branches (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  name text not null,
  timezone text not null default 'Asia/Manila',
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  unique(id,organization_id)
);
create table if not exists public.brew_devices (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  installation_id uuid not null,
  display_name text not null default 'Android POS',
  is_active boolean not null default true,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  unique(id,organization_id),
  unique(organization_id,installation_id),
  foreign key(branch_id,organization_id) references public.brew_branches(id,organization_id)
);
create table if not exists public.brew_products (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  local_product_id text,
  name text not null,
  category text not null default 'Coffee',
  price_centavos bigint not null check(price_centavos >= 0),
  stock_quantity numeric(12,3) not null default 0,
  track_stock boolean not null default true,
  active boolean not null default true,
  version bigint not null default 1 check(version > 0),
  updated_at timestamptz not null default now(),
  unique(id,organization_id),
  foreign key(branch_id,organization_id) references public.brew_branches(id,organization_id)
);
create table if not exists public.brew_sales (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  device_id uuid not null,
  client_sale_id uuid not null,
  local_receipt_no text not null,
  service_type text not null check(service_type in ('Dine-in','Takeout')),
  payment_method text not null check(payment_method in ('Cash','GCash','Maya','Card')),
  subtotal_centavos bigint not null check(subtotal_centavos >= 0),
  discount_centavos bigint not null default 0 check(discount_centavos >= 0),
  total_centavos bigint not null check(total_centavos >= 0),
  status text not null default 'completed' check(status in ('completed','voided','refunded')),
  created_offline_at timestamptz not null,
  received_at timestamptz not null default now(),
  unique(id,organization_id),
  unique(organization_id,client_sale_id),
  foreign key(branch_id,organization_id) references public.brew_branches(id,organization_id),
  foreign key(device_id,organization_id) references public.brew_devices(id,organization_id),
  check(total_centavos = subtotal_centavos - discount_centavos)
);
create table if not exists public.brew_sale_items (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  sale_id uuid not null,
  line_no integer not null check(line_no > 0),
  product_name text not null,
  options jsonb not null default '[]'::jsonb,
  quantity integer not null check(quantity > 0),
  unit_centavos bigint not null check(unit_centavos >= 0),
  line_centavos bigint not null check(line_centavos >= 0),
  unique(sale_id,line_no),
  foreign key(sale_id,organization_id) references public.brew_sales(id,organization_id)
);
create table if not exists public.brew_payments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  sale_id uuid not null,
  method text not null check(method in ('Cash','GCash','Maya','Card')),
  amount_centavos bigint not null check(amount_centavos >= 0),
  tendered_centavos bigint check(tendered_centavos is null or tendered_centavos >= 0),
  change_centavos bigint not null default 0 check(change_centavos >= 0),
  verification text not null default 'manual' check(verification in ('manual','gateway_verified')),
  recorded_at timestamptz not null default now(),
  foreign key(sale_id,organization_id) references public.brew_sales(id,organization_id)
);
create table if not exists public.brew_sync_events (
  id uuid primary key,
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  device_id uuid not null,
  client_sale_id uuid,
  event_type text not null check(event_type in ('sale.completed','order.status','stock.adjusted')),
  payload jsonb not null,
  payload_version integer not null default 1 check(payload_version > 0),
  received_at timestamptz not null default now(),
  foreign key(branch_id,organization_id) references public.brew_branches(id,organization_id),
  foreign key(device_id,organization_id) references public.brew_devices(id,organization_id),
  unique(organization_id,id)
);
create table if not exists public.brew_inventory_movements (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  product_id uuid not null,
  source_event_id uuid,
  change_qty numeric(12,3) not null,
  reason text not null check(reason in ('sale','restock','adjustment','reversal')),
  created_at timestamptz not null default now(),
  foreign key(branch_id,organization_id) references public.brew_branches(id,organization_id),
  foreign key(product_id,organization_id) references public.brew_products(id,organization_id),
  unique(organization_id,source_event_id,product_id)
);
create table if not exists public.brew_licenses (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.brew_organizations(id),
  device_id uuid not null,
  plan text not null check(plan in ('trial','monthly','lifetime')),
  status text not null check(status in ('active','expired','revoked')),
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  foreign key(device_id,organization_id) references public.brew_devices(id,organization_id),
  check((plan = 'lifetime' and expires_at is null) or (plan <> 'lifetime' and expires_at is not null))
);
create index if not exists brew_sales_by_branch_time on public.brew_sales(organization_id,branch_id,received_at desc);
create index if not exists brew_products_by_branch on public.brew_products(organization_id,branch_id,active);
create index if not exists brew_sync_events_time on public.brew_sync_events(organization_id,received_at desc);
create index if not exists brew_inventory_movements_time on public.brew_inventory_movements(organization_id,created_at desc);
create index if not exists brew_memberships_by_user on public.brew_memberships(user_id,organization_id) where is_active;

-- Lock down public Data API access; avoid assuming RLS alone grants privileges.
revoke all on public.brew_organizations, public.brew_memberships, public.brew_branches,
 public.brew_devices, public.brew_products, public.brew_sales, public.brew_sale_items,
 public.brew_payments, public.brew_sync_events, public.brew_inventory_movements,
 public.brew_licenses from anon, authenticated;
grant select on public.brew_organizations, public.brew_memberships, public.brew_branches,
 public.brew_devices, public.brew_products, public.brew_sales, public.brew_sale_items,
 public.brew_payments, public.brew_sync_events, public.brew_inventory_movements,
 public.brew_licenses to authenticated;

alter table public.brew_organizations enable row level security;
alter table public.brew_memberships enable row level security;
alter table public.brew_branches enable row level security;
alter table public.brew_devices enable row level security;
alter table public.brew_products enable row level security;
alter table public.brew_sales enable row level security;
alter table public.brew_sale_items enable row level security;
alter table public.brew_payments enable row level security;
alter table public.brew_sync_events enable row level security;
alter table public.brew_inventory_movements enable row level security;
alter table public.brew_licenses enable row level security;

-- Memberships can be viewed by each user only; all mutations through privileged backend.
create policy brew_membership_self_read on public.brew_memberships
 for select to authenticated using (user_id = (select auth.uid()));
create policy brew_organizations_member_read on public.brew_organizations
 for select to authenticated using (id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_branches_member_read on public.brew_branches
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_devices_member_read on public.brew_devices
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_products_member_read on public.brew_products
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_sales_member_read on public.brew_sales
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_sale_items_member_read on public.brew_sale_items
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_payments_member_read on public.brew_payments
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_sync_events_manager_read on public.brew_sync_events
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active and role in ('owner','manager')));
create policy brew_inventory_movements_member_read on public.brew_inventory_movements
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active));
create policy brew_licenses_owner_read on public.brew_licenses
 for select to authenticated using (organization_id in
  (select organization_id from public.brew_memberships
    where user_id=(select auth.uid()) and is_active and role='owner'));

-- SECURITY NOTES
-- (1) Authenticated members have SELECT only; client writes intentionally blocked.
-- (2) A future authenticated Edge Function (not included) must verify user, device,
--     branch and org, then apply sale + line items + payments + inventory in one
--     atomic transaction and acknowledge duplicate event IDs safely.
-- (3) Do NOT put Supabase service_role/secret keys in Android or public website.
-- (4) Do NOT execute on production until verified with backup + tests.
