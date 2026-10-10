using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

var builder=WebApplication.CreateBuilder(args);
var settings=HubSettings.FromEnvironment();
builder.WebHost.UseUrls(settings.HubUrl);
builder.Services.AddSingleton(settings);
builder.Services.AddSingleton<HubDb>();
var app=builder.Build();
app.Use(async(ctx,next)=>{
    if(ctx.Request.Path=="/healthz") {await next();return;}
    if(ctx.Request.ContentLength>256_000){ctx.Response.StatusCode=413;return;}
    var bearer=ctx.Request.Headers.Authorization.ToString();
    var token=bearer.StartsWith("Bearer ",StringComparison.OrdinalIgnoreCase)?bearer[7..].Trim():"";
    var roles=new[]{"admin","kiosk","cashier","kitchen"};
    var granted=roles.FirstOrDefault(role=>HubSettings.FixedEquals(token,settings.Key(role)));
    if(granted==null){ctx.Response.StatusCode=401;await ctx.Response.WriteAsJsonAsync(new{error="Pair with an authorized BrewPOS hub device."});return;}
    ctx.Items["role"]=granted;
    await next();
});
app.MapGet("/healthz",()=>Results.Ok(new{service="BrewPOS Kiosk LAN Hub",status="ok"}));
app.MapGet("/v1/catalog",(HttpContext ctx,HubDb db)=>{
    if(!Has(ctx,"admin","kiosk","cashier","kitchen"))return Results.Forbid();
    return Results.Ok(new{organizationId=settings.OrganizationId,branchId=settings.BranchId,products=db.Menu()});
});
app.MapPut("/v1/catalog",async(HttpContext ctx,HubDb db,JsonElement body)=>{
    if(!Has(ctx,"admin"))return Results.Forbid();
    try {
        if(body.ValueKind!=JsonValueKind.Array || body.GetArrayLength()>1000) return Results.BadRequest(new{error="Invalid menu"});
        var products=JsonSerializer.Deserialize<List<KioskProduct>>(body,Options.Web)??[];
        db.ReplaceMenu(products);
        return Results.Ok(new{saved=products.Count});
    }catch(Exception ex)when(ex is JsonException or InvalidOperationException or SqliteException){
        return Results.BadRequest(new{error=ex.Message});
    }
});
app.MapPost("/v1/catalog/refresh",async(HttpContext ctx,HubDb db,HubSettings hub,StaffRefreshRequest request)=>{
    if(!Has(ctx,"admin"))return Results.Forbid();
    try{
        var products=await BrewCatalog.Load(hub,request.StaffAccessToken,db.Menu());
        db.ReplaceMenu(products);
        return Results.Ok(new{saved=products.Count,from="BrewPOS Supabase verified staff membership"});
    }catch(UnauthorizedAccessException ex){return Results.Json(new{error=ex.Message},statusCode:403);}
    catch(Exception ex)when(ex is HttpRequestException or JsonException or InvalidOperationException){
        return Results.BadRequest(new{error=ex.Message});
    }
});
app.MapPost("/v1/cloud/register",async(HttpContext ctx,HubDb db,HubSettings hub,StaffRefreshRequest request)=>{
    if(!Has(ctx,"admin"))return Results.Forbid();
    try {
        await BrewSalesCloud.Register(hub,db.InstallationId,request.StaffAccessToken);
        return Results.Ok(new{registered=true,installationId=db.InstallationId});
    }catch(Exception ex)when(ex is UnauthorizedAccessException or HttpRequestException or InvalidOperationException){
        return Results.BadRequest(new{error=ex.Message});
    }
});
app.MapPost("/v1/cloud/sync-paid",async(HttpContext ctx,HubDb db,HubSettings hub,StaffRefreshRequest request)=>{
    if(!Has(ctx,"admin","cashier"))return Results.Forbid();
    try{
        var result=await BrewSalesCloud.SyncPaid(hub,db,request.StaffAccessToken);
        return Results.Ok(result);
    }catch(Exception ex)when(ex is UnauthorizedAccessException or HttpRequestException or InvalidOperationException){
        return Results.BadRequest(new{error=ex.Message});
    }
});
app.MapPost("/v1/orders",async(HttpContext ctx,HubDb db,KioskOrder request)=>{
    if(!Has(ctx,"kiosk","cashier","admin"))return Results.Forbid();
    try {var result=db.Place(request);return Results.Ok(result);}
    catch(ConflictException ex){return Results.Conflict(new{error=ex.Message});}
    catch(InvalidOperationException ex){return Results.BadRequest(new{error=ex.Message});}
});
app.MapGet("/v1/orders",(HttpContext ctx,HubDb db)=>{
    var role=Role(ctx);
    if(role is not ("admin" or "cashier" or "kitchen"))return Results.Forbid();
    return Results.Ok(new{orders=db.Orders(role=="kitchen")});
});
app.MapGet("/v1/orders/{id:guid}",(HttpContext ctx,HubDb db,Guid id)=>{
    if(!Has(ctx,"kiosk","cashier","kitchen","admin"))return Results.Forbid();
    var order=db.Get(id);
    if(order==null)return Results.NotFound();
    return Results.Ok(new{order.Id,order.Number,order.Status});
});
app.MapPatch("/v1/orders/{id:guid}/status",(HttpContext ctx,HubDb db,Guid id,StatusRequest command)=>{
    var role=Role(ctx);
    if(role is not ("admin" or "cashier" or "kitchen"))return Results.Forbid();
    try{return Results.Ok(db.Transition(id,command.Next,role));}
    catch(ConflictException ex){return Results.Conflict(new{error=ex.Message});}
    catch(InvalidOperationException ex){return Results.BadRequest(new{error=ex.Message});}
});
app.Run();
static string Role(HttpContext ctx)=>ctx.Items["role"] as string??"";
static bool Has(HttpContext ctx,params string[] names)=>names.Contains(Role(ctx));

sealed record HubSettings(Guid OrganizationId,Guid BranchId,string HubUrl,string AdminKey,string KioskKey,string CashierKey,string KitchenKey){
    public string Key(string role)=>role switch{"admin"=>AdminKey,"kiosk"=>KioskKey,"cashier"=>CashierKey,"kitchen"=>KitchenKey,_=>""};
    public static HubSettings FromEnvironment(){
        string Need(string name) => Environment.GetEnvironmentVariable(name)?.Trim()
            is {Length:>0} value ? value :throw new InvalidOperationException("Set "+name+" before starting BrewPOS Hub.");
        var keys=new[]{Need("BREW_HUB_ADMIN_KEY"),Need("BREW_HUB_KIOSK_KEY"),Need("BREW_HUB_CASHIER_KEY"),Need("BREW_HUB_KITCHEN_KEY")};
        if(keys.Any(x=>x.Length<32)||keys.Distinct().Count()!=4)throw new InvalidOperationException("Use FOUR distinct random 32+ character device keys.");
        var uri=Need("BREW_HUB_URL");
        if(!Uri.TryCreate(uri,UriKind.Absolute,out var u)||u.Scheme!="https")
            throw new InvalidOperationException("Hub must serve HTTPS. Configure a certificate trusted by the kiosk devices.");
        return new(Guid.Parse(Need("BREW_HUB_ORGANIZATION_ID")),Guid.Parse(Need("BREW_HUB_BRANCH_ID")),
            uri,keys[0],keys[1],keys[2],keys[3]);
    }
    public static bool FixedEquals(string a,string b) {
        var x=SHA256.HashData(Encoding.UTF8.GetBytes(a));
        var y=SHA256.HashData(Encoding.UTF8.GetBytes(b));
        return !string.IsNullOrEmpty(a)&&CryptographicOperations.FixedTimeEquals(x,y);
    }
}
static class Options {
    public static readonly JsonSerializerOptions Web=new(JsonSerializerDefaults.Web){PropertyNameCaseInsensitive=true};
}
sealed record KioskChoice(string GroupId,string[] OptionIds);
sealed record KioskOption(string Id,string Name,long DeltaCentavos);
sealed record KioskGroup(string Id,string Name,bool Required,bool Multiple,int MaxSelections,List<KioskOption> Options);
sealed record KioskProduct(Guid Id,string Name,string Category,string? Description,long PriceCentavos,long Version,bool Active,List<KioskGroup> Modifiers);
sealed record KioskLine(Guid ProductId,string Name,int Quantity,long UnitCentavos,long ProductVersion,List<KioskChoice> Options,string? OptionLabel);
sealed record KioskOrder(Guid Id,string Number,Guid InstallationId,string Service,string? Notes,string PaymentMethod,
    List<KioskLine> Lines,long TotalCentavos,string? CreatedAt=null,string Status="AwaitingPayment");
sealed record StatusRequest(string Next);
sealed record StaffRefreshRequest(string StaffAccessToken);
sealed class ConflictException(string message):Exception(message);

sealed class HubDb {
    readonly string _path;
    readonly object _gate=new();
    public HubDb():this(null) {}
    internal HubDb(string? isolatedRoot) {
        var folder=isolatedRoot??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Azurate","BrewPOS.Kiosk.Hub");
        Directory.CreateDirectory(folder);_path=Path.Combine(folder,"hub.sqlite3");
        using var db=Open();
        using var cmd=db.CreateCommand();
        cmd.CommandText="""
           CREATE TABLE IF NOT EXISTS catalog(id TEXT PRIMARY KEY,body TEXT NOT NULL);
           CREATE TABLE IF NOT EXISTS orders(id TEXT PRIMARY KEY,number TEXT NOT NULL UNIQUE,
             installation_id TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,
             created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
           CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,
             order_id TEXT NOT NULL,from_status TEXT,to_status TEXT NOT NULL,role TEXT NOT NULL,at TEXT NOT NULL);
           CREATE TABLE IF NOT EXISTS hub_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
           CREATE TABLE IF NOT EXISTS cloud_acks(order_id TEXT PRIMARY KEY,ack_at TEXT NOT NULL);
           """;
        cmd.ExecuteNonQuery();
    }
    SqliteConnection Open(){
        var db=new SqliteConnection(new SqliteConnectionStringBuilder{
            DataSource=_path,Mode=SqliteOpenMode.ReadWriteCreate}.ToString());
        db.Open();using var cmd=db.CreateCommand();
        cmd.CommandText="PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;";
        cmd.ExecuteNonQuery();return db;
    }
    public Guid InstallationId {
        get {
            lock(_gate){
              using var db=Open();using var cmd=db.CreateCommand();
              cmd.CommandText="SELECT value FROM hub_meta WHERE key='installation_id'";
              if(Guid.TryParse(cmd.ExecuteScalar()?.ToString(),out var value))return value;
              value=Guid.NewGuid();
              using var insert=db.CreateCommand();
              insert.CommandText="INSERT INTO hub_meta(key,value) VALUES('installation_id',$v)";
              insert.Parameters.AddWithValue("$v",value.ToString());insert.ExecuteNonQuery();
              return value;
            }
        }
    }
    public List<KioskOrder> PendingPaidForCloud() {
        using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText="SELECT body,status,created_at FROM orders WHERE status IN ('Paid','Preparing','Ready','Completed') "+
            "AND id NOT IN (SELECT order_id FROM cloud_acks) ORDER BY created_at ASC LIMIT 100";
        using var rows=cmd.ExecuteReader();var list=new List<KioskOrder>();
        while(rows.Read()){
            var row=JsonSerializer.Deserialize<KioskOrder>(rows.GetString(0),Options.Web)!;
            list.Add(row with { Status=rows.GetString(1),CreatedAt=rows.GetString(2) });
        }
        return list;
    }
    public void CloudAcknowledged(Guid orderId) {
        using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText="INSERT OR IGNORE INTO cloud_acks(order_id,ack_at) VALUES($id,$now)";
        cmd.Parameters.AddWithValue("$id",orderId.ToString());
        cmd.Parameters.AddWithValue("$now",DateTimeOffset.UtcNow.ToString("O"));cmd.ExecuteNonQuery();
    }
    public List<KioskProduct> Menu(){
        using var db=Open();using var c=db.CreateCommand();c.CommandText="SELECT body FROM catalog ORDER BY id";
        using var r=c.ExecuteReader();var result=new List<KioskProduct>();
        while(r.Read())result.Add(JsonSerializer.Deserialize<KioskProduct>(r.GetString(0),Options.Web)!);
        return result;
    }
    public void ReplaceMenu(List<KioskProduct> products){
        if(products.Select(p=>p.Id).Distinct().Count()!=products.Count || products.Any(p=>p.Id==Guid.Empty ||
            p.Name.Length is <1 or >200 || p.PriceCentavos is <0 or >100000000 ||
            p.Version<1 || p.Modifiers.Count>15 || p.Modifiers.Any(g=>g.Options.Count>50)))
            throw new InvalidOperationException("Catalog contains invalid products, duplicate IDs or prices.");
        lock(_gate){
          using var db=Open();using var tx=db.BeginTransaction();
          using(var del=db.CreateCommand()){del.Transaction=tx;del.CommandText="DELETE FROM catalog";del.ExecuteNonQuery();}
          foreach(var p in products) {
            using var cmd=db.CreateCommand();cmd.Transaction=tx;
            cmd.CommandText="INSERT INTO catalog(id,body) VALUES($id,$body)";
            cmd.Parameters.AddWithValue("$id",p.Id.ToString());
            cmd.Parameters.AddWithValue("$body",JsonSerializer.Serialize(p,Options.Web));
            cmd.ExecuteNonQuery();
          }
          tx.Commit();
        }
    }
    public object Place(KioskOrder order) {
        if(order.Id==Guid.Empty||order.InstallationId==Guid.Empty||
          !System.Text.RegularExpressions.Regex.IsMatch(order.Number,@"^K-[A-Z0-9]{6,20}$") ||
          order.Service is not ("Dine-in" or "Takeout") ||
          order.PaymentMethod!="counter"||order.Lines.Count is <1 or >80 ||
          order.Notes?.Length>60)
            throw new InvalidOperationException("Invalid kiosk order. Pay at Counter only.");
        lock(_gate){
          using var db=Open();using var tx=db.BeginTransaction();
          using(var check=db.CreateCommand()){
            check.Transaction=tx;check.CommandText="SELECT body FROM orders WHERE id=$id";
            check.Parameters.AddWithValue("$id",order.Id.ToString());
            var old=check.ExecuteScalar() as string;
            if(old!=null) {
              var existing=JsonSerializer.Deserialize<KioskOrder>(old,Options.Web)!;
              var left=JsonSerializer.Serialize(existing with {CreatedAt=null,Status="AwaitingPayment"},Options.Web);
              var right=JsonSerializer.Serialize(order with {CreatedAt=null,Status="AwaitingPayment"},Options.Web);
              if(left!=right)throw new ConflictException("Conflicting order ID. Review locally.");
              return new{accepted=true,duplicate=true,order.Id,order.Number};
            }
          }
          var map=Menu().ToDictionary(x=>x.Id);
          long total=0;
          foreach(var line in order.Lines){
            if(!map.TryGetValue(line.ProductId,out var product)||!product.Active||
                product.Version!=line.ProductVersion ||line.Quantity is <1 or >99)
                throw new ConflictException("Menu or product changed. Reconfirm order with staff.");
            long price=product.PriceCentavos;
            foreach(var group in product.Modifiers){
              var choice=line.Options.FirstOrDefault(x=>x.GroupId==group.Id);
              var chosen=choice?.OptionIds??[];
              if(group.Required && chosen.Length==0 ||
                 (!group.Multiple && chosen.Length>1) || chosen.Length>Math.Max(1,group.MaxSelections) ||
                 chosen.Distinct().Count()!=chosen.Length)
                 throw new ConflictException("Invalid options; check the menu.");
              foreach(var optionId in chosen){
                var option=group.Options.FirstOrDefault(x=>x.Id==optionId)
                     ??throw new ConflictException("Unknown menu modifier.");
                price=checked(price+option.DeltaCentavos);
              }
            }
            if(line.Options.Any(x=>product.Modifiers.All(g=>g.Id!=x.GroupId))||
               price!=line.UnitCentavos ||price<0)throw new ConflictException("Order pricing changed.");
            total=checked(total+price*line.Quantity);
          }
          if(order.TotalCentavos!=total||total<=0||total>100000000)
            throw new ConflictException("Order total does not match trusted menu.");
          using var cmd=db.CreateCommand();cmd.Transaction=tx;
          cmd.CommandText=""" 
            INSERT INTO orders(id,number,installation_id,body,status,created_at,updated_at)
            VALUES($id,$n,$install,$body,'AwaitingPayment',$at,$at)
            """;
          cmd.Parameters.AddWithValue("$id",order.Id.ToString());
          cmd.Parameters.AddWithValue("$n",order.Number);
          cmd.Parameters.AddWithValue("$install",order.InstallationId.ToString());
          cmd.Parameters.AddWithValue("$body",JsonSerializer.Serialize(order with{Status="AwaitingPayment"},Options.Web));
          cmd.Parameters.AddWithValue("$at",DateTimeOffset.UtcNow.ToString("O"));
          try{cmd.ExecuteNonQuery();}catch(SqliteException e)when(e.SqliteErrorCode==19){throw new ConflictException("Duplicate order number; ask a cashier.");}
          tx.Commit();
          return new{accepted=true,duplicate=false,order.Id,order.Number};
        }
    }
    public KioskOrder? Get(Guid id){
        using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText="SELECT body,status,created_at FROM orders WHERE id=$id";
        cmd.Parameters.AddWithValue("$id",id.ToString());
        using var r=cmd.ExecuteReader();if(!r.Read())return null;
        var o=JsonSerializer.Deserialize<KioskOrder>(r.GetString(0),Options.Web)!;
        return o with{Status=r.GetString(1),CreatedAt=r.GetString(2)};
    }
    public List<KioskOrder> Orders(bool kitchen) {
        using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText=kitchen?
          "SELECT body,status,created_at FROM orders WHERE status IN ('Paid','Preparing','Ready','Completed') ORDER BY created_at DESC LIMIT 200":
          "SELECT body,status,created_at FROM orders ORDER BY created_at DESC LIMIT 200";
        using var r=cmd.ExecuteReader();var result=new List<KioskOrder>();
        while(r.Read()){
            var o=JsonSerializer.Deserialize<KioskOrder>(r.GetString(0),Options.Web)!;
            result.Add(o with{Status=r.GetString(1),CreatedAt=r.GetString(2)});
        }
        return result;
    }
    public object Transition(Guid id,string next,string role) {
        lock(_gate){
          using var db=Open();using var tx=db.BeginTransaction();
          using var query=db.CreateCommand();query.Transaction=tx;
          query.CommandText="SELECT status FROM orders WHERE id=$id";
          query.Parameters.AddWithValue("$id",id.ToString());
          var old=query.ExecuteScalar()?.ToString()??throw new InvalidOperationException("Unknown ticket.");
          var valid=old switch{
            "AwaitingPayment"=>next=="Paid"&&(role is "cashier" or "admin"),
            "Paid"=>next=="Preparing"&&(role is "kitchen" or "cashier" or "admin"),
            "Preparing"=>next=="Ready"&&(role is "kitchen" or "cashier" or "admin"),
            "Ready"=>next=="Completed"&&(role is "cashier" or "kitchen" or "admin"),
            _=>false
          };
          if(!valid)throw new ConflictException("Not a valid status transition for your device role.");
          using var update=db.CreateCommand();update.Transaction=tx;
          update.CommandText="UPDATE orders SET status=$new,updated_at=$at WHERE id=$id AND status=$old";
          update.Parameters.AddWithValue("$new",next);update.Parameters.AddWithValue("$at",DateTimeOffset.UtcNow.ToString("O"));
          update.Parameters.AddWithValue("$id",id.ToString());update.Parameters.AddWithValue("$old",old);
          if(update.ExecuteNonQuery()!=1)throw new ConflictException("Ticket changed; refresh first.");
          using var audit=db.CreateCommand();audit.Transaction=tx;
          audit.CommandText="INSERT INTO audit(order_id,from_status,to_status,role,at) VALUES($id,$old,$new,$role,$at)";
          audit.Parameters.AddWithValue("$id",id.ToString());audit.Parameters.AddWithValue("$old",old);
          audit.Parameters.AddWithValue("$new",next);audit.Parameters.AddWithValue("$role",role);
          audit.Parameters.AddWithValue("$at",DateTimeOffset.UtcNow.ToString("O"));audit.ExecuteNonQuery();
          tx.Commit();return new{updated=true,id,status=next};
        }
    }
}
