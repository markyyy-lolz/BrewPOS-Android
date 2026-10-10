using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CafeOabi.BrewPOS;
public sealed class SecureSession(string root) {
    readonly string _file=Path.Combine(root,"session.dpapi");
    static readonly byte[] Entropy=Encoding.UTF8.GetBytes("Azurate.CafeOabi.BrewPOS.v1");
    public void Save(StoredSession data) {
        var raw=Encoding.UTF8.GetBytes(JsonSerializer.Serialize(data,Config.Json));
        var enc=ProtectedData.Protect(raw,Entropy,DataProtectionScope.CurrentUser);
        File.WriteAllBytes(_file+".tmp",enc);File.Move(_file+".tmp",_file,true);
    }
    public StoredSession? Load() {
        try {
            if(!File.Exists(_file))return null;
            var bytes=ProtectedData.Unprotect(File.ReadAllBytes(_file),Entropy,DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<StoredSession>(bytes,Config.Json);
        }catch{return null;}
    }
    public void Clear(){if(File.Exists(_file))File.Delete(_file);}
}
