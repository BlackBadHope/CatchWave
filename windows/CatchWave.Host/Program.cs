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
    private readonly RSA key=RSA.Create(2048);
    internal readonly byte[] Token=RandomNumberGenerator.GetBytes(32);
    internal X509Certificate2 Certificate{get;}
    internal HostIdentity(){
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
internal sealed class HostSession(HostIdentity identity,bool synthetic,bool allowMediaKeys,Func<byte,bool>? commandSink=null,Func<bool>? mediaEnabled=null,HostStatus? status=null,Func<HostPermission>? permission=null,Func<byte,HostPermission,bool>? authorizedSink=null) {
    internal int AcceptedCommands;
    private HostPermission CurrentPermission()=>permission?.Invoke()??new(mediaEnabled?.Invoke()??allowMediaKeys,0);
    internal async Task Run(TcpClient peer,CancellationToken outer) {
        using var peerLease=peer;using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(outer);
        status?.Begin();status?.Stage(HostStage.Tls);
        using var tls=new SslStream(peer.GetStream(),false);peer.NoDelay=true;
        try {using(var deadline=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token)){
            deadline.CancelAfter(8000);
            await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions{ServerCertificate=identity.Certificate,EnabledSslProtocols=SslProtocols.Tls12},deadline.Token);
            status?.Stage(HostStage.Auth);Packet auth=await Protocol.Read(tls,deadline.Token);
            if(auth.Kind!=0||auth.Body.Length!=32||!CryptographicOperations.FixedTimeEquals(auth.Body,identity.Token)){status?.Failure(HostError.AuthRejected);throw new IOException("Pairing rejected");}
        }}catch(Exception e){if(!outer.IsCancellationRequested)status?.Failure(HostStatus.Classify(e,status.Snapshot.Stage));throw;}
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
            using var deadline=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);deadline.CancelAfter(1500);
            await writeLock.WaitAsync(deadline.Token);try{await Protocol.Write(tls,kind,body,deadline.Token);}finally{writeLock.Release();}
        }
        Task? sender=null,control=null;
        try {
            PcmFormat format=await formatReady.Task.WaitAsync(lifetime.Token);await Write(1,Protocol.Format(session,format));status?.Stage(HostStage.Format);
            HostPermission policy=CurrentPermission();await Write(5,Protocol.Policy(policy));
            sender=Task.Run(async ()=>{try{await foreach(byte[] frame in frames.Reader.ReadAllAsync(lifetime.Token)){
                long stamp=BinaryPrimitives.ReadInt64BigEndian(frame.AsSpan(8));if(clock.ElapsedMilliseconds-stamp>500)continue;
                HostPermission nowPolicy=CurrentPermission();if(nowPolicy!=policy){policy=nowPolicy;await Write(5,Protocol.Policy(policy));}
                await Write(2,frame);Interlocked.Exchange(ref latest,BinaryPrimitives.ReadInt64BigEndian(frame));Interlocked.Exchange(ref latestAt,clock.ElapsedMilliseconds);
                status?.Frame(BinaryPrimitives.ReadInt64BigEndian(frame),frame.AsSpan(16).IndexOfAnyExcept((byte)0)<0);
            }}catch(Exception e){if(!lifetime.IsCancellationRequested)status?.Failure(HostStatus.Classify(e,status.Snapshot.Stage));throw;}});
            control=Task.Run(async ()=>{try{while(!lifetime.IsCancellationRequested){
                Packet command=await Protocol.Read(tls,lifetime.Token);if(command.Kind==6){status?.Client(command.Body);continue;}if(command.Kind!=3)throw new IOException("Unexpected frame");
                HostPermission permissionNow=CurrentPermission();bool valid=gate.Accept(command.Body,Interlocked.Read(ref latest),clock.ElapsedMilliseconds,out long sequence,out byte action,permissionNow.Generation);
                valid&=clock.ElapsedMilliseconds-Interlocked.Read(ref latestAt)<750;
                bool accepted=valid&&permissionNow.Enabled&&permissionNow==CurrentPermission()&&(authorizedSink?.Invoke(action,permissionNow)??commandSink?.Invoke(action)??MediaKeys.Send(action));if(accepted)Interlocked.Increment(ref AcceptedCommands);
                status?.Log.Event("CONTROL",accepted?"ACCEPTED":"REJECTED",action,sequence);
                byte[] ack=new byte[9];BinaryPrimitives.WriteInt64BigEndian(ack,sequence);ack[8]=accepted?(byte)1:(byte)0;await Write(4,ack);
            }}catch(Exception e){if(!lifetime.IsCancellationRequested)status?.Failure(HostStatus.Classify(e,status.Snapshot.Stage));throw;}});
            await Task.WhenAny(sender,control);lifetime.Cancel();peer.Close();
            try{await Task.WhenAll(sender,control);}catch(IOException){}catch(OperationCanceledException){}catch(ObjectDisposedException){}
        }catch(Exception e){if(!outer.IsCancellationRequested)status?.Failure(HostStatus.Classify(e,status.Snapshot.Stage));throw;}finally{
            lifetime.Cancel();peer.Close();await capture;writeLock.Dispose();
            status?.End();
        }
    }
}
internal static class Program {
    [STAThread]
    internal static int Main(string[] args){
        if(args.Length==3&&args[0]=="--interop-test")return InteropTest.Run(args[1],args[2]).GetAwaiter().GetResult();
        if(args.SequenceEqual(new[]{"--self-test"}))return SelfTest.Run().GetAwaiter().GetResult();
        if(args.Length==3&&args[0]=="--self-test"&&args[1]=="--qr-fixture")return SelfTest.Run(args[2]).GetAwaiter().GetResult();
        if(args.Contains("--help")||args.Length==0){Console.WriteLine("CatchWave Host QR prototype.\n--listen IPv4 [--port 49372] [--synthetic] [--disable-media-keys | --enable-media-keys]\nDefault: loopback only. LAN binding requires an explicit address.\nPair using the local QR window; media control uses the saved owner checkbox choice.\n--disable-media-keys: force off for this pilot without changing the saved preference.\n--enable-media-keys: explicit session-only override.\n--self-test: bounded loopback TLS, synthetic silence, fake key sink, no capture.\nNo installation, autostart, discovery or firewall changes.");return 0;}
        string address="127.0.0.1";int port=49372;bool synthetic=false,media=false,mediaLockedOff=false;
        try {for(int i=0;i<args.Length;i++)switch(args[i]){
            case "--listen":address=args[++i];break;case "--port":port=int.Parse(args[++i]);break;
            case "--synthetic":synthetic=true;break;case "--enable-media-keys":media=true;break;case "--disable-media-keys":mediaLockedOff=true;break;default:throw new ArgumentException();
        }
        if(!IPAddress.TryParse(address,out IPAddress? ip)||ip.AddressFamily!=AddressFamily.InterNetwork||IPAddress.Any.Equals(ip)||port<1024||port>65535)throw new ArgumentException();
        using var identity=new HostIdentity();using var stop=new CancellationTokenSource();
        Console.CancelKeyPress+=(s,e)=>{e.Cancel=true;stop.Cancel();};var listener=new TcpListener(ip,port);listener.Start(1);
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
        var preferences=HostControlSettings.Local();
        using var diagnostics=DiagnosticLog.Local();var state=new HostStatus(diagnostics);using var window=new HostWindow(identity,address,port,synthetic,!mediaLockedOff&&(media||preferences.Load()),stop,preferences.Save,mediaLockedOff,state);
        Task server=Task.Run(async()=>{
            try {while(!stop.IsCancellationRequested){
                TcpClient peer=await listener.AcceptTcpClientAsync(stop.Token);
                try {await new HostSession(identity,synthetic,false,status:state,permission:()=>window.Permission,authorizedSink:window.Execute).Run(peer,stop.Token);}catch(Exception e)when(e is IOException or AuthenticationException or OperationCanceledException or NotSupportedException or System.Runtime.InteropServices.COMException){if(!stop.IsCancellationRequested)state.Failure(HostStatus.Classify(e,state.Snapshot.Stage));}
            }}catch(OperationCanceledException){}finally{listener.Stop();}
        });
        try{Application.Run(window);}finally{diagnostics.Event("STOP","OWNER_CLOSED_HOST");stop.Cancel();server.GetAwaiter().GetResult();}return 0;
        }catch(ArgumentException){Console.Error.WriteLine("Invalid arguments. Use --help.");return 2;}
    }
}
