// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
namespace CatchWave.Host;

internal static class SecurityTest {
    private static int checks;
    private static void Check(bool value,string name){if(!value)throw new Exception("Security: "+name);checks++;}
    internal static async Task<int> Run(){
        checks=0;using var signer=new SignedPeer();using var attacker=new SignedPeer();var trust=new HostTrustedDevices();using var identity=new HostIdentity(trust);
        string brokenTrust=Path.Combine(Path.GetTempPath(),"CatchWave-trust-"+Guid.NewGuid().ToString("N")+".json");
        try{File.WriteAllText(brokenTrust,"[null]");var broken=new HostTrustedDevices(brokenTrust);Check(!broken.PermissionFor(signer.PublicKey,new(true,0)).Enabled,"null trust entries fail closed without breaking startup");}
        finally{File.Delete(brokenTrust);}
        byte[] key=ControlIdentity.Authenticate(signer.Auth(identity.Token),identity.Token)!;
        Check(key.SequenceEqual(signer.PublicKey),"bounded P256 SPKI authentication");
        Check(ControlIdentity.Authenticate(identity.Token,identity.Token)==null,"legacy AUTH is audio only");
        trust.Observe(key);Check(!trust.PermissionFor(key,new(true,0)).Enabled,"QR and PC toggle cannot approve a new key");
        Check(!trust.Approve("00"),"owner approval binds observed fingerprint");signer.Approve(identity);
        Check(trust.PermissionFor(key,new(true,0)).Enabled,"explicit approval grants known key");
        Check(!trust.PermissionFor(attacker.PublicKey,new(true,0)).Enabled,"another QR holder remains blocked");
        Check(!trust.PermissionFor(key,new(false,1)).Enabled,"owner PC revoke wins over saved key");
        byte[] core=new byte[41];RandomNumberGenerator.Fill(core);
        byte[] signed=signer.Sign(core);Check(ControlIdentity.Verify(key,signed),"DER ECDSA verified");
        Check(!ControlIdentity.Verify(key,core),"unsigned commands rejected");Check(!ControlIdentity.Verify(attacker.PublicKey,signed),"wrong key rejected");
        signed[3]^=1;Check(!ControlIdentity.Verify(key,signed),"changed session or command rejected");
        var rate=new PacketRate(32);for(int i=0;i<32;i++)rate.Accept(100);try{rate.Accept(100);throw new Exception("Unbounded rate");}catch(IOException){checks++;}rate.Accept(1100);
        var status=new HostStatus();status.Begin();int callbacks=0;status.Changed+=()=>callbacks++;
        for(int i=0;i<50000;i++)status.Client(Protocol.ClientState(2,2,true,true));Check(callbacks<=2,"telemetry flood cannot enqueue every snapshot");
        using var stop=new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var reserve=new TcpListener(IPAddress.Loopback,0);reserve.Start();int port=((IPEndPoint)reserve.LocalEndpoint).Port;reserve.Stop();
        await using var listener=new HostListener(stop.Token,async(peer,token)=>await new HostSession(identity,true,true,_=>throw new Exception("Untrusted media input"),status:status).Run(peer,token));
        await listener.Select("127.0.0.1",port);status.Begin();status.Frame(99,false);
        var stalledPeers=new List<TcpClient>();
        try{for(int i=2;i<=5;i++){var stalled=new TcpClient();stalledPeers.Add(stalled);stalled.Client.Bind(new IPEndPoint(IPAddress.Parse("127.0.0."+i),0));await stalled.ConnectAsync(IPAddress.Loopback,port,stop.Token);}
        await Task.Delay(100,stop.Token);
        using var client=new TcpClient();await client.ConnectAsync(IPAddress.Loopback,port,stop.Token);
        using var tls=new SslStream(client.GetStream(),false,(_,cert,_,_)=>cert!=null&&CryptographicOperations.FixedTimeEquals(SHA256.HashData(cert.GetRawCertData()),SHA256.HashData(identity.Certificate.RawData)));
        await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions{TargetHost="CatchWave",EnabledSslProtocols=SslProtocols.Tls12},stop.Token);
        await Protocol.Write(tls,0,attacker.Auth(identity.Token),stop.Token);Packet format=await Protocol.Read(tls,stop.Token),policy=await Protocol.Read(tls,stop.Token),audio=await Protocol.Read(tls,stop.Token);
        Check(format.Kind==1&&audio.Kind==2,"stalled unauthenticated source does not block another source");
        Check(policy.Body[0]==0,"unapproved signed peer receives denied policy");
        byte[] command=new byte[41];format.Body[..16].CopyTo(command,0);BinaryPrimitives.WriteInt64BigEndian(command.AsSpan(16),1);BinaryPrimitives.WriteInt64BigEndian(command.AsSpan(24),BinaryPrimitives.ReadInt64BigEndian(audio.Body));command[32]=1;BinaryPrimitives.WriteInt64BigEndian(command.AsSpan(33),BinaryPrimitives.ReadInt64BigEndian(policy.Body.AsSpan(1)));
        await Protocol.Write(tls,3,attacker.Sign(command),stop.Token);Packet ack;do{ack=await Protocol.Read(tls,stop.Token);}while(ack.Kind is 2 or 5);Check(ack.Body[8]==0,"QR holder signed command blocked by owner approval");
        await Task.Delay(3100,stop.Token);Check(status.Snapshot.Live,"pre-auth timeout cannot replace authenticated session status");
        stop.Cancel();return checks;
        }finally{foreach(var stalled in stalledPeers)stalled.Dispose();}
    }
}
