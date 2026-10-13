// SPDX-License-Identifier: GPL-3.0-or-later
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
namespace CatchWave.Host;

/** Only successfully authenticated physical USB routes; never raw phone serials or pairing data. */
internal sealed class UsbAutoSettings(string? path=null) {
    private readonly object gate=new();private readonly HashSet<string> ports=new(StringComparer.Ordinal);
    internal static UsbAutoSettings Local()=>new(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"CatchWave","usb-auto-routes.json"));
    internal static string Port(UsbAccessory.Phone phone)=>Convert.ToHexString(SHA256.HashData(Encoding.ASCII.GetBytes(phone.Info.Filter+":"+phone.Info.Port)));
    private static string Route(UsbAccessory.Phone phone)=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Port(phone)+"|"+phone.Info.Id.Device.ToUpperInvariant()+"|"+phone.Info.Id.Instance.ToUpperInvariant())));
    internal bool Allowed(UsbAccessory.Phone phone){lock(gate)return ports.Contains(Route(phone));}
    internal void Load(){if(path==null)return;try{
        var info=new FileInfo(path);if(info.Length>4096)return;
        var saved=JsonSerializer.Deserialize<string[]>(File.ReadAllText(path));if(saved==null||saved.Length>8||saved.Any(v=>v==null||v.Length!=64||!v.All(Uri.IsHexDigit)))return;
        lock(gate){ports.Clear();foreach(string value in saved)ports.Add(value);}
    }catch(Exception e)when(e is IOException or UnauthorizedAccessException or JsonException){} }
    internal bool Remember(UsbAccessory.Phone phone){lock(gate){
        string port=Route(phone);if(ports.Contains(port))return true;if(ports.Count>=8)return false;ports.Add(port);
        if(path==null)return true;
        try{Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);string temp=path+".tmp";File.WriteAllText(temp,JsonSerializer.Serialize(ports));File.Move(temp,path,true);return true;}
        catch(Exception e)when(e is IOException or UnauthorizedAccessException){ports.Remove(port);return false;}
    }}
}

/** One attempt per physical attachment, not a session-restart loop. Metadata probes do not open USB. */
internal sealed class UsbAutoPolicy(UsbAutoSettings settings) {
    private readonly HashSet<string> attempted=new(StringComparer.Ordinal);
    internal void Manual(UsbAccessory.Phone phone)=>attempted.Add(UsbAutoSettings.Port(phone));
    internal UsbAccessory.Phone? Select(IReadOnlyList<UsbAccessory.Phone> phones,bool running){
        var present=phones.Select(UsbAutoSettings.Port).ToHashSet(StringComparer.Ordinal);
        attempted.IntersectWith(present);
        if(running){attempted.UnionWith(present);return null;}
        if(phones.Count!=1||!settings.Allowed(phones[0])||!attempted.Add(UsbAutoSettings.Port(phones[0])))return null;
        return phones[0];
    }
}

/** Coalesced Windows device-change events. No installer, vendor scan loop or parallel USB session. */
internal sealed class UsbAutoConnect:IDisposable {
    private readonly HostWindow window;private readonly UsbHost host;private readonly UsbAutoPolicy policy;
    private readonly System.Windows.Forms.Timer debounce=new(){Interval=400};
    private bool closed,checking,dirty;
    internal UsbAutoConnect(HostWindow window,UsbHost host,UsbAutoSettings settings){
        this.window=window;this.host=host;policy=new(settings);
        window.UsbDevicesChanged+=Changed;window.Shown+=Shown;debounce.Tick+=async(_,_)=>await Check();
    }
    internal Task Manual(UsbAccessory.Phone phone){policy.Manual(phone);return host.Connect(phone);}
    private void Shown(object? sender,EventArgs e)=>Changed();
    private void Changed(){if(closed)return;dirty=true;debounce.Stop();debounce.Start();}
    private async Task Check(){
        debounce.Stop();if(closed||checking)return;checking=true;dirty=false;
        try{
            if(!UsbAccessory.Available)return;
            var phones=await Task.Run(UsbAccessory.Phones);
            if(closed)return;
            var selected=policy.Select(phones,host.Running);
            if(selected!=null){window.UsbAutoStarting();await host.Connect(selected,allowSetup:false);}
        }catch(Exception e)when(e is IOException or System.Runtime.InteropServices.SEHException or DllNotFoundException or UnauthorizedAccessException){
            // Auto detection is optional; a failed probe never interrupts LAN or installs anything.
        }finally{checking=false;if(dirty&&!closed)debounce.Start();}
    }
    public void Dispose(){if(closed)return;closed=true;debounce.Stop();debounce.Dispose();window.UsbDevicesChanged-=Changed;window.Shown-=Shown;}
}
