using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace BrewPOS.Kiosk.Windows;
public sealed record DeviceConfig(string Url,string Role,string Key,string BranchName);
public sealed class DeviceStore{
    readonly string _file;
    public string Root {get;}
    public DeviceStore(string? directory=null){
        Root=directory??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Azurate","BrewPOS.Kiosk.Windows");
        Directory.CreateDirectory(Root);_file=Path.Combine(Root,"kiosk.sqlite3");
        using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText="""
          CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,payload TEXT NOT NULL,
            status TEXT NOT NULL,created TEXT NOT NULL,error TEXT);
          """;
        cmd.ExecuteNonQuery();
    }
    SqliteConnection Open(){
        var db=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=_file,Mode=SqliteOpenMode.ReadWriteCreate}.ToString());
        db.Open();using var c=db.CreateCommand();
        c.CommandText="PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;";c.ExecuteNonQuery();
        return db;
    }
    string? Get(string k){
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="SELECT value FROM meta WHERE key=$k";c.Parameters.AddWithValue("$k",k);
        return c.ExecuteScalar()?.ToString();
    }
    void Put(string k,string v){
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="INSERT INTO meta(key,value) VALUES($k,$v) ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        c.Parameters.AddWithValue("$k",k);c.Parameters.AddWithValue("$v",v);c.ExecuteNonQuery();
    }
    public string InstallationId {
        get {
            var current=Get("installation_id");
            if(Guid.TryParse(current,out _))return current!;
            var id=Guid.NewGuid().ToString();Put("installation_id",id);return id;
        }
    }
    static readonly byte[] Entropy=Encoding.UTF8.GetBytes("BrewPOS.Kiosk.Windows.Pairing.v1");
    public DeviceConfig? LoadConfig(){
        try {
            var raw=Get("pairing");if(raw==null)return null;
            var bytes=ProtectedData.Unprotect(Convert.FromBase64String(raw),Entropy,DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<DeviceConfig>(bytes);
        }catch{return null;}
    }
    public void SaveConfig(DeviceConfig cfg){
        var bytes=JsonSerializer.SerializeToUtf8Bytes(cfg);
        Put("pairing",Convert.ToBase64String(ProtectedData.Protect(bytes,Entropy,DataProtectionScope.CurrentUser)));
    }
    public string MenuJson=>Get("menu")??"[]";
    public void SaveMenu(string json){
        using var doc=JsonDocument.Parse(json);
        if(doc.RootElement.ValueKind!=JsonValueKind.Array||doc.RootElement.GetArrayLength()>1000)
            throw new InvalidOperationException("Invalid menu from hub.");
        if(Outstanding())throw new InvalidOperationException("Local unsent orders prevent menu replacement.");
        Put("menu",json);
    }
    public bool Outstanding(){
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="SELECT COUNT(*) FROM outbox WHERE status IN ('queued','review')";
        return Convert.ToInt64(c.ExecuteScalar())>0;
    }
    public (string Id,string Payload,string Number) SaveOrder(JsonElement payload){
        if(payload.GetProperty("paymentMethod").GetString()!="counter")throw new InvalidOperationException("Pay at Counter only.");
        var service=payload.GetProperty("service").GetString();
        if(service is not ("Dine-in" or "Takeout"))throw new InvalidOperationException("Invalid service.");
        var lines=payload.GetProperty("lines");
        if(lines.ValueKind!=JsonValueKind.Array||lines.GetArrayLength() is <1 or >80)
            throw new InvalidOperationException("Empty or oversize order.");
        using var catalog=JsonDocument.Parse(MenuJson);
        var map=catalog.RootElement.EnumerateArray().ToDictionary(x=>x.GetProperty("id").GetString()!,x=>x);
        long total=0;
        var verified=new List<object>();
        foreach(var line in lines.EnumerateArray()){
            var id=line.GetProperty("productId").GetString()!;
            if(!map.TryGetValue(id,out var product)||!product.GetProperty("active").GetBoolean())
                throw new InvalidOperationException("Unavailable product.");
            var qty=line.GetProperty("quantity").GetInt32();
            if(qty is <1 or >99||line.GetProperty("productVersion").GetInt64()!=product.GetProperty("version").GetInt64())
                throw new InvalidOperationException("Menu/quantity is out of date.");
            var price=product.GetProperty("priceCentavos").GetInt64();
            var selections=line.GetProperty("options");
            foreach(var g in product.GetProperty("modifiers").EnumerateArray()){
                var gid=g.GetProperty("id").GetString();
                var selected=selections.EnumerateArray().FirstOrDefault(x=>x.GetProperty("groupId").GetString()==gid);
                string?[] ids=selected.ValueKind==JsonValueKind.Undefined?Array.Empty<string?>():
                    selected.GetProperty("optionIds").EnumerateArray().Select(x=>x.GetString()).ToArray();
                if(g.GetProperty("required").GetBoolean()&&ids.Length==0||
                    !g.GetProperty("multiple").GetBoolean()&&ids.Length>1||
                    ids.Length>g.GetProperty("maxSelections").GetInt32())
                    throw new InvalidOperationException("Invalid drink options.");
                foreach(var optionId in ids){
                    var option=g.GetProperty("options").EnumerateArray().FirstOrDefault(x=>x.GetProperty("id").GetString()==optionId);
                    if(option.ValueKind==JsonValueKind.Undefined)throw new InvalidOperationException("Invalid modifier.");
                    price=checked(price+option.GetProperty("deltaCentavos").GetInt64());
                }
            }
            if(price!=line.GetProperty("unitCentavos").GetInt64()||price<0)
                throw new InvalidOperationException("Price discrepancy; please reselect.");
            total=checked(total+price*qty);
            verified.Add(new{
              productId=id,name=product.GetProperty("name").GetString(),quantity=qty,
              unitCentavos=price,productVersion=product.GetProperty("version").GetInt64(),
              options=selections.Clone(),optionLabel=line.GetProperty("optionLabel").GetString()
            });
        }
        if(total is <1 or >100000000)throw new InvalidOperationException("Order total invalid.");
        var idNew=Guid.NewGuid().ToString();
        var number="K-"+idNew.Replace("-","")[..9].ToUpperInvariant();
        var order=new{
          id=idNew,number,installationId=InstallationId,service,
          notes=(payload.GetProperty("notes").GetString()??"")[..Math.Min(60,(payload.GetProperty("notes").GetString()??"").Length)],
          paymentMethod="counter",lines=verified,totalCentavos=total,status="AwaitingPayment"
        };
        var raw=JsonSerializer.Serialize(order);
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="INSERT INTO outbox(id,payload,status,created) VALUES($id,$p,'queued',$at)";
        c.Parameters.AddWithValue("$id",idNew);c.Parameters.AddWithValue("$p",raw);
        c.Parameters.AddWithValue("$at",DateTimeOffset.UtcNow.ToString("O"));c.ExecuteNonQuery();
        return(idNew,raw,number);
    }
    public List<(string Id,string Payload)> Pending(){
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="SELECT id,payload FROM outbox WHERE status='queued' ORDER BY rowid ASC LIMIT 100";
        using var r=c.ExecuteReader();var result=new List<(string,string)>();
        while(r.Read())result.Add((r.GetString(0),r.GetString(1)));
        return result;
    }
    public void Mark(string id,string status,string? error=null){
        if(status is not ("sent" or "review"))throw new InvalidOperationException("Invalid order status.");
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="UPDATE outbox SET status=$status,error=$e WHERE id=$id AND status='queued'";
        c.Parameters.AddWithValue("$status",status);c.Parameters.AddWithValue("$e",error??(object)DBNull.Value);
        c.Parameters.AddWithValue("$id",id);c.ExecuteNonQuery();
    }
}
