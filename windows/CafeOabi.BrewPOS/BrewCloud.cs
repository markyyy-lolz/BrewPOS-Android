using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;

namespace CafeOabi.BrewPOS;

/// <summary>Uses the existing BrewPOS project, Auth JWT, and brewpos-sync edge
/// function. No service_role credentials or arbitrary tenant selection.</summary>
public sealed class BrewCloud {
    readonly HttpClient _http=new(){BaseAddress=new Uri(Config.Cloud+"/"),Timeout=TimeSpan.FromSeconds(25)};
    public TokenSession? Session {get;private set;}
    public void Use(TokenSession? session)=>Session=session;
    async Task<JsonElement> Post(string path,object body,bool authenticated=false) {
        using var request=new HttpRequestMessage(HttpMethod.Post,path){
            Content=JsonContent.Create(body,options:Config.Json)
        };
        request.Headers.TryAddWithoutValidation("apikey",Config.PublicKey);
        if(authenticated) {
            if(Session==null)throw new InvalidOperationException("Cloud account sign-in required.");
            request.Headers.TryAddWithoutValidation("authorization","Bearer "+Session.Access);
        }
        using var res=await _http.SendAsync(request);
        var raw=await res.Content.ReadAsStringAsync();
        if(!res.IsSuccessStatusCode) {
            string error=raw;
            try {
                using var json=JsonDocument.Parse(raw);
                var obj=json.RootElement;
                error=obj.TryGetProperty("error",out var er)?er.GetString()??raw:
                    obj.TryGetProperty("msg",out var msg)?msg.GetString()??raw:raw;
            }catch(JsonException) { }
            throw new BrewCloudException((int)res.StatusCode,error);
        }
        using var doc=JsonDocument.Parse(raw);
        return doc.RootElement.Clone();
    }
    static TokenSession ParseSession(JsonElement auth) {
        var access=auth.GetProperty("access_token").GetString()!;
        var refresh=auth.GetProperty("refresh_token").GetString()!;
        var user=auth.GetProperty("user").GetProperty("id").GetGuid();
        var expiry=auth.GetProperty("expires_in").GetInt64();
        return new TokenSession(access,refresh,DateTimeOffset.UtcNow.AddSeconds(Math.Max(60,expiry)),user);
    }
    public async Task<TokenSession> Login(string email,string password) {
        if(string.IsNullOrWhiteSpace(email)||string.IsNullOrWhiteSpace(password))
            throw new InvalidOperationException("Enter your BrewPOS email and password.");
        var data=await Post("auth/v1/token?grant_type=password",new {email=email.Trim(),password});
        Session=ParseSession(data);return Session;
    }
    public async Task Refresh() {
        var saved=Session ??throw new InvalidOperationException("Sign in first.");
        var data=await Post("auth/v1/token?grant_type=refresh_token",new{refresh_token=saved.Refresh});
        Session=ParseSession(data);
    }
    public async Task EnsureFresh() {
        if(Session==null)throw new InvalidOperationException("Sign in to BrewPOS Cloud first.");
        if(Session.ExpiresAt<=DateTimeOffset.UtcNow.AddMinutes(2))await Refresh();
    }
    public async Task<JsonElement> SyncAction(object data) {
        await EnsureFresh();
        return await Post("functions/v1/brewpos-sync",data,true);
    }
    public async Task<Tenant> CafeOabiTenant() {
        var result=await SyncAction(new{action="whoami"});
        var candidates=new List<Tenant>();
        foreach(var membership in result.GetProperty("memberships").EnumerateArray()) {
            var org=membership.GetProperty("organization_id").GetGuid();
            var role=membership.GetProperty("role").GetString()??"viewer";
            foreach(var branch in membership.GetProperty("branches").EnumerateArray()) {
                var name=branch.GetProperty("name").GetString()??"";
                if(name.Contains("Oabi",StringComparison.OrdinalIgnoreCase))
                    candidates.Add(new Tenant(org,branch.GetProperty("id").GetGuid(),name,role));
            }
        }
        if(candidates.Count==0)
            throw new InvalidOperationException("Your account has no Café Oabi branch membership. Azurate owner must create Café Oabi and invite its verified staff first.");
        if(candidates.Count>1)
            throw new InvalidOperationException("Multiple Café Oabi branches found. Select a unique branch through onboarding first.");
        return candidates[0];
    }
    public async Task Register(Tenant branch,Guid install) {
        if(branch.Role is not ("owner" or "manager"))
            throw new InvalidOperationException("Initial Windows device registration requires a café owner/manager.");
        var result=await SyncAction(new{
            action="register",organization_id=branch.OrganizationId,branch_id=branch.BranchId,
            installation_id=install,display_name="Café Oabi Windows POS"
        });
        if(!result.GetProperty("registered").GetBoolean())
            throw new InvalidOperationException("BrewPOS Cloud did not confirm device registration.");
    }
    public async Task<List<MenuItem>> DownloadMenu(Tenant tenant,Guid installation) {
        var catalog=await SyncAction(new {action="catalog",organization_id=tenant.OrganizationId,
            branch_id=tenant.BranchId,installation_id=installation});
        var products=new List<MenuItem>();
        foreach(var p in catalog.GetProperty("products").EnumerateArray()) {
            var org=p.GetProperty("organization_id").GetGuid();var branch=p.GetProperty("branch_id").GetGuid();
            if(org!=tenant.OrganizationId||branch!=tenant.BranchId)
                throw new InvalidOperationException("Cross-tenant product response rejected.");
            products.Add(new MenuItem(
                p.GetProperty("id").GetGuid(),org,branch,
                p.GetProperty("name").GetString()??"",
                p.GetProperty("category").GetString()??"Coffee",
                p.GetProperty("price_centavos").GetInt64(),
                p.GetProperty("stock_quantity").GetDecimal(),
                p.GetProperty("track_stock").GetBoolean(),p.GetProperty("active").GetBoolean(),
                p.GetProperty("version").GetInt64()
            ));
        }
        return products;
    }
    public async Task ReplaySale(Tenant tenant,Guid installation,SyncEntry row) {
        if(row.Kind is not ("sale.completed" or "stock.adjusted"))
            throw new InvalidOperationException("Inventory/event sync is not yet supported by the current BrewPOS endpoint.");
        using var d=JsonDocument.Parse(row.Payload);
        var result=await SyncAction(new{
            action="sync",organization_id=tenant.OrganizationId,branch_id=tenant.BranchId,
            installation_id=installation,@event=d.RootElement.Clone()
        });
        if(!result.GetProperty("accepted").GetBoolean() ||
            result.GetProperty("event_id").GetGuid()!=row.Id ||
            !result.TryGetProperty("inventory_applied",out var applied)||!applied.GetBoolean())
            throw new InvalidOperationException("BrewPOS Cloud did not acknowledge this sale. Keep pending.");
    }
}
public sealed class BrewCloudException(int code,string message):Exception(message) {
    public int Code {get;}=code;
    public bool Retryable=>Code==408||Code==429||Code>=500;
}
