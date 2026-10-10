using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CafeOabi.BrewPOS;

public static class Config {
    public const string Cloud="https://rfxzbuocxersgbshczbj.supabase.co";
    public const string PublicKey="sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
    public static readonly JsonSerializerOptions Json=new(JsonSerializerDefaults.Web) {WriteIndented=false,PropertyNameCaseInsensitive=true};
    public static string Peso(long cents)=>"₱"+(cents/100m).ToString("N2");
}
public sealed record MenuItem(Guid Id,Guid OrganizationId,Guid BranchId,string Name,string Category,long PriceCents,
    decimal Stock,bool TrackStock=true,bool Active=true,long Version=1) {
    public string DisplayPrice=>Config.Peso(PriceCents);
    public string StockLabel=>Stock.ToString("0.##");
}
public sealed record TicketLine(Guid ProductId,string Name,string Options,int Quantity,long UnitCents) {
    public long TotalCents=>checked(UnitCents*Quantity);
    public string PriceLabel=>Config.Peso(TotalCents);
}
public sealed record SaleEvent(Guid EventId,Guid InstallationId,string ReceiptNo,string Service,string Notes,
    long SubtotalCents,long DiscountCents,long TotalCents,long TenderedCents,long ChangeCents,
    DateTimeOffset CreatedAt,List<TicketLine> Lines) {
    public object CloudEvent()=>new {
        payload_version=2,event_type="sale.completed",event_id=EventId,client_sale_id=EventId,
        installation_id=InstallationId,local_receipt_no=ReceiptNo,
        created_offline_at_ms=CreatedAt.ToUnixTimeMilliseconds(),
        service_type=Service,payment_method="Cash",
        subtotal_centavos=SubtotalCents,discount_centavos=DiscountCents,
        total_centavos=TotalCents,tendered_centavos=TenderedCents,change_centavos=ChangeCents,
        notes=Notes,
        line_items=Lines.Select((line,index)=>new {
            line_no=index+1,product_id=line.ProductId,local_product_id=line.ProductId.ToString(),product_name=line.Name,
            options=line.Options,quantity=line.Quantity,unit_centavos=line.UnitCents,line_centavos=line.TotalCents
        }).ToArray()
    };
}
public sealed record SyncEntry(Guid Id,string Kind,string Payload,string Status,string? Error,string Receipt,DateTimeOffset Created) {
    public string Label=>$"{Kind} • {Created.ToLocalTime():MMM dd h:mm tt} • {Status}";
}
public sealed record Tenant(Guid OrganizationId,Guid BranchId,string BranchName,string Role);
public sealed record TokenSession(string Access,string Refresh,DateTimeOffset ExpiresAt,Guid UserId);
public sealed record StoredSession(TokenSession Session,Tenant Tenant,DateTimeOffset LastVerifiedUtc);
public static class BP1License {
    // Identical public ECDSA SPKI used by BrewPOS Android; no private signing key in the app.
    const string Key="MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEubpoEi/rbJAmA6XF4jgNecQqNq0AD3hfbKaEIpk2XuCW6l2UkboGuqUq25E470PETTrX/N8/5NyPKskSjmEgMw==";
    public static bool Valid(string token,string device16,DateTimeOffset now,DateTimeOffset lastSeen,out string message) {
        message="Unlicensed";
        if(now.AddMinutes(5)<lastSeen){message="Clock rollback blocked";return false;}
        try {
            var parts=token.Trim().Split('.');
            if(parts.Length!=3||parts[0]!="BP1")return false;
            static byte[] Url64(string x)=>Convert.FromBase64String(x.Replace('-','+').Replace('_','/').PadRight((x.Length+3)/4*4,'='));
            var raw=Url64(parts[1]);var sig=Url64(parts[2]);
            using var key=ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(Key),out _);
            if(!key.VerifyData(raw,sig,HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence))return false;
            var fields=Encoding.UTF8.GetString(raw).Split('|');
            if(fields.Length!=6||fields[0]!="1"||fields[1]!=device16)return false;
            var plan=fields[2];var seconds=long.Parse(fields[3]);
            if(plan=="LIFETIME"&&seconds==0){message="Lifetime active";return true;}
            if(plan is not ("TRIAL" or "MONTHLY")||seconds<=0)return false;
            var expiry=DateTimeOffset.FromUnixTimeSeconds(seconds);
            if(now<=expiry){message=plan+" active";return true;}
            if(now<=expiry.AddHours(72)){message="Renewal overdue; 72h grace";return true;}
            message="Activation expired";return false;
        }catch{message="Invalid activation";return false;}
    }
}
