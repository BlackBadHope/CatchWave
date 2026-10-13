// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Threading.Channels;
namespace CatchWave.Host;

internal sealed class HostIdentity:IDisposable {
    internal HostTrustedDevices Trusted{get;}
    private readonly RSA key=RSA.Create(2048);
    private readonly SemaphoreSlim streaming=new(1,1);
    internal async Task<IDisposable?> ClaimStream(CancellationToken token)=>await streaming.WaitAsync(1000,token)?new StreamLease(streaming):null;
    private sealed class StreamLease(SemaphoreSlim gate):IDisposable {private int ended;public void Dispose(){if(Interlocked.Exchange(ref ended,1)==0)gate.Release();}}
    internal readonly byte[] Token=RandomNumberGenerator.GetBytes(32);
    internal X509Certificate2 Certificate{get;}
    internal HostIdentity(HostTrustedDevices? trusted=null){
        Trusted=trusted??HostTrustedDevices.Local();
        var request=new CertificateRequest("CN=CatchWave ephemeral host",key,HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false,false,0,true));
        using var generated=request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5),DateTimeOffset.UtcNow.AddDays(7));
        // Schannel cannot use an ephemeral CNG private key. Temporary imported key is deleted on Dispose;
        // no certificate store, persistent pairing file or trusted certificate is created.
        Certificate=X509CertificateLoader.LoadPkcs12(generated.Export(X509ContentType.Pfx),null,X509KeyStorageFlags.UserKeySet);
    }
    internal string PairCode(string address,int port)=>$"CW1/{address}/{port}/{Url(SHA256.HashData(Certificate.RawData))}/{Url(Token)}";
    private static string Url(byte[] value)=>Convert.ToBase64String(value).TrimEnd('=').Replace('+','-').Replace('/','_');
    public void Dispose(){Certificate.Dispose();key.Dispose();CryptographicOperations.ZeroMemory(Token);}
}
internal sealed class HostSession(HostIdentity identity,bool synthetic,bool allowMediaKeys,Func<byte,bool>? commandSink=null,Func<bool>? mediaEnabled=null,HostStatus? status=null,Func<HostPermission>? permission=null,Func<byte,HostPermission,bool>? authorizedSink=null,Func<Task>? audioWriteBarrier=null,Action<bool>? commandDecision=null,Action? commandArrived=null) {
    internal int AcceptedCommands;
    private HostPermission CurrentPermission()=>permission?.Invoke()??new(mediaEnabled?.Invoke()??allowMediaKeys,0);
    internal async Task Run(TcpClient peer,CancellationToken outer) {
        using var peerLease=peer;peer.NoDelay=true;await Run(peer.GetStream(),outer);
    }
    internal async Task Run(Stream source,CancellationToken outer) {
        using var sourceLease=source;using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(outer);
        using var tls=new SslStream(source,false);
        byte[]? controlKey;
        bool usbSource=source is WinUsbBulkStream,tlsReady=false;
        try {using(var deadline=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token)){
            deadline.CancelAfter(3000);
            status?.Log.Event("AUTH",usbSource?"USB_TLS_START":"LAN_TLS_START");
            await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions{ServerCertificate=identity.Certificate,EnabledSslProtocols=SslProtocols.Tls12},deadline.Token);
            tlsReady=true;status?.Log.Event("AUTH",usbSource?"USB_TLS_READY":"LAN_TLS_READY");
            deadline.CancelAfter(2000);Packet auth=await Protocol.Read(tls,deadline.Token);
            if(auth.Kind!=0)throw new IOException("Pairing rejected");controlKey=ControlIdentity.Authenticate(auth.Body,identity.Token);
        }}catch(Exception e){if(!outer.IsCancellationRequested)status?.Log.Event("AUTH",usbSource?(tlsReady?"USB_AUTH_REJECTED":"USB_TLS_REJECTED"):"LAN_PREAUTH_REJECTED",e is OperationCanceledException?1:e is AuthenticationException?2:3);throw;}
        using var streamLease=await identity.ClaimStream(lifetime.Token)??throw new IOException("Streaming slot busy");
        identity.Trusted.Observe(controlKey);
        HostPermission Policy()=>identity.Trusted.PermissionFor(controlKey,CurrentPermission());
        // Pre-auth peers cannot overwrite the active session's UI or occupy its audio slot.
        status?.Begin();
        status?.Stage(HostStage.Authenticated);
        byte[] session=RandomNumberGenerator.GetBytes(16);status?.Log.Session(session);var gate=new CommandGate(session);
        var frames=Channel.CreateBounded<byte[]>(new BoundedChannelOptions(8){FullMode=BoundedChannelFullMode.DropOldest,SingleWriter=true,SingleReader=true});
        var formatReady=new TaskCompletionSource<PcmFormat>(TaskCreationOptions.RunContinuationsAsynchronously);
        var writeLock=new SemaphoreSlim(1);long latest=-1,latestAt=-10000;var clock=Stopwatch.StartNew();
        Task capture=Task.Run(()=>{
            try {
                using IPcmSource source=synthetic?new SilenceSource():new WasapiSource(status?.Log);formatReady.TrySetResult(source.Format);
                long sequence=0,due=0;
                while(!lifetime.IsCancellationRequested){
                    long now=clock.ElapsedMilliseconds;
                    if(now<due){lifetime.Token.WaitHandle.WaitOne((int)Math.Min(20,due-now));continue;}
                    // Absolute sample cadence: ordinary scheduler oversleep must not accumulate drift.
                    // If badly stalled, skip obsolete intervals rather than creating an unlimited burst.
                    Protocol.SkipStaleIntervals(ref sequence,ref due,now);
                    frames.Writer.TryWrite(Protocol.Audio(sequence++,due,source.Frame()));due+=20;
                }
                frames.Writer.TryComplete();
            }catch(Exception e){status?.Failure(HostError.CaptureFailed);formatReady.TrySetException(e);frames.Writer.TryComplete(e);lifetime.Cancel();}
        },CancellationToken.None);
        async Task Write(byte kind,byte[] body){
            using var deadline=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);deadline.CancelAfter(5000);
            await writeLock.WaitAsync(deadline.Token);try{
                await Protocol.Write(tls,kind,body,deadline.Token);
                if(kind==2){
                    if(audioWriteBarrier!=null)await audioWriteBarrier();
                    // Publish completed writes under the same gate used for command validation.
                    // A fast peer can already have read these bytes before WriteAsync returns.
                    Interlocked.Exchange(ref latest,BinaryPrimitives.ReadInt64BigEndian(body));
                    Interlocked.Exchange(ref latestAt,clock.ElapsedMilliseconds);
                }
            }finally{writeLock.Release();}
        }
        Task? sender=null,control=null;
        try {
            PcmFormat format=await formatReady.Task.WaitAsync(lifetime.Token);await Write(1,Protocol.Format(session,format));status?.Stage(HostStage.Format);
            HostPermission policy=Policy();await Write(5,Protocol.Policy(policy));
            sender=Task.Run(async ()=>{try{await foreach(byte[] frame in frames.Reader.ReadAllAsync(lifetime.Token)){
                long stamp=BinaryPrimitives.ReadInt64BigEndian(frame.AsSpan(8));if(clock.ElapsedMilliseconds-stamp>500)continue;
                HostPermission nowPolicy=Policy();if(nowPolicy!=policy){policy=nowPolicy;await Write(5,Protocol.Policy(policy));}
                await Write(2,frame);
                status?.Frame(BinaryPrimitives.ReadInt64BigEndian(frame),frame.AsSpan(16).IndexOfAnyExcept((byte)0)<0);
            }}catch(Exception e){if(!lifetime.IsCancellationRequested)status?.Failure(HostStatus.Classify(e,status.Snapshot.Stage));throw;}});
            control=Task.Run(async ()=>{try{var inputRate=new PacketRate(32);while(!lifetime.IsCancellationRequested){
                Packet command=await Protocol.Read(tls,lifetime.Token);inputRate.Accept(clock.ElapsedMilliseconds);if(command.Kind==6){status?.Client(command.Body);continue;}if(command.Kind!=3)throw new IOException("Unexpected frame");
                long receivedAt=clock.ElapsedMilliseconds,sequence;byte action;bool accepted;
                commandArrived?.Invoke();
                await writeLock.WaitAsync(lifetime.Token);
                try{
                    HostPermission pcNow=CurrentPermission(),permissionNow=Policy();long now=clock.ElapsedMilliseconds;
                    byte[] core=command.Body.Length>=41?command.Body[..41]:command.Body;
                    bool valid=gate.Accept(core,Interlocked.Read(ref latest),now,out sequence,out action,permissionNow.Generation);
                    valid&=ControlIdentity.Verify(controlKey,command.Body);
                    valid&=now-Interlocked.Read(ref latestAt)<750&&now-receivedAt<500;
                    accepted=valid&&permissionNow.Enabled&&permissionNow==Policy()&&(authorizedSink?.Invoke(action,pcNow)??commandSink?.Invoke(action)??MediaKeys.Send(action));
                }finally{writeLock.Release();}
                if(accepted)Interlocked.Increment(ref AcceptedCommands);
                commandDecision?.Invoke(accepted);
                status?.Log.Event("CONTROL",accepted?"ACCEPTED":"REJECTED",action,sequence);
                byte[] ack=new byte[9];BinaryPrimitives.WriteInt64BigEndian(ack,sequence);ack[8]=accepted?(byte)1:(byte)0;await Write(4,ack);
            }}catch(Exception e){if(!lifetime.IsCancellationRequested)status?.Failure(HostStatus.Classify(e,status.Snapshot.Stage));throw;}});
            await Task.WhenAny(sender,control);lifetime.Cancel();source.Close();
            try{await Task.WhenAll(sender,control);}catch(IOException){}catch(OperationCanceledException){}catch(ObjectDisposedException){}
        }catch(Exception e){if(!outer.IsCancellationRequested)status?.Failure(HostStatus.Classify(e,status.Snapshot.Stage));throw;}finally{
            lifetime.Cancel();source.Close();await capture;writeLock.Dispose();
            identity.Trusted.Observe(null);
            status?.End();
        }
    }
}
internal static class Program {
    [STAThread]
    internal static int Main(string[] args){
        if(args.Length==4&&args[0]=="--usb-setup")return UsbWinUsbSetup.Run(args[1..]);
        if(args.Length==2&&args[0]=="--usb-probe"){
            try{var selected=UsbAccessory.Phones().Single(p=>p.Info.Id.Instance.Equals(args[1],StringComparison.OrdinalIgnoreCase));using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(20));
                using var pipe=Task.Run(()=>UsbAccessory.Open(selected,deadline.Token,(code,values)=>Console.WriteLine(code+" "+string.Join(' ',values)))).GetAwaiter().GetResult();Console.WriteLine("USB_ACCESSORY_READY");Task.Delay(2000,deadline.Token).GetAwaiter().GetResult();return 0;
            }catch(Exception e)when(e is IOException or OperationCanceledException or InvalidOperationException){Console.Error.WriteLine(e is IOException?e.Message:"USB_PROBE_FAILED");return 2;}
        }
        if(args.SequenceEqual(new[]{"--usb-list"})){
            try{foreach(var phone in UsbAccessory.Phones())Console.WriteLine($"{phone.Info.Id.Instance} {phone.Info.Descriptor.Vendor:X4}:{phone.Info.Descriptor.Product:X4} port={phone.Info.Port} accessory={phone.Accessory}");return UsbAccessory.Available?0:2;}
            catch(IOException e){Console.Error.WriteLine(e.Message);return 2;}
        }
        if(args.SequenceEqual(new[]{"--usb-self-test"})){try{Console.WriteLine("PASS: "+UsbTest.Run().GetAwaiter().GetResult()+" USB contract checks; no device access");return 0;}catch(Exception e){Console.Error.WriteLine("FAIL: "+e.Message);return 1;}}
        if(args.SequenceEqual(new[]{"--security-self-test"})){try{Console.WriteLine("PASS: "+SecurityTest.Run().GetAwaiter().GetResult()+" security checks; loopback and synthetic audio only");return 0;}catch(Exception e){Console.Error.WriteLine("FAIL: "+e.Message);return 1;}}
        if(args.Length==2&&args[0]=="--ui-preview")return HostPreview.Run(args[1]);
        if(args.Length==3&&args[0]=="--interop-test")return InteropTest.Run(args[1],args[2]).GetAwaiter().GetResult();
        if(args.SequenceEqual(new[]{"--self-test"}))return SelfTest.Run().GetAwaiter().GetResult();
        if(args.Length==3&&args[0]=="--self-test"&&args[1]=="--qr-fixture")return SelfTest.Run(args[2]).GetAwaiter().GetResult();
        // Double-clicking the portable desktop app must open its pairing window.
        if(args.Length==0)args=["--listen","auto"];
        if(args.Contains("--help")){Console.WriteLine("CatchWave Host\n--listen IPv4|auto [--port 49372] [--synthetic] [--disable-media-keys | --enable-media-keys]\nauto: prefer USB tethering, otherwise an active private LAN address. The Host window can switch routes.\nWithout arguments: auto. No wildcard bind, driver installation, autostart or firewall changes.\n--self-test: bounded loopback TLS, synthetic silence, fake key sink, no capture.");return 0;}
        string address="127.0.0.1";int port=49372;bool synthetic=false,media=false,mediaLockedOff=false;string? usbPhone=null;
        try {for(int i=0;i<args.Length;i++)switch(args[i]){
            case "--listen":address=args[++i];break;case "--port":port=int.Parse(args[++i]);break;
            case "--usb-phone":usbPhone=args[++i];break;
            case "--synthetic":synthetic=true;break;case "--enable-media-keys":media=true;break;case "--disable-media-keys":mediaLockedOff=true;break;default:throw new ArgumentException();
        }
        var endpoints=HostNetwork.Available();var chosen=address=="auto"&&endpoints.Count==0?new HostEndpoint("127.0.0.1","USB only"):HostNetwork.Choose(address,endpoints);address=chosen.Address;
        if(port<1024||port>65535)throw new ArgumentException();
        using var identity=new HostIdentity();using var stop=new CancellationTokenSource();
        Console.CancelKeyPress+=(s,e)=>{e.Cancel=true;stop.Cancel();};
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
        var preferences=HostControlSettings.Local();
        using var diagnostics=DiagnosticLog.Local();var state=new HostStatus(diagnostics);using var window=new HostWindow(identity,address,port,synthetic,!mediaLockedOff&&(media||preferences.Load()),stop,preferences.Save,mediaLockedOff,state);
        var server=new HostListener(stop.Token,async(peer,token)=>{
            try{await new HostSession(identity,synthetic,false,status:state,permission:()=>window.Permission,authorizedSink:window.Execute).Run(peer,token);}
            catch(Exception e)when(e is IOException or AuthenticationException or OperationCanceledException or NotSupportedException or System.Runtime.InteropServices.COMException){/* Session owns post-auth status; pre-auth failures cannot replace it. */}
        });
        var usb=new UsbHost(identity,window,state,synthetic,port,()=>server.Address??address,stop.Token);
        if(usbPhone!=null){string selectedSerial=usbPhone;window.Shown+=async(_,_)=>{try{
            var devices=await Task.Run(UsbAccessory.Phones);var selected=devices.SingleOrDefault(p=>p.Info.Id.Instance.Equals(selectedSerial,StringComparison.OrdinalIgnoreCase));
            if(selected==null)window.UsbMessage("USB_PHONE_REMOVED");else await usb.Connect(selected);
        }catch(IOException e){window.UsbMessage(e.Message);}};}
        try{server.Select(address,port).GetAwaiter().GetResult();window.ConfigureNetworks(chosen,async next=>{await server.Select(next.Address,port);state.Listening();},a=>identity.PairCode(a,port),HostNetwork.Available,port);window.ConfigureUsb(usb.Connect,()=>usb.Running);Application.Run(window);}
        finally{diagnostics.Event("STOP","OWNER_CLOSED_HOST");stop.Cancel();usb.DisposeAsync().AsTask().GetAwaiter().GetResult();server.DisposeAsync().AsTask().GetAwaiter().GetResult();}return 0;
        }catch(SocketException e)when(e.SocketErrorCode==SocketError.AddressAlreadyInUse){MessageBox.Show("CatchWave Host уже работает на этом адресе. Перед запуском новой версии закройте его текущее окно.","CatchWave",MessageBoxButtons.OK,MessageBoxIcon.Information);return 2;}
        catch(Exception e)when(e is ArgumentException or SocketException){Console.Error.WriteLine("Host address unavailable. Use --listen auto or an active IPv4 address.");MessageBox.Show("Не удалось открыть адрес Host. Подключите USB-модем или локальную сеть и запустите ярлык снова.","CatchWave",MessageBoxButtons.OK,MessageBoxIcon.Warning);return 2;}
    }
}
