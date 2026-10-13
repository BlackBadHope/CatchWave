// SPDX-License-Identifier: GPL-3.0-or-later
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
namespace CatchWave.Host;

internal sealed record HostEndpoint(string Address,string Name,bool Usb=false) {
    public override string ToString()=>$"{(Usb?"USB-модем":"Локальная сеть")} · {Address}";
}
internal static class HostNetwork {
    internal static bool UsbTether(string description)=>description.Contains("RNDIS",StringComparison.OrdinalIgnoreCase)||description.Contains("Remote NDIS",StringComparison.OrdinalIgnoreCase)||description.Contains("USB NCM",StringComparison.OrdinalIgnoreCase);
    internal static bool PrivateV4(IPAddress ip){byte[] b=ip.GetAddressBytes();return b.Length==4&&(b[0]==10||b[0]==172&&b[1]>=16&&b[1]<=31||b[0]==192&&b[1]==168);}
    internal static IReadOnlyList<HostEndpoint> Available()=>NetworkInterface.GetAllNetworkInterfaces()
        .Where(n=>n.OperationalStatus==OperationalStatus.Up&&n.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211)
        .Where(n=>!n.Description.Contains("Virtual",StringComparison.OrdinalIgnoreCase)&&!n.Description.Contains("Hyper-V",StringComparison.OrdinalIgnoreCase))
        .SelectMany(n=>n.GetIPProperties().UnicastAddresses.Where(a=>PrivateV4(a.Address)).Select(a=>new HostEndpoint(a.Address.ToString(),n.Name,
            UsbTether(n.Description))))
        .DistinctBy(n=>n.Address).OrderByDescending(n=>n.Usb).ThenBy(n=>n.Name,StringComparer.Ordinal).ToArray();
    internal static HostEndpoint Choose(string requested,IReadOnlyList<HostEndpoint> available){
        if(requested=="auto")return available.FirstOrDefault(n=>n.Usb)??available.FirstOrDefault()??throw new ArgumentException("No active private IPv4 network. Connect USB tethering or LAN.");
        if(!IPAddress.TryParse(requested,out var ip)||ip.AddressFamily!=AddressFamily.InterNetwork||IPAddress.Any.Equals(ip))throw new ArgumentException("Invalid IPv4");
        return available.FirstOrDefault(n=>n.Address==requested)??new(requested,"Explicit address");
    }
}

/** One selected interface and one session at a time. Bind failure preserves the old listener. */
internal sealed class HostListener(CancellationToken outer,Func<TcpClient,CancellationToken,Task> session):IAsyncDisposable {
    private readonly SemaphoreSlim gate=new(1);
    private CancellationTokenSource? active;
    private TcpListener? listener;
    private Task worker=Task.CompletedTask;
    private readonly object peersGate=new();private readonly HashSet<Task> peers=[];
    private readonly SemaphoreSlim slots=new(4,4);
    private readonly HashSet<IPAddress> sources=[];
    internal string? Address{get;private set;}
    internal async Task Select(string address,int port){
        await gate.WaitAsync(outer).ConfigureAwait(false);
        TcpListener? next=null;
        try {
            if(Address==address)return;
            next=new TcpListener(IPAddress.Parse(address),port);next.Start(16);
            active?.Cancel();listener?.Stop();await worker.ConfigureAwait(false);active?.Dispose();
            outer.ThrowIfCancellationRequested();
            active=CancellationTokenSource.CreateLinkedTokenSource(outer);listener=next;next=null;Address=address;
            var bound=listener;var token=active.Token;
            worker=Task.Run(async()=>{try{while(!token.IsCancellationRequested){
                // Leave excess peers in the bounded TCP backlog instead of closing every new
                // phone while four unauthenticated handshakes occupy the worker slots.
                await slots.WaitAsync(token).ConfigureAwait(false);
                TcpClient peer;
                try{peer=await bound.AcceptTcpClientAsync(token).ConfigureAwait(false);}
                catch{slots.Release();throw;}
                var ip=((IPEndPoint)peer.Client.RemoteEndPoint!).Address;
                lock(peersGate){
                    if(!sources.Add(ip)){peer.Dispose();slots.Release();continue;}
                    Task? task=null;
                    task=Task.Run(async()=>{
                        try{await session(peer,token).ConfigureAwait(false);}
                        catch(Exception e)when(e is IOException or SocketException or OperationCanceledException or System.Security.Authentication.AuthenticationException or ObjectDisposedException){}
                        finally{peer.Dispose();lock(peersGate){peers.Remove(task!);sources.Remove(ip);}slots.Release();}
                    },CancellationToken.None);
                    peers.Add(task);
                }
            }}catch(OperationCanceledException)when(token.IsCancellationRequested){}catch(SocketException)when(token.IsCancellationRequested){}finally{
                bound.Stop();Task[] draining;lock(peersGate)draining=peers.ToArray();await Task.WhenAll(draining).ConfigureAwait(false);
            }},CancellationToken.None);
        }finally{next?.Stop();gate.Release();}
    }
    public async ValueTask DisposeAsync(){await gate.WaitAsync().ConfigureAwait(false);try{active?.Cancel();listener?.Stop();await worker.ConfigureAwait(false);active?.Dispose();}finally{slots.Dispose();gate.Release();gate.Dispose();}}
}
