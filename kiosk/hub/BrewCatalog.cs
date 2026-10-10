using System.Net.Http.Headers;
using System.Text.Json;

/// <summary>
/// Pulls the existing BrewPOS tenant menu under a real OWNER/MANAGER JWT.
/// The local HUB holds only role-specific LAN device keys; Supabase staff
/// tokens are used for this one request and NEVER stored in SQLite.
/// </summary>
static class BrewCatalog {
    const string Base="https://rfxzbuocxersgbshczbj.supabase.co/";
    const string Publishable="sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
    static readonly JsonSerializerOptions Opt=new(JsonSerializerDefaults.Web);
    static async Task<JsonElement> Get(HttpClient http,string path,string jwt) {
        using var req=new HttpRequestMessage(HttpMethod.Get,path);
        req.Headers.Authorization=new AuthenticationHeaderValue("Bearer",jwt);
        req.Headers.TryAddWithoutValidation("apikey",Publishable);
        using var response=await http.SendAsync(req);
        if(!response.IsSuccessStatusCode)throw new UnauthorizedAccessException(
            "BrewPOS Auth or tenant permission check failed ("+(int)response.StatusCode+").");
        using var json=JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        return json.RootElement.Clone();
    }
    public static async Task<List<KioskProduct>> Load(HubSettings branch,string staffJwt,List<KioskProduct> old) {
        if(string.IsNullOrWhiteSpace(staffJwt)||staffJwt.Length<40||staffJwt.Length>8000)
            throw new UnauthorizedAccessException("Provide an unexpired BrewPOS staff access token.");
        using var client=new HttpClient{BaseAddress=new Uri(Base),Timeout=TimeSpan.FromSeconds(16)};
        var user=await Get(client,"auth/v1/user",staffJwt);
        if(!user.TryGetProperty("id",out var uid)||!Guid.TryParse(uid.GetString(),out var userId))
            throw new UnauthorizedAccessException("Unverified BrewPOS user.");
        var memberships=await Get(client,"rest/v1/brew_memberships?select=role,organization_id"+
            "&organization_id=eq."+branch.OrganizationId+
            "&user_id=eq."+userId+"&is_active=eq.true",staffJwt);
        if(!memberships.EnumerateArray().Any(m=>
             m.GetProperty("organization_id").GetGuid()==branch.OrganizationId &&
             m.GetProperty("role").GetString() is "owner" or "manager"))
            throw new UnauthorizedAccessException("Only a BrewPOS owner/manager may publish a kiosk menu.");
        var active=await Get(client,"rest/v1/brew_branches?select=id,is_active"+
            "&organization_id=eq."+branch.OrganizationId+"&id=eq."+branch.BranchId+
            "&is_active=eq.true",staffJwt);
        if(active.GetArrayLength()!=1)throw new UnauthorizedAccessException("Branch not active or not authorized.");
        var menu=await Get(client,"rest/v1/brew_products?select=id,organization_id,branch_id,name,category,"+
            "price_centavos,stock_quantity,track_stock,active,version"+
            "&organization_id=eq."+branch.OrganizationId+"&branch_id=eq."+branch.BranchId+
            "&order=name.asc&limit=1000",staffJwt);
        var previous=old.ToDictionary(x=>x.Id);
        var updated=new List<KioskProduct>();
        foreach(var p in menu.EnumerateArray()) {
            if(p.GetProperty("organization_id").GetGuid()!=branch.OrganizationId ||
               p.GetProperty("branch_id").GetGuid()!=branch.BranchId)
                throw new InvalidOperationException("Untrusted cross-tenant product; menu not changed.");
            var id=p.GetProperty("id").GetGuid();
            var price=p.GetProperty("price_centavos").GetInt64();
            var qty=p.GetProperty("stock_quantity").GetDecimal();
            var inStock=!p.GetProperty("track_stock").GetBoolean()||qty>0;
            var activeProduct=p.GetProperty("active").GetBoolean()&&inStock;
            if(price<0||price>100000000)throw new InvalidOperationException("Unsupported menu price.");
            previous.TryGetValue(id,out var curated);
            // Modifiers/add-on prices come from the café manager's approved
            // catalog, NEVER guessed based on the product category.
            updated.Add(new KioskProduct(id,
                p.GetProperty("name").GetString()??"Unnamed",
                p.GetProperty("category").GetString()??"Other",
                curated?.Description,price,p.GetProperty("version").GetInt64(),
                activeProduct,curated?.Modifiers??new List<KioskGroup>()));
        }
        return updated;
    }
}
