using System.IO;
using System.Text.Json;
using CafeOabi.BrewPOS;
using Xunit;

namespace CafeOabi.Tests;

public class CafeJournalTests {
    static (CafeStore,Tenant,MenuItem) Create() {
        var folder=Path.Combine(Path.GetTempPath(),"CafeOabiTests",Guid.NewGuid().ToString("N"));
        var db=new CafeStore(folder);
        var tenant=new Tenant(Guid.NewGuid(),Guid.NewGuid(),"Café Oabi Test","owner");
        var p=new MenuItem(Guid.NewGuid(),tenant.OrganizationId,tenant.BranchId,"Latte","Coffee",12000,20);
        db.ReplaceMenu(tenant,new[]{p});
        return (db,tenant,p);
    }
    [Fact]
    public void UnlicensedCheckoutNeverWritesASale() {
        var(db,tenant,p)=Create();
        Assert.Throws<InvalidOperationException>(()=>db.Checkout(tenant,Guid.NewGuid(),
            new[]{new TicketLine(p.Id,p.Name,"Large",1,14000)},"Dine-in","Table 2",20000,0));
        Assert.Empty(db.Outbox(tenant));
        Assert.Equal(20,db.Menu(tenant).Single().Stock);
    }
    [Fact]
    public void CloudSaleEventUsesAndroidCompatibleImmutableUUID() {
        var e=Guid.NewGuid();var install=Guid.NewGuid();
        var sale=new SaleEvent(e,install,"OABI-OFF-TEST","Takeout","",25000,0,25000,30000,5000,
            DateTimeOffset.UtcNow,new List<TicketLine>{new(Guid.NewGuid(),"Matcha","Large, 75% sugar",2,12500)});
        var payload=JsonSerializer.Serialize(sale.CloudEvent(),Config.Json);
        using var json=JsonDocument.Parse(payload);
        Assert.Equal("sale.completed",json.RootElement.GetProperty("event_type").GetString());
        Assert.Equal(e,json.RootElement.GetProperty("event_id").GetGuid());
        Assert.Equal(e,json.RootElement.GetProperty("client_sale_id").GetGuid());
        Assert.Equal(install,json.RootElement.GetProperty("installation_id").GetGuid());
        Assert.Equal(2,json.RootElement.GetProperty("line_items")[0].GetProperty("quantity").GetInt32());
        Assert.Contains("PROVISIONAL",TicketPrinter.Receipt("Café Oabi",sale));
    }
    [Fact]
    public void DeviceIdIsStableAndExactly16HexCharacters() {
        var(db,tenant,p)=Create();
        var first=db.Device16;
        var next=new CafeStore(db.Folder);
        Assert.Equal(first,next.Device16);
        Assert.Matches("^[0-9a-f]{16}$",first);
        Assert.Equal(db.InstallId,next.InstallId);
    }
    [Fact]
    public void RejectUnsignedActivation() {
        var(db,tenant,p)=Create();
        Assert.False(BP1License.Valid("BP1.invalid.fake",db.Device16,
            DateTimeOffset.UtcNow,DateTimeOffset.MinValue,out _));
        Assert.False(db.Authorized(DateTimeOffset.UtcNow,out _));
    }
    [Fact]
    public void TenantMenuIsolationIsStrict() {
        var(db,tenant,p)=Create();
        var stranger=new Tenant(Guid.NewGuid(),Guid.NewGuid(),"Other café","owner");
        Assert.Empty(db.Menu(stranger));
    }
}
