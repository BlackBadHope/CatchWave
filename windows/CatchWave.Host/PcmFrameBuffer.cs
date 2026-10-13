// SPDX-License-Identifier: GPL-3.0-or-later
namespace CatchWave.Host;

/** Fixed-size FIFO; overflow discards oldest PCM, never creates an unbounded audio delay. */
internal sealed class PcmFrameBuffer(int capacity)
{
    private readonly byte[] bytes = new byte[capacity > 0 ? capacity : throw new ArgumentOutOfRangeException(nameof(capacity))];
    private int head, count;
    internal int Count => count;
    internal long DroppedBytes { get; private set; }
    internal void Clear() { head = count = 0; }
    internal void Write(ReadOnlySpan<byte> value)
    {
        if (value.Length > bytes.Length) { DroppedBytes += value.Length - bytes.Length; value = value[^bytes.Length..]; }
        int discard = Math.Max(0, count + value.Length - bytes.Length);
        head = (head + discard) % bytes.Length; count -= discard; DroppedBytes += discard;
        int tail = (head + count) % bytes.Length, first = Math.Min(value.Length, bytes.Length - tail);
        value[..first].CopyTo(bytes.AsSpan(tail)); value[first..].CopyTo(bytes); count += value.Length;
    }
    internal int Read(Span<byte> output)
    {
        int take = Math.Min(count, output.Length), first = Math.Min(take, bytes.Length - head);
        bytes.AsSpan(head, first).CopyTo(output); bytes.AsSpan(0, take - first).CopyTo(output[first..]);
        output[take..].Clear(); head = (head + take) % bytes.Length; count -= take; return take;
    }
}
