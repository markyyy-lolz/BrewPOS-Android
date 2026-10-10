-- BrewPOS Android self-ordering kiosk DRAFT; not applied to live café data.
-- Requires review, backup, merchant onboarding and PayMongo webhook acceptance.
-- Expose ONLY to the service_role Edge API; no public table policies.
create table if not exists public.brew_kiosk_devices (
  device_id uuid primary key references public.brew_devices(id) on delete restrict,
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  token_sha256 text not null unique check(token_sha256 ~ '^[0-9a-f]{64}$'),
  enabled boolean not null default true,
  paired_at timestamptz not null default now(),
  foreign key(branch_id,organization_id) references public.brew_branches(id,organization_id)
);
alter table public.brew_kiosk_devices enable row level security;
revoke all on public.brew_kiosk_devices from anon,authenticated;
grant select,insert,update on public.brew_kiosk_devices to service_role;

create table if not exists public.brew_kiosk_orders (
  id uuid primary key default gen_random_uuid(),
  order_number bigint generated always as identity unique,
  organization_id uuid not null references public.brew_organizations(id),
  branch_id uuid not null,
  kiosk_device_id uuid not null references public.brew_kiosk_devices(device_id),
  submission_id uuid not null,
  request_payload jsonb not null,
  items jsonb not null,
  service_type text not null check(service_type in ('Dine-in','Takeout')),
  payment_choice text not null check(payment_choice in ('cashier','qrph')),
  subtotal_centavos bigint not null check(subtotal_centavos>=0),
  total_centavos bigint not null check(total_centavos>=0),
  payment_status text not null default 'unpaid' check(payment_status in ('unpaid','awaiting_qrph','paid','cancelled')),
  provider_intent_id text unique,
  provider_qr_image text,
  sale_id uuid,
  tendered_centavos bigint,
  change_centavos bigint,
  paid_at timestamptz,
  created_at timestamptz not null default now(),
  unique(kiosk_device_id,submission_id),
  foreign key(branch_id,organization_id) references public.brew_branches(id,organization_id)
);
create index if not exists brew_kiosk_orders_pending_idx on public.brew_kiosk_orders(organization_id,branch_id,payment_status,created_at);
alter table public.brew_kiosk_orders enable row level security;
revoke all on public.brew_kiosk_orders from anon,authenticated;
grant select,insert,update on public.brew_kiosk_orders to service_role;
grant usage,select on sequence public.brew_kiosk_orders_order_number_seq to service_role;

-- Existing cashier payments stay unchanged. QRPH is only written after server verification.
alter table public.brew_sales drop constraint if exists brew_sales_payment_method_check;
alter table public.brew_sales add constraint brew_sales_payment_method_check
  check(payment_method in ('Cash','GCash','Maya','Card','QRPH'));
alter table public.brew_payments drop constraint if exists brew_payments_method_check;
alter table public.brew_payments add constraint brew_payments_method_check
  check(method in ('Cash','GCash','Maya','Card','QRPH'));

-- Store a server-verified snapshot. Client prices, totals, tenant IDs and paid flags are ignored.
create or replace function public.brew_kiosk_create_order(
  p_device_id uuid,p_submission_id uuid,p_service text,p_payment text,p_items jsonb
) returns jsonb language plpgsql security invoker set search_path=pg_catalog,public as $$
declare
  v_device public.brew_kiosk_devices%rowtype;
  v_request jsonb;
  v_existing public.brew_kiosk_orders%rowtype;
  v_product public.brew_products%rowtype;
  v_item jsonb;
  v_items jsonb := '[]'::jsonb;
  v_total bigint := 0;
  v_qty integer;
  v_size text;
  v_sugar text;
  v_ice text;
  v_shot boolean;
  v_unit bigint;
  v_row public.brew_kiosk_orders%rowtype;
begin
  select * into v_device from public.brew_kiosk_devices where device_id=p_device_id and enabled;
  if not found or not exists(select 1 from public.brew_devices d join public.brew_branches b
      on d.branch_id=b.id and d.organization_id=b.organization_id
      where d.id=v_device.device_id and d.organization_id=v_device.organization_id
        and d.branch_id=v_device.branch_id and d.is_active and b.is_active)
    then raise exception 'Inactive or unpaired kiosk'; end if;
  if p_submission_id is null or p_service not in ('Dine-in','Takeout')
     or p_payment not in ('cashier','qrph') or jsonb_typeof(p_items)<>'array'
     or jsonb_array_length(p_items) not between 1 and 40
    then raise exception 'Invalid order request'; end if;
  v_request := jsonb_build_object('service',p_service,'payment',p_payment,'items',p_items);
  select * into v_existing from public.brew_kiosk_orders
      where kiosk_device_id=p_device_id and submission_id=p_submission_id;
  if found then
    if v_existing.request_payload<>v_request then raise exception 'Conflicting submission ID'; end if;
    return jsonb_build_object('id',v_existing.id,'number','K-'||v_existing.order_number,
        'total_centavos',v_existing.total_centavos,'payment_status',v_existing.payment_status,
        'qr_image',v_existing.provider_qr_image,'duplicate',true);
  end if;
  for v_item in select value from jsonb_array_elements(p_items) loop
    v_qty := (v_item->>'quantity')::integer;
    v_size := v_item->>'size';
    v_sugar := v_item->>'sugar';
    v_ice := v_item->>'ice';
    v_shot := coalesce((v_item->>'extra_shot')::boolean,false);
    if v_qty is null or v_qty not between 1 and 99
       or v_size not in ('Regular','Large') or v_sugar not in ('0%','50%','100%')
       or v_ice not in ('No ice','Less','Regular') then raise exception 'Invalid customization'; end if;
    select * into v_product from public.brew_products where id=(v_item->>'product_id')::uuid
      and organization_id=v_device.organization_id and branch_id=v_device.branch_id and active;
    if not found or (v_product.track_stock and v_product.stock_quantity<v_qty) then
      raise exception 'Menu item unavailable'; end if;
    v_unit := v_product.price_centavos + case when v_size='Large' then 3000 else 0 end
      + case when v_shot then 4000 else 0 end;
    v_total := v_total + v_unit*v_qty;
    v_items := v_items || jsonb_build_array(jsonb_build_object(
      'product_id',v_product.id,'name',v_product.name,'category',v_product.category,'quantity',v_qty,
      'unit_centavos',v_unit,'line_centavos',v_unit*v_qty,
      'options',v_size||', '||v_sugar||' sugar, '||v_ice||' ice'||
        case when v_shot then ', extra shot' else '' end));
  end loop;
  if v_total<=0 or v_total>100000000 then raise exception 'Invalid order total'; end if;
  insert into public.brew_kiosk_orders(
    organization_id,branch_id,kiosk_device_id,submission_id,request_payload,items,
    service_type,payment_choice,subtotal_centavos,total_centavos
  ) values(
    v_device.organization_id,v_device.branch_id,p_device_id,p_submission_id,v_request,v_items,
    p_service,p_payment,v_total,v_total
  ) on conflict (kiosk_device_id,submission_id) do nothing
  returning * into v_row;
  if not found then
    select * into v_row from public.brew_kiosk_orders where kiosk_device_id=p_device_id and submission_id=p_submission_id;
    if v_row.request_payload<>v_request then raise exception 'Conflicting submission ID'; end if;
  end if;
  return jsonb_build_object('id',v_row.id,'number','K-'||v_row.order_number,
    'total_centavos',v_row.total_centavos,'payment_status',v_row.payment_status,
    'qr_image',v_row.provider_qr_image);
end $$;
revoke all on function public.brew_kiosk_create_order(uuid,uuid,text,text,jsonb) from public,anon,authenticated;
grant execute on function public.brew_kiosk_create_order(uuid,uuid,text,text,jsonb) to service_role;

-- One transaction owns paid sale, inventory deduction, payment and print eligibility.
-- Edge Function may call for Cash only after staff authentication, and for QRPH only
-- after retrieving and verifying the succeeded PayMongo Payment Intent.
create or replace function public.brew_kiosk_finalize_order(
  p_order_id uuid,p_source text,p_actor_id uuid,p_tendered bigint,p_provider_intent text
) returns jsonb language plpgsql security invoker set search_path=pg_catalog,public as $$
declare
  v_order public.brew_kiosk_orders%rowtype;
  v_entry jsonb;
  v_group record;
  v_sale_id uuid;
  v_change bigint;
  v_payment text;
begin
  select * into v_order from public.brew_kiosk_orders where id=p_order_id for update;
  if not found then raise exception 'Unknown kiosk order'; end if;
  if v_order.payment_status='paid' then
    return jsonb_build_object('accepted',true,'duplicate',true,'order_id',p_order_id,'sale_id',v_order.sale_id);
  end if;
  if v_order.payment_status='cancelled' then raise exception 'Cancelled order'; end if;
  if p_source='cashier' then
    if v_order.payment_choice<>'cashier' or p_actor_id is null or
      not exists(select 1 from public.brew_memberships where organization_id=v_order.organization_id
        and user_id=p_actor_id and is_active and role in ('owner','manager','cashier'))
      then raise exception 'Authorized cashier required'; end if;
    if p_tendered<v_order.total_centavos then raise exception 'Insufficient tender'; end if;
    v_payment:='Cash';v_change:=p_tendered-v_order.total_centavos;
  elsif p_source='verified_qrph' then
    if v_order.payment_choice<>'qrph' or v_order.provider_intent_id is null
      or v_order.provider_intent_id is distinct from p_provider_intent
      or v_order.payment_status<>'awaiting_qrph' then
      raise exception 'QR Ph payment is not verified for this order'; end if;
    v_payment:='QRPH';p_tendered:=v_order.total_centavos;v_change:=0;
  else raise exception 'Invalid payment source'; end if;
  -- Lock products consistently and reject an item deleted after ordering.
  for v_group in select (x->>'product_id')::uuid as product_id,
        sum((x->>'quantity')::integer) as qty
    from jsonb_array_elements(v_order.items) x group by 1 order by 1 loop
    perform 1 from public.brew_products where id=v_group.product_id
      and organization_id=v_order.organization_id and branch_id=v_order.branch_id for update;
    if not found then raise exception 'Unknown kiosk product at settlement'; end if;
    insert into public.brew_inventory_movements(
      organization_id,branch_id,product_id,source_event_id,change_qty,reason)
    select v_order.organization_id,v_order.branch_id,id,v_order.id,-v_group.qty,'sale'
      from public.brew_products where id=v_group.product_id and track_stock;
    update public.brew_products set stock_quantity=stock_quantity-v_group.qty,
      version=version+1,updated_at=now()
      where id=v_group.product_id and track_stock;
  end loop;
  insert into public.brew_sync_events(id,organization_id,branch_id,device_id,client_sale_id,event_type,payload,payload_version)
    values(v_order.id,v_order.organization_id,v_order.branch_id,v_order.kiosk_device_id,
      v_order.id,'sale.completed',jsonb_build_object('source','android_kiosk',
      'order_id',v_order.id,'total_centavos',v_order.total_centavos,'items',v_order.items),2);
  insert into public.brew_sales(
    organization_id,branch_id,device_id,client_sale_id,local_receipt_no,service_type,
    payment_method,subtotal_centavos,discount_centavos,total_centavos,created_offline_at)
  values(v_order.organization_id,v_order.branch_id,v_order.kiosk_device_id,v_order.id,
    'K-'||v_order.order_number,v_order.service_type,v_payment,
    v_order.subtotal_centavos,0,v_order.total_centavos,v_order.created_at)
  returning id into v_sale_id;
  for v_entry in select value from jsonb_array_elements(v_order.items) loop
    insert into public.brew_sale_items(organization_id,sale_id,line_no,product_name,
      options,quantity,unit_centavos,line_centavos)
    values(v_order.organization_id,v_sale_id,
      (select count(*)+1 from public.brew_sale_items where sale_id=v_sale_id),
      v_entry->>'name',jsonb_build_array(v_entry->>'options'),
      (v_entry->>'quantity')::integer,(v_entry->>'unit_centavos')::bigint,
      (v_entry->>'line_centavos')::bigint);
  end loop;
  insert into public.brew_payments(
    organization_id,sale_id,method,amount_centavos,tendered_centavos,change_centavos,verification)
  values(v_order.organization_id,v_sale_id,v_payment,v_order.total_centavos,
    p_tendered,v_change,case when p_source='verified_qrph' then 'gateway_verified' else 'manual' end);
  update public.brew_kiosk_orders
    set payment_status='paid',paid_at=now(),sale_id=v_sale_id,
        tendered_centavos=p_tendered,change_centavos=v_change,provider_qr_image=null
    where id=v_order.id;
  return jsonb_build_object('accepted',true,'duplicate',false,'order_id',v_order.id,'sale_id',v_sale_id);
end $$;
revoke all on function public.brew_kiosk_finalize_order(uuid,text,uuid,bigint,text) from public,anon,authenticated;
grant execute on function public.brew_kiosk_finalize_order(uuid,text,uuid,bigint,text) to service_role;
