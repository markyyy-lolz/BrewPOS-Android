using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

/// <summary>
/// Existing BrewPOS cloud integration: manager registers this HUB as a BrewPOS
/// device, then an AUTHORIZED staff member retries PAID counter tickets only.
/// Ingesting a pending unpaid kiosk ticket as a sale is impossible here.
/// No service-role key or Supabase credentials stored in the LAN hub.
/// </summary>
static class BrewSalesCloud {
    const string Base="https://rfxzbuocxersgbshczbj.supabase.co/";
    const string PublicKey="sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
    static readonly JsonSerializerOptions Json=new(JsonSerializerDefaults.Web);
    static async Task<JsonElement> Call(object body,string staffJwt){
        if(string.IsNullOrWhiteSpace(staffJwt)||staffJwt.Length<40||staffJwt.Length>8000)
            throw new UnauthorizedAccessException("A current BrewPOS staff session is required.");
        using var http=new HttpClient{BaseAddress=new Uri(Base),Timeout=TimeSpan.FromSeconds(20)};
        using var req=new HttpRequestMessage(HttpMethod.Post,"functions/v1/brewpos-sync") {
            Content=JsonContent.Create(body,options:Json)
        };
        req.Headers.Authorization=new AuthenticationHeaderValue("Bearer",staffJwt);
        req.Headers.TryAddWithoutValidation("apikey",PublicKey);
        using var result=await http.SendAsync(req);
        var raw=await result.Content.ReadAsStringAsync();
        if(!result.IsSuccessStatusCode)
            throw new InvalidOperationException("BrewPOS Cloud rejected the staff/device action: "+
                raw[..Math.Min(180,raw.Length)]);
        using var parsed=JsonDocument.Parse(raw);
        return parsed.RootElement.Clone();
    }
    public static async Task Register(HubSettings branch,Guid installation,string staffJwt){
        var ack=await Call(new{
            action="register",organization_id=branch.OrganizationId,
            branch_id=branch.BranchId,installation_id=installation,
            display_name="BrewPOS Kiosk LAN Hub"
        },staffJwt);
        if(ack.GetProperty("registered").GetBoolean()!=true)
            throw new InvalidOperationException("BrewPOS device registration not confirmed.");
    }
    public static async Task<object> SyncPaid(HubSettings branch,HubDb db,string staffJwt){
        var all=db.PendingPaidForCloud();
        var success=0;
        foreach(var order in all){
            // Exactly one immutable event per kiosk order. Existing BrewPOS
            // database enforces idempotency on event_id=client_sale_id.
            var lines=order.Lines.Select((x,index)=>new{
                line_no=index+1,
                local_product_id=x.ProductId.ToString(),
                product_name=x.Name,
                options=x.OptionLabel??"",
                quantity=x.Quantity,
                unit_centavos=x.UnitCentavos,
                line_centavos=checked(x.UnitCentavos*x.Quantity)
            }).ToArray();
            var subtotal=lines.Sum(x=>x.line_centavos);
            if(subtotal!=order.TotalCentavos||subtotal is <1 or >100000000)
                throw new InvalidOperationException("Local order total changed; hold for review.");
            var timestamp=DateTimeOffset.Parse(order.CreatedAt??throw new InvalidOperationException("No original order timestamp."));
            var ev=new {
                payload_version=1,
                event_type="sale.completed",
                event_id=order.Id,
                client_sale_id=order.Id,
                installation_id=db.InstallationId,
                local_receipt_no=order.Number,
                created_offline_at_ms=timestamp.ToUnixTimeMilliseconds(),
                service_type=order.Service,
                payment_method="Cash",
                subtotal_centavos=subtotal,
                discount_centavos=0,
                total_centavos=subtotal,
                tendered_centavos=subtotal,
                change_centavos=0,
                notes=order.Notes??"",
                line_items=lines
            };
            var response=await Call(new {
                action="sync",
                organization_id=branch.OrganizationId,
                branch_id=branch.BranchId,
                installation_id=db.InstallationId,
                @event=ev
            },staffJwt);
            if(response.GetProperty("accepted").GetBoolean()!=true||
                response.GetProperty("event_id").GetGuid()!=order.Id)
                throw new InvalidOperationException("BrewPOS did not acknowledge the original order UUID.");
            db.CloudAcknowledged(order.Id);
            success++;
        }
        return new{synced=success,pendingPaidForCloud=db.PendingPaidForCloud().Count};
    }
}
