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
            Check(Session(output).GetAwaiter().GetResult(), "selected mono 44.1 kHz PCM reaches authenticated stream");
        }
        finally { File.Delete(path); File.Delete(path + ".tmp"); }
        return checks;
    }

    private sealed class MarkerSource : IPcmSource
    {
        public PcmFormat Format => new(44100, 1);
        public byte[] Frame() { var data = new byte[Format.Bytes]; data[0] = 27; return data; }
        public void Dispose() { }
    }
    private static async Task<bool> Session(AudioOutput output)
    {
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5)); var token = stop.Token;
        using var identity = new HostIdentity(new HostTrustedDevices()); using var signer = new SignedPeer();
        string? opened = null;
        var selection = new HostAudioSource(factory: (id, _) => { opened = id; return new MarkerSource(); }); selection.Select(output);
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
            bool correct = opened == output.Id && format.Kind == 1 && BinaryPrimitives.ReadInt32BigEndian(format.Body.AsSpan(16)) == 44100 && format.Body[20] == 1 && frame.Kind == 2 && frame.Body.Length == 1780 && frame.Body[16] == 27;
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
