// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Diagnostics;

namespace CatchWave.Host;

internal enum HostStage
{
    Listening,
    Tcp,
    Tls,
    Auth,
    Authenticated,
    Format,
    SilenceSent,
    AudioSent,
    Disconnected,
    Error,
}

internal enum HostError
{
    None,
    TlsHandshake,
    AuthRejected,
    AuthTimeout,
    Protocol,
    CaptureFailed,
    WriteTimeout,
    PeerClosed,
    Network,
    Internal,
}

internal sealed record HostPermission(bool Enabled, long Generation);

internal sealed record HostSnapshot(
    HostStage Stage,
    HostError Error,
    HostStage FailedAt,
    long Seconds,
    long Frames,
    long Skipped,
    int ClientOutput,
    int Sensors,
    bool ClientWants,
    bool ClientEffective,
    long ClientAgeMs,
    long ClientGeneration
)
{
    internal bool Live => Stage is HostStage.SilenceSent or HostStage.AudioSent;

    internal bool Effective(bool allowed, long generation = 0) =>
        Live
        && ClientAgeMs >= 0
        && ClientAgeMs < 1500
        && ClientOutput > 0
        && ClientWants
        && ClientEffective
        && allowed
        && ClientGeneration == generation;
}

internal sealed class HostStatus
{
    private readonly object gate = new();
    private readonly Stopwatch clock = new();
    private HostStage stage = HostStage.Listening,
        failedAt;
    private HostError error;
    private long frames,
        skipped,
        lastSequence = -1,
        reported = -1000,
        clientAt = -1;
    private int output,
        sensors;
    private bool wants,
        effective;
    private long clientGeneration;
    internal readonly DiagnosticLog Log;
    private long diagnosticAt = -10000;

    internal HostStatus(DiagnosticLog? log = null)
    {
        Log = log ?? new DiagnosticLog();
    }

    internal event Action? Changed;

    internal void Listening()
    {
        Log.Event("ROUTE", "OWNER_SELECTED_INTERFACE");
        lock (gate)
        {
            clock.Reset();
            stage = HostStage.Listening;
            error = HostError.None;
            frames = skipped = 0;
            lastSequence = clientAt = -1;
            output = sensors = 0;
            wants = effective = false;
        }
        Changed?.Invoke();
    }

    internal HostSnapshot Snapshot
    {
        get
        {
            lock (gate)
                return new(
                    stage,
                    error,
                    failedAt,
                    clock.ElapsedMilliseconds / 1000,
                    frames,
                    skipped,
                    output,
                    sensors,
                    wants,
                    effective,
                    clientAt < 0 ? -1 : clock.ElapsedMilliseconds - clientAt,
                    clientGeneration
                );
        }
    }

    internal void Begin()
    {
        Log.Begin();
        Log.Event("RECONNECT", "PEER_CONNECTED");
        lock (gate)
        {
            clock.Restart();
            diagnosticAt = -10000;
            reported = -1000;
            stage = HostStage.Tcp;
            error = HostError.None;
            frames = skipped = 0;
            lastSequence = clientAt = -1;
            output = sensors = 0;
            wants = effective = false;
        }
        Changed?.Invoke();
    }

    internal void Stage(HostStage value)
    {
        Log.Event("STAGE", value.ToString().ToUpperInvariant());
        lock (gate)
        {
            if (error != HostError.None)
                return;
            stage = value;
        }
        Changed?.Invoke();
    }

    internal void Failure(HostError code)
    {
        lock (gate)
        {
            if (error != HostError.None)
                return;
            failedAt = stage;
            stage = code == HostError.PeerClosed ? HostStage.Disconnected : HostStage.Error;
            error = code;
            effective = false;
        }
        Log.Event("STOP", code.ToString().ToUpperInvariant(), (long)Snapshot.FailedAt);
        Console.WriteLine($"CatchWave stage={Snapshot.Stage} error={code} at={Snapshot.FailedAt}");
        Changed?.Invoke();
    }

    internal void End()
    {
        Log.Event(
            "STOP",
            Snapshot.Error == HostError.None
                ? "SESSION_ENDED"
                : Snapshot.Error.ToString().ToUpperInvariant()
        );
        lock (gate)
        {
            effective = false;
            output = sensors = 0;
            if (error == HostError.None)
                stage = HostStage.Disconnected;
        }
        Changed?.Invoke();
    }

    internal void Frame(long sequence, bool silence)
    {
        bool notify;
        lock (gate)
        {
            frames++;
            if (lastSequence >= 0 && sequence > lastSequence + 1)
                skipped += sequence - lastSequence - 1;
            lastSequence = sequence;
            HostStage next = silence ? HostStage.SilenceSent : HostStage.AudioSent;
            notify = stage != next || clock.ElapsedMilliseconds - reported >= 500;
            if (error == HostError.None)
                stage = next;
            if (notify)
                reported = clock.ElapsedMilliseconds;
        }
        if (notify)
            Changed?.Invoke();
        if (clock.ElapsedMilliseconds - diagnosticAt >= 2000)
        {
            diagnosticAt = clock.ElapsedMilliseconds;
            Log.Event(
                "SNAPSHOT",
                silence ? "SILENT_SOURCE" : "PCM_SENT",
                frames,
                skipped,
                sequence,
                Snapshot.ClientOutput,
                Snapshot.ClientAgeMs
            );
        }
    }

    internal void Client(byte[] body)
    {
        if (
            body.Length != 13
            || body[0] != 1
            || body[1] > 2
            || body[2] > 5
            || body[3] > 1
            || body[4] > 1
            || BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(5)) < 0
        )
            throw new IOException("Invalid client state");
        lock (gate)
        {
            output = body[1];
            sensors = body[2];
            wants = body[3] == 1;
            effective = body[4] == 1;
            clientGeneration = BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(5));
            clientAt = clock.ElapsedMilliseconds;
        }
        Changed?.Invoke();
    }

    internal static HostError Classify(Exception error, HostStage at) =>
        error switch
        {
            System.Security.Authentication.AuthenticationException => HostError.TlsHandshake,
            OperationCanceledException => at is HostStage.Tls or HostStage.Auth
                ? HostError.AuthTimeout
                : HostError.WriteTimeout,
            EndOfStreamException => HostError.PeerClosed,
            System.Net.Sockets.SocketException => HostError.Network,
            IOException => at == HostStage.Tls ? HostError.TlsHandshake : HostError.Protocol,
            _ => HostError.Internal,
        };
}
