using Xunit;

namespace BrewPOS.Kiosk.Tests;
public class HubOrderTests {
    static (HubDb Hub,KioskProduct Product) Make(){
        var folder=Path.Combine(Path.GetTempPath(),"BrewPOS.Kiosk.Hub.Tests",Guid.NewGuid().ToString("N"));
        var hub=new HubDb(folder);
        var product=new KioskProduct(Guid.NewGuid(),"Cafe Latte","Coffee","",
            12000,1,true,new List<KioskGroup>{
                new("size","Drink Size",true,false,1,new List<KioskOption>{
                    new("regular","Regular",0),new("large","Large",2000)
                })
            });
        hub.ReplaceMenu(new List<KioskProduct>{product});
        return(hub,product);
    }
    static KioskOrder Order(KioskProduct product,long charge=14000)=>new(
        Guid.NewGuid(),"K-"+Guid.NewGuid().ToString("N")[..9].ToUpperInvariant(),
        Guid.NewGuid(),"Dine-in","Table 1","counter",
        new List<KioskLine>{
            new(product.Id,product.Name,1,charge,product.Version,
                new List<KioskChoice>{new("size",new[]{"large"})},"Large")
        },charge
    );
    [Fact]public void PayAtCounterIsUnpaidUntilCashierConfirms(){
        var (db,p)=Make();var o=Order(p);
        db.Place(o);
        Assert.Equal("AwaitingPayment",db.Get(o.Id)!.Status);
        Assert.Empty(db.Orders(true));
        Assert.Empty(db.PendingPaidForCloud());
        Assert.Throws<ConflictException>(()=>db.Transition(o.Id,"Paid","kiosk"));
        db.Transition(o.Id,"Paid","cashier");
        Assert.Single(db.Orders(true));
        Assert.Single(db.PendingPaidForCloud());
        db.Transition(o.Id,"Preparing","kitchen");
        db.Transition(o.Id,"Ready","kitchen");
        db.Transition(o.Id,"Completed","cashier");
    }
    [Fact]public void DuplicateOrderIsIdempotentWithSameUuid(){
        var (db,p)=Make();var o=Order(p);
        db.Place(o);db.Place(o);
        Assert.Single(db.Orders(false));
        var duplicate=o with{TotalCentavos=100};
        Assert.Throws<ConflictException>(()=>db.Place(duplicate));
    }
    [Fact]public void PriceTamperingNeverWritesAnOrder(){
        var (db,p)=Make();var o=Order(p,100);
        Assert.Throws<ConflictException>(()=>db.Place(o));
        Assert.Empty(db.Orders(false));
    }
    [Fact]public void OnlinePaymentsAreNotEnabledByCustomerPayload(){
        var (db,p)=Make();var o=Order(p) with { PaymentMethod="online" };
        Assert.Throws<InvalidOperationException>(()=>db.Place(o));
        Assert.Empty(db.Orders(false));
    }
    [Fact]public void SQLiteTicketsSurviveRestart(){
        var (db,p)=Make();var o=Order(p);
        db.Place(o);
        var reopened=new HubDb(GetRoot(db));
        Assert.Equal("AwaitingPayment",reopened.Get(o.Id)!.Status);
    }
    static string GetRoot(HubDb db){
        // The isolated test path is private. Obtain it from the immutable
        // HubDb path only to verify persistence, without production data.
        var field=typeof(HubDb).GetField("_path",System.Reflection.BindingFlags.Instance|
            System.Reflection.BindingFlags.NonPublic)!;
        return Path.GetDirectoryName((string)field.GetValue(db)!)!;
    }
    [Fact]public void CannotSkipBaristaPaidState(){
        var (db,p)=Make();var o=Order(p);
        db.Place(o);
        Assert.Throws<ConflictException>(()=>db.Transition(o.Id,"Ready","kitchen"));
        db.Transition(o.Id,"Paid","cashier");
        Assert.Throws<ConflictException>(()=>db.Transition(o.Id,"Completed","cashier"));
    }
}
