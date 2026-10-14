// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
namespace CatchWave.Host;

internal static class SelfTest {
    private static int checks;
    private static void Check(bool value,string name){if(!value)throw new Exception(name);checks++;}
    private static byte[] Command(byte[] session,long sequence,long observed,byte action,long generation=0){byte[] b=new byte[41];session.CopyTo(b,0);BinaryPrimitives.WriteInt64BigEndian(b.AsSpan(16),sequence);BinaryPrimitives.WriteInt64BigEndian(b.AsSpan(24),observed);b[32]=action;BinaryPrimitives.WriteInt64BigEndian(b.AsSpan(33),generation);return b;}
    internal static async Task<int> Run(string? qrFixture=null){
        try {
            // These suites assert the Russian UI text; English is covered by LanguageTest.
            bool language=L.Russian;L.Russian=true;
            try{checks+=HostUiTest.Run();checks+=AudioSourceTest.Run();}finally{L.Russian=language;}
            checks+=LanguageTest.Run();
            checks+=await UsbTest.Run();
            checks+=await NetworkTest.Run();
            checks+=DiagnosticTest.Run();
            string preference=Path.Combine(Path.GetTempPath(),"catchwave-selftest-"+Guid.NewGuid().ToString("N")+".json");
            try{var settings=new HostControlSettings(preference);Check(!settings.Load(),"default control disabled");Check(settings.Save(true)&&new HostControlSettings(preference).Load(),"control choice survives restart");Check(settings.Save(false)&&!new HostControlSettings(preference).Load(),"explicit disabled persists");}finally{File.Delete(preference);File.Delete(preference+".tmp");}
            byte[] png=PairingQr.Png(PairingQr.Fixture);Check(png.AsSpan(0,8).SequenceEqual(new byte[]{137,80,78,71,13,10,26,10}),"QR PNG signature");
            using(var bytes=new MemoryStream(png))using(var image=Image.FromStream(bytes))Check(image.Width==image.Height&&image.Width>=250,"QR dimension and image decode");
            using(var bytes=new MemoryStream(PairingQr.Png(PairingQr.Fixture.Replace("192.0.2.1","192.168.255.254"))))using(var image=Image.FromStream(bytes))Check(image.Width<=400,"maximum payload fits without QR resampling");
            if(qrFixture!=null)File.WriteAllBytes(qrFixture,png);
            byte[] session=Enumerable.Range(0,16).Select(i=>(byte)i).ToArray();var gate=new CommandGate(session);
            Check(gate.Accept(Command(session,1,95,1),100,1000,out _,out _),"fresh command");
            Check(!gate.Accept(Command(session,1,95,1),100,1800,out _,out _),"replay");
            Check(!gate.Accept(Command(session,2,70,2),100,1800,out _,out _),"stale observed audio");
            Check(!gate.Accept(Command(session,3,101,2),100,1800,out _,out _),"future audio");
            Check(!gate.Accept(Command(new byte[16],4,100,2),100,1800,out _,out _),"wrong session");
            Check(!gate.Accept(Command(session,4,100,9),100,1800,out _,out _),"unknown action");
            Check(gate.Accept(Command(session,5,100,2),100,1800,out _,out _),"next accepted");
            Check(!gate.Accept(Command(session,6,100,3),100,1900,out _,out _),"rate limiting");
            Check(!gate.Accept(new byte[100],100,3000,out _,out _),"size limit");
            byte[] converted=WasapiSource.Convert(BitConverter.GetBytes(-1f).Concat(BitConverter.GetBytes(.5f)).Concat(BitConverter.GetBytes(float.NaN)).ToArray(),32,true);
            Check(BitConverter.ToInt16(converted)==short.MinValue&&BitConverter.ToInt16(converted,2)==16384&&BitConverter.ToInt16(converted,4)==0,"PCM float clamps and nonfinite");
            Check(MediaKeys.StructureSize==(IntPtr.Size==8?40:28),"SendInput layout");
            long due=40,sequence=2;Protocol.SkipStaleIntervals(ref sequence,ref due,55);Check(due==40&&sequence==2,"ordinary oversleep preserves cadence");
            Protocol.SkipStaleIntervals(ref sequence,ref due,560);Check(due==560&&sequence==28,"long stall skips obsolete intervals");
            var epochGate=new CommandGate(session);Check(!epochGate.Accept(Command(session,1,100,1,0),100,3000,out _,out _,2),"PC off-on rejects old policy generation even if OFF update was coalesced");Check(epochGate.Accept(Command(session,2,100,1,2),100,3000,out _,out _,2),"new PC permission generation accepted");
            await using var frame=new MemoryStream();await Protocol.Write(frame,2,Protocol.Audio(7,140,new byte[3840]),CancellationToken.None);frame.Position=0;Packet decoded=await Protocol.Read(frame,CancellationToken.None);
            Check(decoded.Kind==2&&decoded.Body.Length==3856&&BinaryPrimitives.ReadInt64BigEndian(decoded.Body)==7,"wire frame");
            await using var bad=new MemoryStream(new byte[]{2,0,1,0,0});try{await Protocol.Read(bad,CancellationToken.None);throw new Exception("oversize allowed");}catch(IOException){checks++;}
            using var identity=new HostIdentity(new HostTrustedDevices());
            checks+=await SecurityTest.Run();
            await FramePublication(identity,200,true);await FramePublication(identity,1000,false);
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(8));
            await Session(identity,false,deadline.Token);await Session(identity,true,deadline.Token);
            var status=new HostStatus();status.Begin();status.Stage(HostStage.Authenticated);status.Frame(0,true);
            Check(!status.Snapshot.Effective(true),"sent PCM without phone output is not effective control");
            status.Client(Protocol.ClientState(1,1,false,false));Check(!status.Snapshot.Effective(true),"phone control OFF never effective");
            status.Client(Protocol.ClientState(1,2,true,true));Check(status.Snapshot.Effective(true),"fresh confirmed output and both permissions");Check(!status.Snapshot.Effective(true,1),"old report generation cannot show effective control");Check(!status.Snapshot.Effective(false),"host policy OFF overrides saved phone choice");
            Check(!(status.Snapshot with{ClientAgeMs=1500}).Effective(true),"stale phone report not effective");
            status.Failure(HostError.CaptureFailed);status.End();Check(status.Snapshot.Error==HostError.CaptureFailed&&!status.Snapshot.Effective(true),"first error survives session cleanup");
            try{status.Client(Protocol.ClientState(1,8,true,true));throw new Exception("invalid state accepted");}catch(IOException){checks++;}
            Console.WriteLine($"PASS: {checks} checks; loopback TLS + synthetic PCM; WASAPI capture, media key input, LAN and phone untouched.");return 0;
        }catch(Exception e){Console.Error.WriteLine("FAIL: "+e.GetBaseException().Message);return 1;}
    }
    private static async Task FramePublication(HostIdentity identity,int delay,bool accept){
        using var signer=new SignedPeer();signer.Approve(identity);long epoch=identity.Trusted.PermissionFor(signer.PublicKey,new(true,0)).Generation;
        using var stop=new CancellationTokenSource(TimeSpan.FromSeconds(6));var token=stop.Token;
        var listener=new TcpListener(IPAddress.Loopback,0);listener.Start(1);
        var written=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var decision=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);int first=0;
        var arrived=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        Task server=Task.Run(async()=>{using var peer=await listener.AcceptTcpClientAsync(token);try{
            await new HostSession(identity,true,true,_=>true,audioWriteBarrier:async()=>{
                if(Interlocked.Increment(ref first)==1){written.TrySetResult();await release.Task.WaitAsync(token);}
            },commandDecision:value=>decision.TrySetResult(value),commandArrived:()=>arrived.TrySetResult()).Run(peer,token);
        }catch(IOException){}catch(OperationCanceledException){};});
        try{
            using var client=new TcpClient();await client.ConnectAsync(IPAddress.Loopback,((IPEndPoint)listener.LocalEndpoint).Port,token);
            using var tls=new SslStream(client.GetStream(),false,(_,cert,_,_)=>cert!=null&&CryptographicOperations.FixedTimeEquals(SHA256.HashData(cert.GetRawCertData()),SHA256.HashData(identity.Certificate.RawData)));
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions{TargetHost="CatchWave",EnabledSslProtocols=SslProtocols.Tls12},token);
            await Protocol.Write(tls,0,signer.Auth(identity.Token),token);Packet format=await Protocol.Read(tls,token);await Protocol.Read(tls,token);Packet audio=await Protocol.Read(tls,token);await written.Task.WaitAsync(token);
            await Protocol.Write(tls,3,signer.Sign(Command(format.Body[..16],1,BinaryPrimitives.ReadInt64BigEndian(audio.Body),1,epoch)),token);
            await arrived.Task.WaitAsync(token);await Task.WhenAny(decision.Task,Task.Delay(delay,token));
            Check(!decision.Task.IsCompleted,"command must wait for completed audio publication, not reject a frame already read by peer");
            release.TrySetResult();Packet ack;do{ack=await Protocol.Read(tls,token);}while(ack.Kind is 2 or 5);
            Check(ack.Kind==4&&ack.Body[8]==(accept?1:0),accept?"immediate command after audio publication accepted":"command delayed beyond 500 ms cannot execute after recovery");
            client.Close();await server.WaitAsync(token);
        }finally{release.TrySetResult();stop.Cancel();listener.Stop();try{await server.WaitAsync(TimeSpan.FromSeconds(1));}catch{};}
    }
    private static async Task Session(HostIdentity identity,bool correct,CancellationToken token){
        using var signer=new SignedPeer();signer.Approve(identity);
        long Epoch(long generation)=>identity.Trusted.PermissionFor(signer.PublicKey,new(true,generation)).Generation;
        var listener=new TcpListener(IPAddress.Loopback,0);listener.Start(1);
        try {
            int port=((IPEndPoint)listener.LocalEndpoint).Port;
            int allowed=1,generation=0;var status=new HostStatus();
            Task server=Task.Run(async()=>{using var peer=await listener.AcceptTcpClientAsync(token);try{await new HostSession(identity,true,true,_=>true,status:status,permission:()=>new(Volatile.Read(ref allowed)!=0,Volatile.Read(ref generation))).Run(peer,token);}catch(IOException){}catch(AuthenticationException){}catch(OperationCanceledException){};});
            using var client=new TcpClient();await client.ConnectAsync(IPAddress.Loopback,port,token);
            using var tls=new SslStream(client.GetStream(),false,(_,cert,_,_)=>cert!=null&&CryptographicOperations.FixedTimeEquals(SHA256.HashData(cert.GetRawCertData()),SHA256.HashData(identity.Certificate.RawData)));
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions{TargetHost="CatchWave",EnabledSslProtocols=SslProtocols.Tls12},token);
            await Protocol.Write(tls,0,correct?signer.Auth(identity.Token):new byte[32],token);
            if(!correct){try{await Protocol.Read(tls,token);throw new Exception("unauthenticated stream");}catch(EndOfStreamException){checks++;}}
            else {
                Packet format=await Protocol.Read(tls,token);Check(format.Kind==1&&format.Body.Length==24&&BinaryPrimitives.ReadInt32BigEndian(format.Body.AsSpan(16))==48000,"authenticated format");
                Packet policy=await Protocol.Read(tls,token);Check(policy.Kind==5&&policy.Body.SequenceEqual(Protocol.Policy(new(true,Epoch(0)))),"authenticated host policy");
                byte[] session=format.Body[..16];Packet audio=await Protocol.Read(tls,token);Check(audio.Kind==2&&audio.Body.Length==3856&&audio.Body.AsSpan(16).IndexOfAnyExcept((byte)0)<0,"synthetic PCM");
                long observed=BinaryPrimitives.ReadInt64BigEndian(audio.Body);await Protocol.Write(tls,3,signer.Sign(Command(session,1,observed,1,Epoch(0))),token);
                Packet ack;do{ack=await Protocol.Read(tls,token);}while(ack.Kind is 2 or 5);
                Check(ack.Kind==4&&ack.Body[8]==1,"command accepted without sending key");
                await Protocol.Write(tls,3,signer.Sign(Command(session,1,observed,1,Epoch(0))),token);do{ack=await Protocol.Read(tls,token);}while(ack.Kind is 2 or 5);Check(ack.Body[8]==0,"replay rejected on TLS");
                for(int pass=0;pass<2;pass++){
                    Volatile.Write(ref allowed,pass);Volatile.Write(ref generation,pass+1);long previous=observed;
                    do{audio=await Protocol.Read(tls,token);if(audio.Kind==2)observed=BinaryPrimitives.ReadInt64BigEndian(audio.Body);}while(observed<previous+36);
                    await Protocol.Write(tls,3,signer.Sign(Command(session,pass+2,observed,1,Epoch(pass+1))),token);do{ack=await Protocol.Read(tls,token);}while(ack.Kind is 2 or 5);
                    Check(ack.Kind==4&&ack.Body[8]==pass,pass==0?"live PC control OFF rejects":"live PC control ON accepts");
                }
            }
            client.Close();await server.WaitAsync(token);if(correct)Check(status.Snapshot.Stage==HostStage.Disconnected&&status.Snapshot.Error==HostError.PeerClosed,"normal phone EOF gives disconnected state");
        }finally{listener.Stop();}
    }
}
