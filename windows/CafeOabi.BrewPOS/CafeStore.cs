using System.IO;
using System.Globalization;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace CafeOabi.BrewPOS;

public sealed class CafeStore {
    readonly string _file;
    public string Folder {get;}
    public CafeStore(string? root=null) {
        Folder=root??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Azurate","CafeOabi.BrewPOS");
        Directory.CreateDirectory(Folder); _file=Path.Combine(Folder,"cafe-oabi.sqlite3");
        using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText="""
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY,value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS menu (id TEXT PRIMARY KEY,org_id TEXT NOT NULL,branch_id TEXT NOT NULL,payload TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS menu_branch ON menu(org_id,branch_id);
            CREATE TABLE IF NOT EXISTS outbox (
                id TEXT PRIMARY KEY,org_id TEXT NOT NULL,branch_id TEXT NOT NULL,
                actor_id TEXT NOT NULL,event_type TEXT NOT NULL,payload TEXT NOT NULL,
                receipt TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('queued','synced','review')),
                error TEXT,created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS outbox_pending ON outbox(org_id,branch_id,status,created_at);
            """;
        cmd.ExecuteNonQuery();
    }
    SqliteConnection Open() {
        var db=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=_file,Mode=SqliteOpenMode.ReadWriteCreate}.ToString());
        db.Open();
        using var c=db.CreateCommand();c.CommandText="PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;";c.ExecuteNonQuery();
        return db;
    }
    static void Exec(SqliteConnection db,SqliteTransaction tx,string sql,params (string Key,object? Value)[] args) {
        using var cmd=db.CreateCommand();cmd.Transaction=tx;cmd.CommandText=sql;
        foreach(var a in args)cmd.Parameters.AddWithValue(a.Key,a.Value??DBNull.Value);
        cmd.ExecuteNonQuery();
    }
    public string? Setting(string name) {
        using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText="SELECT value FROM settings WHERE key=$k";cmd.Parameters.AddWithValue("$k",name);
        return cmd.ExecuteScalar()?.ToString();
    }
    public void Set(string name,string value) {
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="INSERT INTO settings(key,value) VALUES($k,$v) ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        c.Parameters.AddWithValue("$k",name);c.Parameters.AddWithValue("$v",value);c.ExecuteNonQuery();
    }
    public Guid InstallId {
        get {
            if(Guid.TryParse(Setting("install_id"),out var value))return value;
            value=Guid.NewGuid();Set("install_id",value.ToString());return value;
        }
    }
    public string Device16 {
        get {
            var existing=Setting("device_16");
            if(existing is {Length:16})return existing;
            var device=Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(8)).ToLowerInvariant();
            Set("device_16",device);return device;
        }
    }
    public DateTimeOffset LastSeen =>long.TryParse(Setting("last_seen_unix"),out var n)?
        DateTimeOffset.FromUnixTimeSeconds(n):DateTimeOffset.MinValue;
    public void TickClock(DateTimeOffset now) {
        if(now.AddMinutes(5)<LastSeen)throw new InvalidOperationException("Clock rollback detected; reconnect for verification.");
        Set("last_seen_unix",now.ToUnixTimeSeconds().ToString());
    }
    public string LicenseCode {get=>Setting("license_bp1")??"";set=>Set("license_bp1",value);}
    public bool Authorized(DateTimeOffset now,out string message)=>
        BP1License.Valid(LicenseCode,Device16,now,LastSeen,out message);

    public List<MenuItem> Menu(Tenant tenant) {
        using var db=Open();return ReadMenu(db,null,tenant);
    }
    static List<MenuItem> ReadMenu(SqliteConnection db,SqliteTransaction? tx,Tenant tenant) {
        using var c=db.CreateCommand();c.Transaction=tx;
        c.CommandText="SELECT payload FROM menu WHERE org_id=$o AND branch_id=$b ORDER BY id";
        c.Parameters.AddWithValue("$o",tenant.OrganizationId.ToString());
        c.Parameters.AddWithValue("$b",tenant.BranchId.ToString());
        var values=new List<MenuItem>();using var r=c.ExecuteReader();
        while(r.Read()){var p=JsonSerializer.Deserialize<MenuItem>(r.GetString(0),Config.Json);if(p!=null)values.Add(p);}
        return values.OrderBy(x=>x.Category).ThenBy(x=>x.Name).ToList();
    }
    public bool HasOutstanding(Tenant tenant)=>Outbox(tenant).Any(x=>x.Status is "queued" or "review");
    public void ReplaceMenu(Tenant tenant,IReadOnlyList<MenuItem> catalog) {
        if(catalog.Any(p=>p.OrganizationId!=tenant.OrganizationId||p.BranchId!=tenant.BranchId)||catalog.Select(p=>p.Id).Distinct().Count()!=catalog.Count)
            throw new InvalidOperationException("Invalid tenant catalog.");
        if(HasOutstanding(tenant))throw new InvalidOperationException("Pending sales/inventory must reconcile before replacing local menu.");
        using var db=Open();using var tx=db.BeginTransaction();
        using(var pending=db.CreateCommand()) {
            pending.Transaction=tx;
            pending.CommandText="SELECT COUNT(*) FROM outbox WHERE org_id=$o AND branch_id=$b AND status IN ('queued','review')";
            pending.Parameters.AddWithValue("$o",tenant.OrganizationId.ToString());
            pending.Parameters.AddWithValue("$b",tenant.BranchId.ToString());
            if(Convert.ToInt64(pending.ExecuteScalar())>0)throw new InvalidOperationException("Local events appeared during catalog download; retry sync.");
        }
        Exec(db,tx,"DELETE FROM menu WHERE org_id=$o AND branch_id=$b",("$o",tenant.OrganizationId.ToString()),("$b",tenant.BranchId.ToString()));
        foreach(var p in catalog)SaveMenu(db,tx,p);
        tx.Commit();
    }
    static void SaveMenu(SqliteConnection db,SqliteTransaction tx,MenuItem p)=>
        Exec(db,tx,"""
          INSERT INTO menu(id,org_id,branch_id,payload) VALUES($id,$o,$b,$p)
          ON CONFLICT(id) DO UPDATE SET org_id=excluded.org_id,branch_id=excluded.branch_id,payload=excluded.payload
          """,("$id",p.Id.ToString()),("$o",p.OrganizationId.ToString()),
          ("$b",p.BranchId.ToString()),("$p",JsonSerializer.Serialize(p,Config.Json)));

    public SyncEntry Checkout(Tenant tenant,Guid actor,IReadOnlyList<TicketLine> lines,string service,string notes,long tendered,long discount) {
        if(tenant.Role is not ("owner" or "manager" or "cashier"))
            throw new InvalidOperationException("Not authorized for sales.");
        if(!Authorized(DateTimeOffset.UtcNow,out var status))
            throw new InvalidOperationException("BP1 activation needed: "+status);
        if(service is not ("Dine-in" or "Takeout"))throw new InvalidOperationException("Choose Dine-in or Takeout.");
        if(lines.Count is <1 or >80)throw new InvalidOperationException("Sale requires 1–80 items.");
        if(lines.Any(x=>x.Quantity is <1 or >999 || x.UnitCents<0))throw new InvalidOperationException("Invalid sale quantity/price.");
        var subtotal=checked(lines.Sum(x=>x.TotalCents));
        if(discount<0||discount>subtotal)throw new InvalidOperationException("Invalid discount.");
        var total=subtotal-discount;
        if(tendered<total)throw new InvalidOperationException("Insufficient cash.");
        var key=Guid.NewGuid();var created=DateTimeOffset.UtcNow;
        var receiptNo="OABI-OFF-"+key.ToString("N")[..10].ToUpperInvariant();
        var sale=new SaleEvent(key,InstallId,receiptNo,service,notes,subtotal,discount,total,tendered,tendered-total,created,lines.ToList());
        var receipt=TicketPrinter.Receipt("Café Oabi",sale);
        var body=JsonSerializer.Serialize(sale.CloudEvent(),Config.Json);
        using var db=Open();using var tx=db.BeginTransaction();
        var trustedMenu=ReadMenu(db,tx,tenant);
        var productLines=lines.GroupBy(x=>x.ProductId);
        foreach(var g in productLines) {
            var product=trustedMenu.SingleOrDefault(x=>x.Id==g.Key)
                ??throw new InvalidOperationException("Product missing from trusted menu.");
            if(!product.Active)throw new InvalidOperationException("Product unavailable.");
            var count=g.Sum(x=>x.Quantity);
            if(product.TrackStock && product.Stock<count)throw new InvalidOperationException("Local stock insufficient for "+product.Name);
            if(g.Any(x=>x.UnitCents<product.PriceCents))
                throw new InvalidOperationException("Custom price below base price.");
            if(product.TrackStock)SaveMenu(db,tx,product with{Stock=product.Stock-count});
        }
        Exec(db,tx,"""
          INSERT INTO outbox(id,org_id,branch_id,actor_id,event_type,payload,receipt,status,created_at)
          VALUES($id,$o,$b,$a,'sale.completed',$p,$r,'queued',$t)
          """,("$id",key.ToString()),("$o",tenant.OrganizationId.ToString()),
          ("$b",tenant.BranchId.ToString()),("$a",actor.ToString()),
          ("$p",body),("$r",receipt),("$t",created.ToString("O")));
        tx.Commit();
        return new SyncEntry(key,"sale.completed",body,"queued",null,receipt,created);
    }

    public SyncEntry QueueStockAdjustment(Tenant tenant,Guid actor,Guid productId,decimal delta,string reason) {
        if(tenant.Role is not ("owner" or "manager"))throw new InvalidOperationException("Manager permission needed.");
        if(!Authorized(DateTimeOffset.UtcNow,out var status))throw new InvalidOperationException("Activation needed: "+status);
        if(delta==0||Math.Abs(delta)>100000||decimal.Round(delta,3)!=delta)throw new InvalidOperationException("Invalid stock movement.");
        using var db=Open();using var tx=db.BeginTransaction();
        var p=ReadMenu(db,tx,tenant).SingleOrDefault(p=>p.Id==productId)
            ??throw new InvalidOperationException("Unknown product.");
        if(!p.TrackStock||string.IsNullOrWhiteSpace(reason)||reason.Trim().Length is <3 or >240)
            throw new InvalidOperationException("Tracked product and a reason of 3–240 characters required.");
        if(p.Stock+delta<0)throw new InvalidOperationException("Local stock cannot be negative.");
        var id=Guid.NewGuid();var date=DateTimeOffset.UtcNow;
        var payload=JsonSerializer.Serialize(new{payload_version=2,event_type="stock.adjusted",event_id=id,
            installation_id=InstallId,product_id=productId,expected_product_version=p.Version,
            delta,reason,created_at_ms=date.ToUnixTimeMilliseconds()},Config.Json);
        SaveMenu(db,tx,p with {Stock=p.Stock+delta});
        Exec(db,tx,"""
          INSERT INTO outbox(id,org_id,branch_id,actor_id,event_type,payload,receipt,status,created_at)
          VALUES($id,$o,$b,$a,'stock.adjusted',$p,'','queued',$t)
          """,("$id",id.ToString()),("$o",tenant.OrganizationId.ToString()),("$b",tenant.BranchId.ToString()),
          ("$a",actor.ToString()),("$p",payload),("$t",date.ToString("O")));
        tx.Commit();
        return new SyncEntry(id,"stock.adjusted",payload,"queued",null,"",date);
    }
    public List<SyncEntry> Outbox(Tenant tenant) {
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="SELECT id,event_type,payload,status,error,receipt,created_at FROM outbox WHERE org_id=$o AND branch_id=$b ORDER BY rowid";
        c.Parameters.AddWithValue("$o",tenant.OrganizationId.ToString());c.Parameters.AddWithValue("$b",tenant.BranchId.ToString());
        var rows=new List<SyncEntry>();using var r=c.ExecuteReader();
        while(r.Read())rows.Add(new SyncEntry(Guid.Parse(r.GetString(0)),r.GetString(1),r.GetString(2),
            r.GetString(3),r.IsDBNull(4)?null:r.GetString(4),r.GetString(5),DateTimeOffset.Parse(r.GetString(6))));
        return rows;
    }
    public void MarkSynced(Guid id) {
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="UPDATE outbox SET status='synced',error=NULL WHERE id=$i AND status='queued'";
        c.Parameters.AddWithValue("$i",id.ToString());c.ExecuteNonQuery();
    }
    public void Review(Guid id,string reason) {
        using var db=Open();using var c=db.CreateCommand();
        c.CommandText="UPDATE outbox SET status='review',error=$e WHERE id=$i AND status='queued'";
        c.Parameters.AddWithValue("$i",id.ToString());c.Parameters.AddWithValue("$e",reason[..Math.Min(500,reason.Length)]);
        c.ExecuteNonQuery();
    }
}
