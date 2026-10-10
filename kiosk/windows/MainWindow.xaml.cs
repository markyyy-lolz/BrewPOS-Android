using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;

namespace BrewPOS.Kiosk.Windows;

public partial class MainWindow:Window {
    readonly DeviceStore _db=new();
    readonly HttpClient _http=new(){Timeout=TimeSpan.FromSeconds(8)};
    readonly DispatcherTimer _retry=new(){Interval=TimeSpan.FromSeconds(15)};
    DeviceConfig? _config;
    bool _connected;
    readonly SemaphoreSlim _lock=new(1,1);
    public MainWindow(){
        InitializeComponent();
        _config=_db.LoadConfig();
        _retry.Tick+=async(_,_)=>await TrySync();
        if(_config!=null)Loaded+=async(_,_)=>await Start();
    }
    async void Pair_Click(object sender,RoutedEventArgs e){
        PairButton.IsEnabled=false;SetupError.Text="";
        try{
            var url=HubUrl.Text.Trim().TrimEnd('/');
            if(!Uri.TryCreate(url,UriKind.Absolute,out var uri)||uri.Scheme!="https")
                throw new InvalidOperationException("HTTPS LAN hub URL required.");
            if(PairingKey.Password.Trim().Length<32 ||string.IsNullOrWhiteSpace(BranchName.Text))
                throw new InvalidOperationException("Branch and approved 32+ character device key required.");
            var role=(Role.SelectedItem as ComboBoxItem)?.Content?.ToString()??"kiosk";
            var config=new DeviceConfig(url,role,PairingKey.Password.Trim(),BranchName.Text.Trim());
            await Request(config,HttpMethod.Get,"/v1/catalog",null);
            _db.SaveConfig(config);_config=config;PairingKey.Clear();
            await Start();
        }catch(Exception error){SetupError.Text=error.Message;}
        finally{PairButton.IsEnabled=true;}
    }
    async Task Start(){
        try{
            await Browser.EnsureCoreWebView2Async();
            Browser.CoreWebView2.Settings.AreDevToolsEnabled=false;
            Browser.CoreWebView2.Settings.AreDefaultContextMenusEnabled=false;
            Browser.CoreWebView2.Settings.IsStatusBarEnabled=false;
            var folder=Path.Combine(AppContext.BaseDirectory,"ui");
            if(!Directory.Exists(folder))throw new DirectoryNotFoundException("The BrewPOS kiosk UI assets are missing.");
            Browser.CoreWebView2.SetVirtualHostNameToFolderMapping("brewpos-kiosk.local",
                folder,CoreWebView2HostResourceAccessKind.DenyCors);
            Browser.CoreWebView2.NavigationStarting+=(_,eventArgs)=>{
                if(!eventArgs.Uri.StartsWith("https://brewpos-kiosk.local/",StringComparison.OrdinalIgnoreCase))
                    eventArgs.Cancel=true;
            };
            Browser.CoreWebView2.WebMessageReceived+=OnMessage;
            Browser.Visibility=Visibility.Visible;
            Setup.Visibility=Visibility.Collapsed;
            Browser.CoreWebView2.Navigate("https://brewpos-kiosk.local/index.html");
            WindowStyle=WindowStyle.None;
            WindowState=WindowState.Maximized;
            _retry.Start();
            await TrySync();
        }catch(Exception ex){
            Setup.Visibility=Visibility.Visible;
            Browser.Visibility=Visibility.Collapsed;
            SetupError.Text="Windows WebView2 setup failed: "+ex.Message;
        }
    }
    async Task<JsonElement> Request(DeviceConfig cfg,HttpMethod method,string path,object? payload){
        using var req=new HttpRequestMessage(method,cfg.Url+path);
        req.Headers.Authorization=new AuthenticationHeaderValue("Bearer",cfg.Key);
        if(payload!=null)req.Content=JsonContent.Create(payload);
        using var response=await _http.SendAsync(req);
        var body=await response.Content.ReadAsStringAsync();
        if(!response.IsSuccessStatusCode)throw new HubException((int)response.StatusCode,
            "Café hub "+(int)response.StatusCode+" • "+body[..Math.Min(200,body.Length)]);
        using var doc=JsonDocument.Parse(body);return doc.RootElement.Clone();
    }
    sealed class HubException(int status,string message):Exception(message){
        public int Status {get;}=status;
    }
    async Task<JsonElement> Query(HttpMethod method,string path,object? obj=null){
        if(_config==null)throw new InvalidOperationException("Device is not paired.");
        var reply=await Request(_config,method,path,obj);
        _connected=true;return reply;
    }
    async void OnMessage(object? sender,CoreWebView2WebMessageReceivedEventArgs ev){
        if(!ev.Source.StartsWith("https://brewpos-kiosk.local/",StringComparison.OrdinalIgnoreCase))return;
        string id="";
        try{
            using var d=JsonDocument.Parse(ev.TryGetWebMessageAsString());
            var request=d.RootElement;
            id=request.GetProperty("id").GetString()??"";
            var action=request.GetProperty("action").GetString()??"";
            var payload=request.GetProperty("payload").Clone();
            object result=action switch {
                "bootstrap"=>await Bootstrap(),
                "menu"=>JsonSerializer.Deserialize<JsonElement>(_db.MenuJson),
                "createOrder"=>await CreateOrder(payload),
                "listOrders"=>await ListOrders(),
                "updateStatus"=>await UpdateStatus(payload),
                _=>throw new InvalidOperationException("Unsupported kiosk action.")
            };
            Reply(id,true,result,null);
        }catch(Exception e){Reply(id,false,null,e.Message);}
    }
    void Reply(string id,bool ok,object? result,string? error){
        if(Browser.CoreWebView2==null)return;
        Browser.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(new{id,ok,result,error}));
    }
    async Task<object> Bootstrap(){
        if(_config==null)throw new InvalidOperationException("Pair the kiosk first.");
        try{
            var data=await Query(HttpMethod.Get,"/v1/catalog");
            if(_config.Role=="kiosk"&&!_db.Outstanding())
                _db.SaveMenu(data.GetProperty("products").GetRawText());
        }catch(HttpRequestException){_connected=false;}
        catch(TaskCanceledException){_connected=false;}
        catch(HubException){_connected=false;}
        return new{mode=_config.Role,branchName=_config.BranchName,hubConnected=_connected};
    }
    async Task<object> CreateOrder(JsonElement payload){
        if(_config?.Role!="kiosk")throw new InvalidOperationException("Only kiosk devices can place customer orders.");
        var (id,raw,number)=_db.SaveOrder(payload);
        bool delivered=false;
        try{
            using var doc=JsonDocument.Parse(raw);
            var ack=await Query(HttpMethod.Post,"/v1/orders",doc.RootElement.Clone());
            delivered=ack.GetProperty("accepted").GetBoolean();
            if(delivered)_db.Mark(id,"sent");
        }catch(HubException e)when(e.Status is 400 or 409){
            _db.Mark(id,"review",e.Message);
        }catch(Exception e)when(e is HttpRequestException or TaskCanceledException or HubException){
            _connected=false;
        }
        return new{number,delivered};
    }
    async Task<object> ListOrders(){
        if(_config?.Role=="kiosk")throw new InvalidOperationException("Staff authorization required.");
        return await Query(HttpMethod.Get,"/v1/orders");
    }
    async Task<object> UpdateStatus(JsonElement request){
        if(_config?.Role=="kiosk")throw new InvalidOperationException("Staff authorization required.");
        var id=request.GetProperty("id").GetGuid();
        var next=request.GetProperty("next").GetString()??"";
        if(next is not ("Paid" or "Preparing" or "Ready" or "Completed"))
            throw new InvalidOperationException("Invalid ticket transition.");
        return await Query(HttpMethod.Patch,$"/v1/orders/{id}/status",new{next});
    }
    async Task TrySync(){
        if(_config?.Role!="kiosk" || !await _lock.WaitAsync(0))return;
        try{
            foreach(var (id,raw) in _db.Pending()){
                try{
                    using var doc=JsonDocument.Parse(raw);
                    var result=await Query(HttpMethod.Post,"/v1/orders",doc.RootElement.Clone());
                    if(result.GetProperty("accepted").GetBoolean())_db.Mark(id,"sent");
                    else break;
                }catch(HubException ex)when(ex.Status is 400 or 409){
                    _db.Mark(id,"review",ex.Message);break;
                }catch(Exception ex)when(ex is HttpRequestException or TaskCanceledException or HubException){
                    _connected=false;break;
                }
            }
        }finally{_lock.Release();}
    }
    protected override void OnClosed(EventArgs e){
        _retry.Stop();_http.Dispose();_lock.Dispose();base.OnClosed(e);
    }
}
