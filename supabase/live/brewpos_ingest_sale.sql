CREATE OR REPLACE FUNCTION public.brew_ingest_sale(p_organization_id uuid, p_branch_id uuid, p_device_id uuid, p_payload jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO 'pg_catalog', 'public'
AS $function$
declare
  v_event_id uuid;
  v_sale_uuid uuid;
  v_existing public.brew_sync_events%rowtype;
  v_device public.brew_devices%rowtype;
  v_line jsonb;
  v_subtotal bigint;
  v_items_sum bigint := 0;
  v_qty integer;
  v_unit bigint;
  v_line_amount bigint;
  v_total bigint;
  v_discount bigint;
  v_tendered bigint;
  v_change bigint;
  v_created timestamptz;
  v_item_no integer := 0;
  v_payment text;
  v_service text;
  v_receipt text;
  v_inserted boolean;
begin
  if p_payload is null or jsonb_typeof(p_payload) <> 'object' then raise exception 'Invalid payload'; end if;
  if p_payload->>'event_type' <> 'sale.completed' or (p_payload->>'payload_version') <> '1' then
    raise exception 'Unsupported event format';
  end if;
  v_event_id := (p_payload->>'event_id')::uuid;
  v_sale_uuid := (p_payload->>'client_sale_id')::uuid;
  if v_event_id is null or v_sale_uuid is null or v_event_id <> v_sale_uuid then
    raise exception 'Invalid event ID';
  end if;
  select * into v_device from public.brew_devices
    where id = p_device_id and branch_id = p_branch_id
      and organization_id = p_organization_id and is_active;
  if not found then raise exception 'Device is not registered or inactive'; end if;
  if (p_payload->>'installation_id')::uuid is distinct from v_device.installation_id then
    raise exception 'Installation ID does not match registered device';
  end if;
  perform 1 from public.brew_branches
    where id = p_branch_id and organization_id = p_organization_id and is_active;
  if not found then raise exception 'Inactive or unknown branch'; end if;
  v_receipt := trim(p_payload->>'local_receipt_no');
  v_service := p_payload->>'service_type';
  v_payment := p_payload->>'payment_method';
  if length(v_receipt) < 5 or length(v_receipt) > 90 then raise exception 'Invalid receipt number'; end if;
  if v_service not in ('Dine-in','Takeout') or v_payment not in ('Cash','GCash','Maya','Card') then raise exception 'Invalid payment/service'; end if;
  v_subtotal := (p_payload->>'subtotal_centavos')::bigint;
  v_total := (p_payload->>'total_centavos')::bigint;
  v_discount := (p_payload->>'discount_centavos')::bigint;
  v_tendered := (p_payload->>'tendered_centavos')::bigint;
  v_change := (p_payload->>'change_centavos')::bigint;
  if v_subtotal is null or v_total is null or v_discount is null or v_tendered is null or v_change is null
     or v_subtotal < 0 or v_discount < 0 or v_discount > v_subtotal or v_total < 0
     or v_total <> v_subtotal - v_discount
     or v_tendered < v_total or v_change <> v_tendered - v_total
     or v_total > 100000000 then raise exception 'Invalid monetary values'; end if;
  v_created := to_timestamp((p_payload->>'created_offline_at_ms')::numeric / 1000);
  if v_created is null or v_created > now() + interval '1 day' then raise exception 'Invalid sale time'; end if;
  if jsonb_typeof(p_payload->'line_items') <> 'array'
     or jsonb_array_length(p_payload->'line_items') not between 1 and 80 then
    raise exception 'Sale must contain 1-80 line items'; end if;

  -- Validate the complete sale before writing anything.
  for v_line in select value from jsonb_array_elements(p_payload->'line_items') loop
    v_qty := (v_line->>'quantity')::integer;
    v_unit := (v_line->>'unit_centavos')::bigint;
    v_line_amount := (v_line->>'line_centavos')::bigint;
    if v_qty is null or v_qty < 1 or v_qty > 999
       or v_unit is null or v_unit < 0 or v_line_amount is null
       or v_line_amount <> v_unit * v_qty
       or length(coalesce(v_line->>'product_name','')) not between 1 and 240 then
      raise exception 'Invalid sale line';
    end if;
    v_items_sum := v_items_sum + v_line_amount;
  end loop;
  if v_items_sum <> v_subtotal then raise exception 'Item totals mismatch'; end if;

  insert into public.brew_sync_events
    (id,organization_id,branch_id,device_id,client_sale_id,event_type,payload,payload_version)
  values
    (v_event_id,p_organization_id,p_branch_id,p_device_id,v_sale_uuid,'sale.completed',p_payload,1)
  on conflict(id) do nothing;
  get diagnostics v_inserted = row_count;
  if not v_inserted then
    select * into v_existing from public.brew_sync_events where id = v_event_id;
    if not found or v_existing.organization_id <> p_organization_id
      or v_existing.device_id <> p_device_id
      or v_existing.payload is distinct from p_payload then
      raise exception 'Conflicting event ID';
    end if;
    return jsonb_build_object('accepted',true,'event_id',v_event_id,'duplicate',true);
  end if;

  insert into public.brew_sales (
    organization_id,branch_id,device_id,client_sale_id,local_receipt_no,
    service_type,payment_method,subtotal_centavos,discount_centavos,total_centavos,created_offline_at
  ) values (
    p_organization_id,p_branch_id,p_device_id,v_sale_uuid,v_receipt,v_service,v_payment,
    v_subtotal,v_discount,v_total,v_created
  ) returning id into v_sale_uuid;

  for v_line in select value from jsonb_array_elements(p_payload->'line_items') loop
    v_item_no := v_item_no + 1;
    insert into public.brew_sale_items (
      organization_id,sale_id,line_no,product_name,options,quantity,unit_centavos,line_centavos
    ) values (
      p_organization_id,v_sale_uuid,v_item_no,v_line->>'product_name',
      case when coalesce(v_line->>'options','') = '' then '[]'::jsonb
      else jsonb_build_array(v_line->>'options') end,
      (v_line->>'quantity')::integer,(v_line->>'unit_centavos')::bigint,
      (v_line->>'line_centavos')::bigint
    );
  end loop;
  insert into public.brew_payments(
    organization_id,sale_id,method,amount_centavos,tendered_centavos,change_centavos,verification
  ) values(
    p_organization_id,v_sale_uuid,v_payment,v_total,v_tendered,v_change,'manual'
  );
  return jsonb_build_object('accepted',true,'event_id',v_event_id,'duplicate',false);
end;
$function$


revoke all on function public.brew_ingest_sale(uuid,uuid,uuid,jsonb) from public, anon, authenticated;
grant execute on function public.brew_ingest_sale(uuid,uuid,uuid,jsonb) to service_role;
