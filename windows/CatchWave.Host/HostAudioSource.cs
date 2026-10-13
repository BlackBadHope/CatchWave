// SPDX-License-Identifier: GPL-3.0-or-later
using System.Runtime.InteropServices;
using System.Text.Json;
namespace CatchWave.Host;

internal sealed record AudioChoice(string? Id, string Name)
{
    internal static readonly AudioChoice Default = new(null, "Windows по умолчанию");
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

/** A source lease freezes selection across opening/capture/closing, shared by LAN and USB. */
internal sealed class HostAudioSource
{
    private readonly object gate = new();
    private readonly HostAudioSettings? settings;
    private readonly Func<string?, DiagnosticLog?, IPcmSource> factory;
    private AudioChoice selected;
    private int leases;
    private string state = "", availability = "";
    private bool saveFailed;
    internal HostAudioSource(HostAudioSettings? settings = null, Func<string?, DiagnosticLog?, IPcmSource>? factory = null)
    {
        this.settings = settings; this.factory = factory ?? ((id, log) => new WasapiSource(log, id));
        selected = settings?.Load() ?? AudioChoice.Default;
    }
    internal AudioChoice Selected { get { lock (gate) return selected; } }
    internal bool Busy { get { lock (gate) return leases > 0; } }
    internal string Hint
    {
        get
        {
            lock (gate)
            {
                if (saveFailed) return "Выбор не сохранён; действует до закрытия Host.";
                if (state == "SOURCE_FORMAT_UNSUPPORTED") return "Нужен формат Windows: моно/стерео, 44,1 / 48 / 96 кГц.";
                if (state == "SOURCE_UNAVAILABLE" || availability != "") return "Источник недоступен" + (availability == "" ? "." : ": " + availability + ".") + " Выберите другой выход.";
                return leases > 0 ? "Для смены источника отключите телефон." : "Направьте звук Windows на этот же выход.";
            }
        }
    }
    internal void Observe(IReadOnlyList<AudioOutput> outputs)
    {
        lock (gate)
        {
            var output = selected.Id == null ? outputs.FirstOrDefault(value => value.IsDefault) : outputs.FirstOrDefault(value => value.Id == selected.Id);
            availability = output?.Active == true ? "" : output?.Availability ?? "выход отсутствует";
        }
    }
    internal bool Select(AudioOutput? output)
    {
        lock (gate)
        {
            if (leases > 0 || output != null && !output.Active) return false;
            var next = output == null ? AudioChoice.Default : new AudioChoice(output.Id, output.Name);
            if (!next.Valid) return false;
            selected = next; state = availability = ""; saveFailed = settings != null && !settings.Save(next); return true;
        }
    }
    internal IPcmSource Open(DiagnosticLog? log)
    {
        AudioChoice choice;
        lock (gate) { leases++; choice = selected; state = ""; }
        try { var source=factory(choice.Id, log);lock(gate)availability="";return new Lease(this, source, log); }
        catch (Exception error) { Failed(error, log); Release(); throw; }
    }
    private void Failed(Exception error, DiagnosticLog? log)
    {
        string code = error is NotSupportedException ? "SOURCE_FORMAT_UNSUPPORTED" : "SOURCE_UNAVAILABLE";
        lock (gate) state = code; log?.Event("ROUTE", code);
    }
    private void Release() { lock (gate) leases--; }
    private sealed class Lease(HostAudioSource owner, IPcmSource source, DiagnosticLog? log) : IPcmSource
    {
        private int disposed;
        public PcmFormat Format => source.Format;
        public byte[] Frame()
        {
            try { return source.Frame(); }
            catch (Exception error) when (error is COMException or IOException or NotSupportedException) { owner.Failed(error, log); throw; }
        }
        public void Dispose() { if (Interlocked.Exchange(ref disposed, 1) == 0) try { source.Dispose(); } finally { owner.Release(); } }
    }
}
