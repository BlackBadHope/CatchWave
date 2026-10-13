// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
namespace CatchWave.Host;

internal static class AudioSourceTest
{
    internal static int Run()
    {
        int checks = 0;
        void Check(bool value, string name) { if (!value) throw new Exception("Audio selection: " + name); checks++; }
        string path = Path.Combine(Path.GetTempPath(), "CatchWave-audio-" + Guid.NewGuid().ToString("N") + ".json");
        var output = new AudioOutput("fixture-output", "Fixture headphones", 1);
        try
        {
            var settings = new HostAudioSettings(path);
            Check(settings.Load() == AudioChoice.Default, "missing settings use default");
            string? opened = "not opened";
            var source = new HostAudioSource(settings, (id, _) => { opened = id; return new SilenceSource(); });
            using (source.Open(null)) Check(opened == null && source.Busy && !source.Select(output), "default source and live selection lock");
            Check(source.Select(output) && settings.Load().Id == output.Id, "explicit endpoint persists");
            using (source.Open(null)) Check(opened == output.Id, "explicit endpoint reaches capture factory");
            source.Observe([]);
            Check(source.Selected.Id == output.Id && source.Hint.Contains("недоступен"), "removed endpoint never falls back");
            Check(!source.Select(output with { State = 8 }) && source.Selected.Id == output.Id, "unplugged endpoint rejected");
            Check(source.Select(null) && settings.Load() == AudioChoice.Default, "owner can restore default");
            foreach (string invalid in new[] { "broken", "null", "{\"Id\":\"device\",\"Name\":null}", new string('x', 4097) })
            {
                File.WriteAllText(path, invalid); Check(settings.Load() == AudioChoice.Default, "bounded invalid preference");
            }
            using var log = new DiagnosticLog(); int attempts = 0;
            var broken = new HostAudioSource(factory: (_, _) => { attempts++; throw new COMException(); });
            Check(broken.Select(output), "missing fixture selected");
            try { using var unused = broken.Open(log); throw new Exception("Missing source silently opened"); } catch (COMException) { }
            Check(attempts == 1 && !broken.Busy && broken.Selected.Id == output.Id && broken.Hint.Contains("недоступен"), "open failure releases lock without default retry");
            Check(!log.Text().Contains(output.Id) && !log.Text().Contains(output.Name), "journal excludes names and IDs");
            using var entered = new ManualResetEventSlim(); using var release = new ManualResetEventSlim();
            var opening = new HostAudioSource(factory: (_, _) => { entered.Set(); if (!release.Wait(2000)) throw new Exception("Fixture opening timed out"); return new SilenceSource(); });
            Task<IPcmSource> pending = Task.Run(() => opening.Open(null));
            try { Check(entered.Wait(1000) && opening.Busy && !opening.Select(output), "opening race freezes selection"); }
            finally { release.Set(); }
            using (pending.GetAwaiter().GetResult()) { }
            Check(!opening.Busy && opening.Select(output), "completed lease releases source selection");
            Check(Marshal.SizeOf<AudioPropVariant>() == (IntPtr.Size == 8 ? 24 : 16), "native PROPVARIANT layout");
            int closed=0;
            var live=new HostAudioSource(factory:(id,_)=>new LiveMarker(id==null?(byte)11:(byte)22,()=>closed++),liveSwitch:true);
            using(var capture=live.Open(log)){
                Check(capture.Frame()[0]==11&&live.Select(output),"live choice accepted during capture");
                Check(capture.Frame()[0]==22&&capture.Format==new PcmFormat(48000,2)&&closed==1,"source changes in existing stream and closes old capture");
                Check(live.Select(null)&&capture.Frame()[0]==11&&closed==2,"default route restores without renegotiation");
            }
            Check(closed==3&&!live.Busy,"live capture is disposed exactly once");
            var mismatch=new HostAudioSource(factory:(id,_)=>id==null?new LiveMarker(11,()=>closed++):new MarkerSource(),liveSwitch:true);
            using(var capture=mismatch.Open(log)){
                mismatch.Select(output);
                try{capture.Frame();throw new Exception("Live format mismatch accepted");}catch(NotSupportedException){}
                Check(mismatch.Hint.Contains("Выберите другой"),"live format failure is explicit without old-device fallback");
            }
            Check(!mismatch.Busy,"failed live switch releases lease");
            Check(!log.Text().Contains(output.Id)&&!log.Text().Contains(output.Name),"live journal excludes endpoint identity");
            var ring=new PcmFrameBuffer(23);var expected=new List<byte>();var random=new Random(47);long dropped=0;
            for(int i=0;i<200;i++){
                byte[] input=new byte[random.Next(0,35)];random.NextBytes(input);expected.AddRange(input);
                if(expected.Count>23){int obsolete=expected.Count-23;expected.RemoveRange(0,obsolete);dropped+=obsolete;}
                ring.Write(input);byte[] read=new byte[random.Next(0,30)];int take=Math.Min(read.Length,expected.Count);
                Check(ring.Read(read)==take&&read.AsSpan(0,take).SequenceEqual(expected.Take(take).ToArray())&&read.AsSpan(take).IndexOfAnyExcept((byte)0)<0,"bounded FIFO wrap/overflow/zero fill");
                expected.RemoveRange(0,take);
            }
            Check(ring.DroppedBytes==dropped,"source overflow is counted");ring.Clear();Check(ring.Count==0,"discontinuity discards old source PCM");
            Check(Session(output).GetAwaiter().GetResult(), "selected mono 44.1 kHz PCM reaches authenticated stream");
            Check(Session(output,true).GetAwaiter().GetResult(), "source changes inside one authenticated TLS stream without format renegotiation");
        }
        finally { File.Delete(path); File.Delete(path + ".tmp"); }
        return checks;
    }

    private sealed class LiveMarker(byte marker,Action close) : IPcmSource
    {
        public PcmFormat Format=>new(48000,2);
        public byte[] Frame(){var data=new byte[Format.Bytes];data[0]=marker;return data;}
        public void Dispose()=>close();
    }
    private sealed class MarkerSource : IPcmSource
    {
        public PcmFormat Format => new(44100, 1);
        public byte[] Frame() { var data = new byte[Format.Bytes]; data[0] = 27; return data; }
        public void Dispose() { }
    }
    private static async Task<bool> Session(AudioOutput output,bool live=false)
    {
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5)); var token = stop.Token;
        using var identity = new HostIdentity(new HostTrustedDevices()); using var signer = new SignedPeer();
        string? opened = null;
        var selection = new HostAudioSource(factory: (id, _) => { opened = id; return live ? new LiveMarker(id==null?(byte)11:(byte)22,()=>{}) : new MarkerSource(); },liveSwitch:live); if(!live)selection.Select(output);
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(1);
        Task server = Task.Run(async () =>
        {
            using var peer = await listener.AcceptTcpClientAsync(token);
            try { await new HostSession(identity, false, false, audio: selection).Run(peer, token); }
            catch (Exception error) when (error is IOException or OperationCanceledException) { }
        });
        try
        {
            using var client = new TcpClient(); await client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port, token);
            using var tls = new SslStream(client.GetStream(), false, (_, certificate, _, _) => certificate != null && CryptographicOperations.FixedTimeEquals(SHA256.HashData(certificate.GetRawCertData()), SHA256.HashData(identity.Certificate.RawData)));
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "CatchWave", EnabledSslProtocols = SslProtocols.Tls12 }, token);
            await Protocol.Write(tls, 0, signer.Auth(identity.Token), token);
            var format = await Protocol.Read(tls, token); await Protocol.Read(tls, token); var frame = await Protocol.Read(tls, token);
            bool correct = format.Kind == 1 && BinaryPrimitives.ReadInt32BigEndian(format.Body.AsSpan(16)) == (live?48000:44100) && format.Body[20] == (live?2:1) && frame.Kind == 2 && frame.Body.Length == (live?3856:1780) && frame.Body[16] == (live?11:27);
            if(live){
                correct &= selection.Select(output);bool changed=false;
                for(int i=0;i<10&&!changed;i++){var next=await Protocol.Read(tls,token);correct&=next.Kind!=1;if(next.Kind==2)changed=next.Body[16]==22;}
                correct &= changed;
            }
            correct &= opened==output.Id;
            client.Close(); await server.WaitAsync(token); return correct && !selection.Busy;
        }
        finally { stop.Cancel(); listener.Stop(); try { await server.WaitAsync(TimeSpan.FromSeconds(1)); } catch (OperationCanceledException) { } }
    }

    /** Bounded real capture; prints only counters/format, never audio, names, endpoint IDs or credentials. */
    internal static int Probe(string choice)
    {
        try
        {
            string? id = null;
            if (choice != "default")
            {
                var outputs = AudioOutputs.Read();
                if (!int.TryParse(choice, out int index) || index < 0 || index >= outputs.Count || !outputs[index].Active) throw new IOException("Audio choice unavailable");
                id = outputs[index].Id;
            }
            using var log = new DiagnosticLog(); using var source = new WasapiSource(log, id);
            int frames = 0, nonzero = 0; var clock = Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < 3000) { var frame = source.Frame(); frames++; if (frame.AsSpan().IndexOfAnyExcept((byte)0) >= 0) nonzero++; Thread.Sleep(20); }
            bool selected = id == null || source.SelectedId == id;
            Console.WriteLine($"CAPTURE_OPENED selected_id_matches={selected} rate={source.Format.Rate} channels={source.Format.Channels} frames={frames} nonzero_frames={nonzero}");
            Console.WriteLine(log.Text()); return selected ? 0 : 1;
        }
        catch (Exception error) when (error is COMException or IOException or NotSupportedException)
        { Console.WriteLine("CAPTURE_FAILED " + (error is NotSupportedException ? "UNSUPPORTED_FORMAT" : "UNAVAILABLE")); return 2; }
    }
}
