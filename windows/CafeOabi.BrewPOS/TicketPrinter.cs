using System.Runtime.InteropServices;
using System.Text;

namespace CafeOabi.BrewPOS;

public static class TicketPrinter {
    public static string Receipt(string shop,SaleEvent sale) {
        var b=new StringBuilder();
        b.AppendLine(shop.ToUpperInvariant());
        b.AppendLine("BREWPOS WINDOWS • CASH RECEIPT");
        b.AppendLine("OFFLINE / PROVISIONAL — CLOUD PENDING");
        b.AppendLine("--------------------------------");
        b.AppendLine(sale.ReceiptNo);
        b.AppendLine(sale.CreatedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm"));
        b.AppendLine(sale.Service+(string.IsNullOrWhiteSpace(sale.Notes)?"":" • "+sale.Notes));
        b.AppendLine("--------------------------------");
        foreach(var l in sale.Lines) {
            b.AppendLine(l.Name);
            if(!string.IsNullOrWhiteSpace(l.Options))b.AppendLine("  "+l.Options);
            b.AppendLine($"  {l.Quantity} x {Config.Peso(l.UnitCents)}   {Config.Peso(l.TotalCents)}");
        }
        b.AppendLine("--------------------------------");
        b.AppendLine("SUBTOTAL:  "+Config.Peso(sale.SubtotalCents));
        b.AppendLine("DISCOUNT: -"+Config.Peso(sale.DiscountCents));
        b.AppendLine("TOTAL:     "+Config.Peso(sale.TotalCents));
        b.AppendLine("CASH:      "+Config.Peso(sale.TenderedCents));
        b.AppendLine("CHANGE:    "+Config.Peso(sale.ChangeCents));
        b.AppendLine("--------------------------------");
        b.AppendLine("TRANSACTION ID: "+sale.EventId);
        b.AppendLine("Sync pending; not a final tax invoice");
        b.AppendLine("Thank you for visiting!");
        b.AppendLine("\n\n");
        return b.ToString();
    }
    public static string BaristaSlip(SaleEvent sale) {
        var b=new StringBuilder();
        b.AppendLine("BARISTA / KITCHEN TICKET");
        b.AppendLine(sale.ReceiptNo);
        b.AppendLine(sale.Service+(string.IsNullOrWhiteSpace(sale.Notes)?"":" • "+sale.Notes));
        b.AppendLine("--------------------------------");
        foreach(var l in sale.Lines){
            b.AppendLine(l.Quantity+" x "+l.Name);
            if(l.Options.Length>0)b.AppendLine("   "+l.Options);
        }
        b.AppendLine("\n\n");
        return b.ToString();
    }
    public static void Print(string printer,string text) {
        if(string.IsNullOrWhiteSpace(printer))throw new InvalidOperationException("Configure a Windows USB/Bluetooth receipt printer.");
        var payload=Encoding.ASCII.GetBytes("\x1B@" +text.Replace("₱","PHP ").Replace("Café","Cafe").Replace("CAFÉ","CAFE").Replace("\r\n","\n")+"\n\n\n\x1D\x56\0");
        if(!OpenPrinter(printer,out var h,nint.Zero)||h.IsInvalid)
            throw new InvalidOperationException("Cannot open printer "+printer);
        using(h) {
            var di=new DOC_INFO_1{pDocName="Cafe Oabi BrewPOS Ticket",pDatatype="RAW"};
            if(!StartDocPrinter(h,1,ref di))throw new InvalidOperationException("Cannot begin ticket print");
            try {
                if(!StartPagePrinter(h))throw new InvalidOperationException("Cannot start page");
                try{
                    if(!WritePrinter(h,payload,payload.Length,out var written)||written!=payload.Length)
                        throw new InvalidOperationException("Incomplete print spooler write");
                }finally{EndPagePrinter(h);}
            }finally{EndDocPrinter(h);}
        }
    }
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
    struct DOC_INFO_1{
        [MarshalAs(UnmanagedType.LPWStr)]public string? pDocName;
        [MarshalAs(UnmanagedType.LPWStr)]public string? pOutputFile;
        [MarshalAs(UnmanagedType.LPWStr)]public string? pDatatype;
    }
    sealed class PrinterHandle:SafeHandle{
        public PrinterHandle():base(nint.Zero,true){}
        public override bool IsInvalid=>handle==nint.Zero;
        protected override bool ReleaseHandle()=>ClosePrinter(handle);
    }
    [DllImport("winspool.drv",EntryPoint="OpenPrinterW",CharSet=CharSet.Unicode,SetLastError=true)]
    static extern bool OpenPrinter(string name,out PrinterHandle handle,nint defaults);
    [DllImport("winspool.drv",SetLastError=true)]static extern bool ClosePrinter(nint handle);
    [DllImport("winspool.drv",EntryPoint="StartDocPrinterW",CharSet=CharSet.Unicode,SetLastError=true)]
    static extern bool StartDocPrinter(PrinterHandle handle,int level,ref DOC_INFO_1 doc);
    [DllImport("winspool.drv",SetLastError=true)]static extern bool EndDocPrinter(PrinterHandle handle);
    [DllImport("winspool.drv",SetLastError=true)]static extern bool StartPagePrinter(PrinterHandle handle);
    [DllImport("winspool.drv",SetLastError=true)]static extern bool EndPagePrinter(PrinterHandle handle);
    [DllImport("winspool.drv",SetLastError=true)]
    static extern bool WritePrinter(PrinterHandle handle,byte[] data,int count,out int written);
}
