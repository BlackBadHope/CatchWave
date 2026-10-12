// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Security.Cryptography;

namespace CatchWave.Host;

internal readonly record struct Packet(byte Kind, byte[] Body);

internal static class Protocol
{
    internal const int MaxPacket = 32768;

    internal static void SkipStaleIntervals(ref long sequence, ref long due, long now)
    {
        long behind = (now - due) / 20;
        if (behind > 8)
        {
            sequence += behind;
            due += behind * 20;
        }
    }

    internal static async Task<Packet> Read(Stream stream, CancellationToken token)
    {
        byte[] header = new byte[5];
        await stream.ReadExactlyAsync(header, token);
        int size = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(1));
        if (header[0] > 6 || size < 0 || size > MaxPacket)
            throw new IOException("Invalid frame");
        byte[] body = new byte[size];
        await stream.ReadExactlyAsync(body, token);
        return new(header[0], body);
    }

    internal static async Task Write(Stream stream, byte kind, byte[] body, CancellationToken token)
    {
        if (kind > 6 || body.Length > MaxPacket)
            throw new IOException("Invalid frame");
        byte[] frame = new byte[body.Length + 5];
        frame[0] = kind;
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(1), body.Length);
        body.CopyTo(frame, 5);
        await stream.WriteAsync(frame, token);
        await stream.FlushAsync(token);
    }

    internal static byte[] Format(byte[] session, PcmFormat format)
    {
        byte[] body = new byte[24];
        session.CopyTo(body, 0);
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(16), format.Rate);
        body[20] = (byte)format.Channels;
        body[21] = 16;
        BinaryPrimitives.WriteInt16BigEndian(body.AsSpan(22), (short)format.Samples);
        return body;
    }

    internal static byte[] Audio(long sequence, long timestamp, byte[] pcm)
    {
        byte[] body = new byte[pcm.Length + 16];
        BinaryPrimitives.WriteInt64BigEndian(body, sequence);
        BinaryPrimitives.WriteInt64BigEndian(body.AsSpan(8), timestamp);
        pcm.CopyTo(body, 16);
        return body;
    }

    internal static byte[] Policy(HostPermission permission)
    {
        byte[] body = new byte[9];
        body[0] = permission.Enabled ? (byte)1 : (byte)0;
        BinaryPrimitives.WriteInt64BigEndian(body.AsSpan(1), permission.Generation);
        return body;
    }

    internal static byte[] ClientState(
        byte output,
        byte sensors,
        bool wants,
        bool effective,
        long generation = 0
    )
    {
        byte[] body = new byte[13];
        body[0] = 1;
        body[1] = output;
        body[2] = sensors;
        body[3] = wants ? (byte)1 : (byte)0;
        body[4] = effective ? (byte)1 : (byte)0;
        BinaryPrimitives.WriteInt64BigEndian(body.AsSpan(5), generation);
        return body;
    }
}

internal readonly record struct PcmFormat(int Rate, int Channels)
{
    internal int Samples => Rate / 50;
    internal int Bytes => Samples * Channels * 2;
    internal bool Valid => (Rate == 44100 || Rate == 48000 || Rate == 96000) && Channels is 1 or 2;
}

internal sealed class CommandGate(byte[] session)
{
    private long last = -1,
        lastAt = -1000;

    internal bool Accept(
        byte[] body,
        long latestAudio,
        long now,
        out long sequence,
        out byte action,
        long policyGeneration = 0
    )
    {
        sequence = -1;
        action = 0;
        if (body.Length != 41)
            return false;
        sequence = BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(16));
        long observed = BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(24));
        action = body[32];
        if (
            !CryptographicOperations.FixedTimeEquals(body.AsSpan(0, 16), session)
            || sequence <= last
            || sequence < 0
        )
            return false;
        last = sequence; // A rejected request also consumes its number: it cannot be retried later.
        if (BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(33)) != policyGeneration)
            return false;
        if (
            observed < 0
            || observed > latestAudio
            || latestAudio - observed > 25
            || action is < 1 or > 3
            || now - lastAt < 650
        )
            return false;
        lastAt = now;
        return true;
    }
}
