using System.Collections.ObjectModel;
using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;

namespace CafeOabi.BrewPOS;

public partial class MainWindow:Window {
    readonly CafeStore _db=new();
    readonly BrewCloud _cloud=new();
    readonly SecureSession _vault;
    readonly ObservableCollection<TicketLine> _cart=new();
    readonly DispatcherTimer _retry=new(){Interval=TimeSpan.FromSeconds(50)};
    readonly SemaphoreSlim _syncLock=new(1,1);
    Tenant? _tenant;
    bool _online=false;

    public MainWindow() {
        InitializeComponent();
        _vault=new SecureSession(_db.Folder);
        DeviceText.Text="BrewPOS activation Device ID (16 hex): "+_db.Device16+
            "\nCloud installation UUID: "+_db.InstallId;
        PrinterInput.Text=_db.Setting("printer")??"";
        AutoPrint.IsChecked=(_db.Setting("auto_print")??"true")=="true";
        CartGrid.ItemsSource=_cart;
        _cart.CollectionChanged+=(_,_)=>Totals();
        _retry.Tick+=async (_,_)=>await SyncNow();
        _retry.Start();
        var saved=_vault.Load();
        if(saved!=null)Status("Encrypted session is available for offline use.");
        Totals();
    }
    void Status(string msg) {
        if(StatusText!=null)StatusText.Text=msg;
        if(Connectivity!=null)Connectivity.Text=_online?"● ONLINE":"● OFFLINE / LOCAL";
        if(_tenant!=null){
            BranchText.Text=_tenant.BranchName;
            TenantText.Text=$"{_tenant.BranchName} • {_tenant.OrganizationId} • {_tenant.Role}";
            var pending=_db.Outbox(_tenant);
            PendingText.Text=$"{pending.Count(e=>e.Status=="queued")} queued • {pending.Count(e=>e.Status=="review")} needs review";
        }
        if(LicenseText!=null) {
            var valid=_db.Authorized(DateTimeOffset.UtcNow,out var result);
            LicenseText.Text=valid?result:"License: "+result;
        }
    }
    static long Cents(string? text,string field) {
        if(!decimal.TryParse(text,NumberStyles.Number,CultureInfo.CurrentCulture,out var n) &&
            !decimal.TryParse(text,NumberStyles.Number,CultureInfo.InvariantCulture,out n))
            throw new InvalidOperationException("Enter a valid "+field+".");
        if(n<0||n>1000000||decimal.Round(n*100,0)!=n*100)
            throw new InvalidOperationException("Invalid "+field+" amount.");
        return checked((long)(n*100m));
    }
    static string TextOf(ComboBox box) => (box.SelectedItem as ComboBoxItem)?.Content?.ToString()??"";
    void Guard(bool requireManager=false) {
        if(_tenant==null||_cloud.Session==null)
            throw new InvalidOperationException("Sign into Café Oabi once while online first.");
        if(!_db.Authorized(DateTimeOffset.UtcNow,out var status))
            throw new InvalidOperationException("Owner-issued BrewPOS BP1 activation required: "+status);
        if(requireManager && _tenant.Role is not ("owner" or "manager"))
            throw new InvalidOperationException("Manager permission required.");
        if(!requireManager && _tenant.Role is not ("owner" or "manager" or "cashier"))
            throw new InvalidOperationException("Cashier permission required.");
        if(_db.Setting("registered:"+_tenant.OrganizationId)!=_tenant.BranchId.ToString())
            throw new InvalidOperationException("Windows PC must first be registered online by an authorized Café Oabi manager.");
        _db.TickClock(DateTimeOffset.UtcNow);
    }
    void Save() {
        if(_tenant!=null && _cloud.Session!=null)
            _vault.Save(new StoredSession(_cloud.Session,_tenant,DateTimeOffset.UtcNow));
    }
    void Workspace() {
        LoginOverlay.Visibility=Visibility.Collapsed;
        Pages.SelectedIndex=0;
        RefreshLocal();
        Status(_online?"Signed in to BrewPOS Cloud.":"Offline: sale receipts and inventory changes save on this PC.");
        SearchInput.Focus();
    }
    void RefreshLocal() {
        if(_tenant==null)return;
        var products=_db.Menu(_tenant);
        var selectedCategory=CategoryBox.SelectedItem?.ToString()??"All categories";
        var categories=new[]{"All categories"}.Concat(products.Select(p=>p.Category).Distinct().OrderBy(x=>x)).ToList();
        if(categories.Count!=CategoryBox.Items.Count || categories.Where((item,i)=>!Equals(item,CategoryBox.Items[i])).Any()) {
            CategoryBox.ItemsSource=categories;
            CategoryBox.SelectedItem=categories.Contains(selectedCategory)?selectedCategory:"All categories";
        }
        var category=CategoryBox.SelectedItem?.ToString()??"All categories";
        var term=SearchInput.Text.Trim();
        var filtered=products.Where(p=>p.Active &&
            (category=="All categories"||p.Category==category) &&
            (term.Length==0 || p.Name.Contains(term,StringComparison.OrdinalIgnoreCase) ||
                p.Category.Contains(term,StringComparison.OrdinalIgnoreCase))).ToList();
        MenuGrid.ItemsSource=filtered;
        InventoryGrid.ItemsSource=products;
        HistoryGrid.ItemsSource=_db.Outbox(_tenant);
        MenuCount.Text=$"{filtered.Count} menu items • {products.Count} cached";
        Totals();Status("Local café data loaded.");
    }
    void Totals() {
        if(TotalText==null||TenderInput==null||DiscountInput==null)return;
        var subtotal=_cart.Sum(x=>x.TotalCents);
        long discount=0;
        try{discount=Cents(DiscountInput.Text,"discount");}catch { }
        discount=Math.Min(discount,subtotal);
        var total=subtotal-discount;
        long tender=0;
        try{tender=Cents(TenderInput.Text,"cash");}catch{ }
        SubtotalText.Text=Config.Peso(subtotal);
        DiscountLabel.Text=Config.Peso(discount);
        TotalText.Text=Config.Peso(total);
        ChangeText.Text=Config.Peso(Math.Max(0,tender-total));
        CartCount.Text=$"{_cart.Count} item lines";
    }
    void MoneyChanged(object sender,TextChangedEventArgs e) { if(IsInitialized)Totals(); }
    void CategoryChanged(object sender,SelectionChangedEventArgs e) {
        if(IsInitialized&&_tenant!=null&&MenuGrid!=null)RefreshLocal();
    }
    void Search_Click(object sender,RoutedEventArgs e)=>RefreshLocal();
    void OpenCashier(object sender,RoutedEventArgs e)=>Pages.SelectedIndex=0;
    void OpenInventory(object sender,RoutedEventArgs e)=>Pages.SelectedIndex=1;
    void OpenHistory(object sender,RoutedEventArgs e){Pages.SelectedIndex=2;RefreshLocal();}
    void OpenSettings(object sender,RoutedEventArgs e)=>Pages.SelectedIndex=3;

    async void SignIn_Click(object sender,RoutedEventArgs e) {
        LoginError.Text="";
        try {
            await _cloud.Login(EmailInput.Text.Trim(),PasswordInput.Password);
            _tenant=await _cloud.CafeOabiTenant();
            _online=true;Save();
            Workspace();
            try{await RefreshMenu();}catch(Exception error){
                Status("Signed in; menu download awaits authorization: "+error.Message);
            }
        }catch(Exception error){
            LoginError.Text=error.Message;
            _online=false;
        }finally{PasswordInput.Clear();}
    }
    void Offline_Click(object sender,RoutedEventArgs e) {
        try {
            var stored=_vault.Load()??throw new InvalidOperationException("No saved BrewPOS staff session.");
            if(_db.Setting("registered:"+stored.Tenant.OrganizationId)!=stored.Tenant.BranchId.ToString())
                throw new InvalidOperationException("This Windows device is not yet registered for Café Oabi.");
            if(!_db.Authorized(DateTimeOffset.UtcNow,out var reason))
                throw new InvalidOperationException("Offline license invalid: "+reason);
            if(_db.Menu(stored.Tenant).Count==0)
                throw new InvalidOperationException("Connect once to download an approved Café Oabi menu.");
            _tenant=stored.Tenant;_cloud.Use(stored.Session);_online=false;Workspace();
        }catch(Exception error){LoginError.Text=error.Message;}
    }
    async Task RefreshMenu() {
        if(_tenant==null)throw new InvalidOperationException("Sign in first.");
        if(_db.HasOutstanding(_tenant))
            throw new InvalidOperationException("Unsynced local tickets/stock block menu replacement.");
        var data=await _cloud.DownloadMenu(_tenant);
        _db.ReplaceMenu(_tenant,data);
        _online=true;RefreshLocal();
        Status($"Downloaded {data.Count} café menu products.");
    }
    async void RefreshMenu_Click(object sender,RoutedEventArgs e) {
        try{await RefreshMenu();}catch(Exception ex){Warn(ex.Message);}
    }
    void Warn(string message) {
        Status(message);
        MessageBox.Show(this,message,"Café Oabi BrewPOS",MessageBoxButton.OK,MessageBoxImage.Warning);
    }
    async void Register_Click(object sender,RoutedEventArgs e) {
        try{
            if(_tenant==null)throw new InvalidOperationException("Sign in first.");
            await _cloud.Register(_tenant,_db.InstallId);
            _db.Set("registered:"+_tenant.OrganizationId,_tenant.BranchId.ToString());
            _online=true;Status("Café Oabi Windows device registered successfully.");
            await RefreshMenu();
        }catch(Exception ex){Warn(ex.Message);}
    }
    void Activate_Click(object sender,RoutedEventArgs e) {
        try {
            var candidate=ActivationInput.Text.Trim();
            if(!BP1License.Valid(candidate,_db.Device16,DateTimeOffset.UtcNow,_db.LastSeen,out var reason))
                throw new InvalidOperationException("Invalid signed activation: "+reason);
            _db.LicenseCode=candidate;
            _db.TickClock(DateTimeOffset.UtcNow);
            ActivationInput.Clear();
            Status("Signed device activation verified.");
        }catch(Exception ex){Warn(ex.Message);}
    }
    void SavePrinter_Click(object sender,RoutedEventArgs e) {
        _db.Set("printer",PrinterInput.Text.Trim());
        _db.Set("auto_print",(AutoPrint.IsChecked==true).ToString().ToLowerInvariant());
        Status("Local printer settings saved.");
    }
    void Add_Click(object sender,RoutedEventArgs e) {
        try {
            Guard();
            var p=MenuGrid.SelectedItem as MenuItem
                ??throw new InvalidOperationException("Choose a drink or menu item first.");
            if(!int.TryParse(QuantityBox.Text,out var qty)||qty is <1 or >999)
                throw new InvalidOperationException("Enter a quantity from 1 to 999.");
            var large=SizeBox.SelectedIndex==1;
            var shot=ShotBox.IsChecked==true;
            var unit=checked(p.PriceCents+(large?2000L:0L)+(shot?4000L:0L));
            var option=string.Join(", ",new[]{
                large?"Large":"Regular",TextOf(SugarBox),TextOf(IceBox),shot?"Extra espresso":""
            }.Where(x=>x.Length>0));
            _cart.Add(new TicketLine(p.Id,p.Name,option,qty,unit));
            SearchInput.Focus();Totals();
        }catch(Exception ex){Warn(ex.Message);}
    }
    void Remove_Click(object sender,RoutedEventArgs e) {
        if(CartGrid.SelectedItem is TicketLine selected)_cart.Remove(selected);
    }
    async void Checkout_Click(object sender,RoutedEventArgs e) {
        try{
            Guard();
            long tender=Cents(TenderInput.Text,"cash");
            long discount=Cents(DiscountInput.Text,"discount");
            var service=TextOf(ServiceBox);
            var entry=_db.Checkout(_tenant!,_cloud.Session!.UserId,_cart.ToList(),
                service,TableBox.Text.Trim(),tender,discount);
            _cart.Clear();
            ReceiptPreview.Text=entry.Receipt;
            RefreshLocal();Status("Café order saved locally: "+entry.Id);
            if(AutoPrint.IsChecked==true) {
                try{
                    TicketPrinter.Print(PrinterInput.Text,entry.Receipt);
                    TicketPrinter.Print(PrinterInput.Text,BaristaFromEvent(entry.Payload));
                }catch(Exception printerError){
                    Warn("Order is SAVED. Printer failed: "+printerError.Message+
                        ". Reprint from Sales without charging again.");
                }
            }
            await SyncNow();
        }catch(Exception ex){Warn(ex.Message);}
    }
    static string BaristaFromEvent(string payload) {
        using var doc=JsonDocument.Parse(payload);
        var r=doc.RootElement;
        var sb=new StringBuilder("BARISTA / KITCHEN TICKET\n");
        sb.AppendLine(r.GetProperty("local_receipt_no").GetString());
        sb.AppendLine(r.GetProperty("service_type").GetString()+
            " • "+r.GetProperty("notes").GetString());
        sb.AppendLine("--------------------------------");
        foreach(var line in r.GetProperty("line_items").EnumerateArray()) {
            sb.AppendLine(line.GetProperty("quantity").GetInt32()+" x "+
                line.GetProperty("product_name").GetString());
            sb.AppendLine("    "+line.GetProperty("options").GetString());
        }
        sb.AppendLine("\n\n");return sb.ToString();
    }
    void HistorySelected(object sender,SelectionChangedEventArgs e) {
        if(ReceiptPreview!=null&&HistoryGrid.SelectedItem is SyncEntry item)
            ReceiptPreview.Text=item.Receipt;
    }
    void PrintCustomer_Click(object sender,RoutedEventArgs e) {
        try {
            var item=HistoryGrid.SelectedItem as SyncEntry
                ??throw new InvalidOperationException("Select the cash sale to reprint.");
            if(item.Kind!="sale.completed")throw new InvalidOperationException("This is an inventory event.");
            TicketPrinter.Print(PrinterInput.Text,item.Receipt);
        }catch(Exception ex){Warn(ex.Message);}
    }
    void PrintBarista_Click(object sender,RoutedEventArgs e) {
        try {
            var item=HistoryGrid.SelectedItem as SyncEntry
                ??throw new InvalidOperationException("Select a sale for its kitchen order.");
            if(item.Kind!="sale.completed")throw new InvalidOperationException("Select a sales ticket.");
            TicketPrinter.Print(PrinterInput.Text,BaristaFromEvent(item.Payload));
        }catch(Exception ex){Warn(ex.Message);}
    }
    void Adjust_Click(object sender,RoutedEventArgs e) {
        try {
            Guard(requireManager:true);
            var p=InventoryGrid.SelectedItem as MenuItem
                ??throw new InvalidOperationException("Select the product to adjust.");
            if(!decimal.TryParse(DeltaInput.Text,NumberStyles.Number,CultureInfo.InvariantCulture,out var delta))
                throw new InvalidOperationException("Enter a stock delta, e.g. +10.");
            _db.QueueStockAdjustment(_tenant!,_cloud.Session!.UserId,p.Id,delta,ReasonInput.Text);
            RefreshLocal();
            Status("Offline stock change queued. BrewPOS Cloud inventory reconciliation not yet enabled.");
        }catch(Exception ex){Warn(ex.Message);}
    }
    async Task SyncNow() {
        if(_tenant==null||!await _syncLock.WaitAsync(0))return;
        try {
            var records=_db.Outbox(_tenant);
            foreach(var entry in records.Where(x=>x.Status=="queued")) {
                if(entry.Kind!="sale.completed"){
                    Status("Inventory event waiting for manager review; cloud inventory route pending.");
                    break;
                }
                try{
                    await _cloud.ReplaySale(_tenant,_db.InstallId,entry);
                    _db.MarkSynced(entry.Id);_online=true;
                }catch(BrewCloudException ex) when(ex.Retryable) {
                    _online=false;Status("Cloud temporarily unreachable; local tickets remain pending.");break;
                }catch(HttpRequestException){
                    _online=false;Status("No internet. Pending café sales are safe in SQLite.");break;
                }catch(TaskCanceledException){
                    _online=false;Status("Cloud timeout; will retry original sale ID.");break;
                }catch(Exception ex){
                    // Never discard original locally paid cash; manager must resolve.
                    _db.Review(entry.Id,ex.Message);
                    Status("Cloud validation rejected one sale; manual review required.");
                    break;
                }
            }
            RefreshLocal();
        }catch(Exception ex){
            _online=false;Status("Offline sync deferred: "+ex.Message);
        }finally{_syncLock.Release();}
    }
    async void Sync_Click(object sender,RoutedEventArgs e)=>await SyncNow();
    void SignOut_Click(object sender,RoutedEventArgs e) {
        if(_tenant!=null&&_db.HasOutstanding(_tenant) &&
            MessageBox.Show(this,"Local transactions are pending. Sign out and keep local data?",
                "Pending sales",MessageBoxButton.YesNo)!=MessageBoxResult.Yes)return;
        _tenant=null;_cloud.Use(null);_vault.Clear();_online=false;_cart.Clear();
        LoginOverlay.Visibility=Visibility.Visible;Status("Signed out. Local receipts and orders preserved.");
    }
    protected override void OnPreviewKeyDown(KeyEventArgs e) {
        base.OnPreviewKeyDown(e);
        if(LoginOverlay.Visibility==Visibility.Visible)return;
        if(e.Key==Key.F2){Pages.SelectedIndex=0;SearchInput.Focus();e.Handled=true;}
        else if(e.Key==Key.F4){Checkout_Click(this,new RoutedEventArgs());e.Handled=true;}
        else if(e.Key==Key.F5){_=SyncNow();e.Handled=true;}
    }
}
