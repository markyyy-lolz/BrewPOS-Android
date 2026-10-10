-- Additive inventory deltas, never last-writer-wins absolute quantities.
-- Invoker-only; callable by service_role after Edge Auth validation.
create or replace function public.brew_ingest_stock(
  p_organization_id uuid, p_branch_id uuid, p_device_id uuid, p_actor_id uuid, p_payload jsonb
) returns jsonb language plpgsql security invoker set search_path=pg_catalog,public as $$
declare
  v_id uuid; v_product uuid; v_delta numeric; v_existing public.brew_sync_events%rowtype;
  v_inserted integer;
begin
  if not exists(select 1 from public.brew_memberships where organization_id=p_organization_id
    and user_id=p_actor_id and is_active and role in ('owner','manager')) then
    raise exception 'Manager authorization required';
  end if;
  if jsonb_typeof(p_payload) is distinct from 'object'
    or p_payload->>'event_type' is distinct from 'stock.adjusted'
    or p_payload->>'payload_version' is distinct from '2' then raise exception 'Invalid stock event'; end if;
  v_id := (p_payload->>'event_id')::uuid;
  v_product := (p_payload->>'product_id')::uuid;
  v_delta := (p_payload->>'delta')::numeric;
  if v_id is null or v_product is null or v_delta is null or v_delta=0
    or abs(v_delta)>100000 or v_delta<>round(v_delta,3)
    or length(trim(coalesce(p_payload->>'reason',''))) not between 3 and 240 then
    raise exception 'Invalid stock movement';
  end if;
  if not exists(select 1 from public.brew_devices d join public.brew_branches b
    on b.id=d.branch_id and b.organization_id=d.organization_id
    where d.id=p_device_id and d.organization_id=p_organization_id and d.branch_id=p_branch_id
    and d.installation_id=(p_payload->>'installation_id')::uuid and d.is_active and b.is_active) then
    raise exception 'Invalid device or branch';
  end if;
  insert into public.brew_sync_events(id,organization_id,branch_id,device_id,event_type,payload,payload_version)
    values(v_id,p_organization_id,p_branch_id,p_device_id,'stock.adjusted',p_payload,2) on conflict(id) do nothing;
  get diagnostics v_inserted=row_count;
  if v_inserted=0 then
    select * into v_existing from public.brew_sync_events where id=v_id;
    if v_existing.organization_id is distinct from p_organization_id
      or v_existing.branch_id is distinct from p_branch_id or v_existing.device_id is distinct from p_device_id
      or v_existing.payload is distinct from p_payload then raise exception 'Conflicting event ID'; end if;
    return jsonb_build_object('accepted',true,'event_id',v_id,'duplicate',true,'inventory_applied',true);
  end if;
  perform 1 from public.brew_products where id=v_product and organization_id=p_organization_id
    and branch_id=p_branch_id and track_stock for update;
  if not found then raise exception 'Unknown or untracked branch product'; end if;
  insert into public.brew_inventory_movements(organization_id,branch_id,product_id,source_event_id,change_qty,reason)
    values(p_organization_id,p_branch_id,v_product,v_id,v_delta,'adjustment');
  update public.brew_products set stock_quantity=stock_quantity+v_delta,version=version+1,updated_at=now()
    where id=v_product;
  return jsonb_build_object('accepted',true,'event_id',v_id,'duplicate',false,'inventory_applied',true);
end;
$$;
revoke all on function public.brew_ingest_stock(uuid,uuid,uuid,uuid,jsonb) from public,anon,authenticated;
grant execute on function public.brew_ingest_stock(uuid,uuid,uuid,uuid,jsonb) to service_role;

-- One statement produces a complete MVCC snapshot, avoiding REST row-limit truncation.
create or replace function public.brew_catalog(p_organization_id uuid,p_branch_id uuid)
returns jsonb language sql stable security invoker set search_path=pg_catalog,public as $$
  select coalesce(jsonb_agg(to_jsonb(p) order by p.id),'[]'::jsonb)
  from public.brew_products p where organization_id=p_organization_id and branch_id=p_branch_id;
$$;
revoke all on function public.brew_catalog(uuid,uuid) from public,anon,authenticated;
grant execute on function public.brew_catalog(uuid,uuid) to service_role;
