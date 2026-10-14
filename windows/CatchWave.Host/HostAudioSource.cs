// SPDX-License-Identifier: GPL-3.0-or-later
using System.Runtime.InteropServices;
using System.Text.Json;
namespace CatchWave.Host;

internal sealed record AudioChoice(string? Id, string Name)
{
    internal static readonly AudioChoice Default = new(null, L.T("Windows default"));
    internal bool Valid => Id == null || Id.Length is > 0 and <= 1024 && !Id.Any(char.IsControl)
        && Name is { Length: > 0 and <= 160 } && !Name.Any(char.IsControl);
}

/** Stores only the selected local Windows audio endpoint; no pairing, audio or command authority. */
internal sealed class HostAudioSettings(string path)
{
    internal static HostAudioSettings Local() => new(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CatchWave", "audio-source.json"));
    internal AudioChoice Load()
    {
        try
        {
            if (new FileInfo(path).Length > 4096) return AudioChoice.Default;
            var value = JsonSerializer.Deserialize<AudioChoice>(File.ReadAllText(path));
            return value != null && value.Valid ? value.Id == null ? AudioChoice.Default : value : AudioChoice.Default;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { return AudioChoice.Default; }
    }
    internal bool Save(AudioChoice choice)
    {
        if (!choice.Valid) return false;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
            File.WriteAllText(path + ".tmp", JsonSerializer.Serialize(choice)); File.Move(path + ".tmp", path, true); return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
    }
}

/** LAN and USB share a saved source choice. Normalized live sources switch on the capture thread. */
internal sealed class HostAudioSource
{
    private readonly object gate = new();
    private readonly HostAudioSettings? settings;
    private readonly Func<string?, DiagnosticLog?, IPcmSource> factory;
    private AudioChoice selected;
    private int leases;
    private long revision;
    internal bool LiveSwitch { get; }
    private string state = "", availability = "";
    private bool saveFailed;
    internal HostAudioSource(HostAudioSettings? settings = null, Func<string?, DiagnosticLog?, IPcmSource>? factory = null, bool liveSwitch = false)
    {
        this.settings = settings; this.factory = factory ?? ((id, log) => new WasapiSource(log, id));
        selected = settings?.Load() ?? AudioChoice.Default; LiveSwitch = liveSwitch;
    }
    internal AudioChoice Selected { get { lock (gate) return selected; } }
    internal bool Busy { get { lock (gate) return leases > 0; } }
    internal string Hint
    {
        get
        {
            lock (gate)
            {
                if (saveFailed) return L.T("Choice not saved; it applies until the Host is closed.");
                if (state == "SOURCE_FORMAT_UNSUPPORTED") return L.T("Windows could not prepare audio for this output. Choose another one.");
                if (state == "SOURCE_UNAVAILABLE" || availability != "") return L.T("Source unavailable") + (availability == "" ? "." : ": " + availability + ".") + L.T(" Choose another output.");
                return LiveSwitch ? L.T("The source can be changed without reconnecting the phone.") : leases > 0 ? L.T("Disconnect the phone to change the source.") : L.T("Send Windows audio to this same output.");
            }
        }
    }
    internal void Observe(IReadOnlyList<AudioOutput> outputs)
    {
        lock (gate)
        {
            var output = selected.Id == null ? outputs.FirstOrDefault(value => value.IsDefault) : outputs.FirstOrDefault(value => value.Id == selected.Id);
            availability = output?.Active == true ? "" : output?.Availability ?? L.T("output missing");
        }
    }
    internal bool Select(AudioOutput? output)
    {
        lock (gate)
        {
            if (!LiveSwitch && leases > 0 || output != null && !output.Active) return false;
            var next = output == null ? AudioChoice.Default : new AudioChoice(output.Id, output.Name);
            if (!next.Valid) return false;
            selected = next; revision++; state = availability = ""; saveFailed = settings != null && !settings.Save(next); return true;
        }
    }
    internal IPcmSource Open(DiagnosticLog? log)
    {
        AudioChoice choice; long openingRevision;
        lock (gate) { leases++; choice = selected; openingRevision = revision; state = ""; }
        try { var source=factory(choice.Id, log);lock(gate)availability="";return new Lease(this, source, log, openingRevision); }
        catch (Exception error) { Failed(error, log); Release(); throw; }
    }
    private void Failed(Exception error, DiagnosticLog? log)
    {
        string code = error is NotSupportedException || error is COMException && error.HResult == unchecked((int)0x88890008) ? "SOURCE_FORMAT_UNSUPPORTED" : "SOURCE_UNAVAILABLE";
        lock (gate) state = code; log?.Event("ROUTE", code);
    }
    private void Release() { lock (gate) leases--; }
    private sealed class Lease(HostAudioSource owner, IPcmSource source, DiagnosticLog? log, long openingRevision) : IPcmSource
    {
        private int disposed;
        private IPcmSource? current = source;
        private long observedRevision = openingRevision;
        public PcmFormat Format { get; } = source.Format;
        public byte[] Frame()
        {
            lock (owner.gate)
            {
                ObjectDisposedException.ThrowIf(disposed != 0, this);
                try
                {
                    if (owner.LiveSwitch && observedRevision != owner.revision)
                    {
                        current?.Dispose(); current = null;
                        var next = owner.factory(owner.selected.Id, log);
                        if (next.Format != Format) { next.Dispose(); throw new NotSupportedException("Live source format must stay normalized."); }
                        current = next; observedRevision = owner.revision; owner.state = owner.availability = "";
                        log?.Event("ROUTE", "LIVE_SOURCE_CHANGED");
                    }
                    return current?.Frame() ?? throw new IOException("Selected source is unavailable.");
                }
                catch (Exception error) when (error is COMException or IOException or NotSupportedException) { owner.Failed(error, log); throw; }
            }
        }
        public void Dispose()
        {
            lock (owner.gate) if (Interlocked.Exchange(ref disposed, 1) == 0)
                try { current?.Dispose(); current = null; } finally { owner.Release(); }
        }
    }
}
