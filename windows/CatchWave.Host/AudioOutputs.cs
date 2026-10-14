// SPDX-License-Identifier: GPL-3.0-or-later
using System.Runtime.InteropServices;
namespace CatchWave.Host;

internal sealed record AudioOutput(string Id, string Name, uint State, bool IsDefault = false)
{
    internal bool Active => State == 1;
    internal string Availability => State switch
    {
        1 => L.T("available"), 2 => L.T("disabled in Windows"), 4 => L.T("device missing"),
        8 => L.T("not connected"), _ => L.T("unavailable")
    };
    public override string ToString() => Name + (Active ? IsDefault ? L.T(" · Windows default") : "" : " · " + Availability);
}

/** Read-only MMDevice access. Names/IDs are local UI data, never diagnostic journal fields. */
internal static class AudioOutputs
{
    internal static object Enumerator() => Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"))!)!;

    internal static IReadOnlyList<AudioOutput> Read()
    {
        object owner = Enumerator(); IMMDeviceCollection? collection = null;
        try
        {
            var enumerator = (IMMDeviceEnumerator)owner;
            string? defaultId = null; IMMDevice? current = null;
            try { Check(enumerator.GetDefaultAudioEndpoint(0, 1, out current)); Check(current.GetId(out defaultId)); }
            catch (COMException) { }
            finally { if (current != null) Marshal.ReleaseComObject(current); }
            Check(enumerator.EnumAudioEndpoints(0, 15, out collection)); Check(collection.GetCount(out uint count));
            if (count > 128) throw new IOException("Too many audio endpoints");
            var result = new List<AudioOutput>();
            for (uint index = 0; index < count; index++)
            {
                IMMDevice? device = null;
                try
                {
                    Check(collection.Item(index, out device)); Check(device.GetId(out string id)); Check(device.GetState(out uint state));
                    result.Add(new(id, Name(device, index), state, id == defaultId));
                }
                catch (COMException) { /* A disappearing device must not break the whole picker. */ }
                finally { if (device != null) Marshal.ReleaseComObject(device); }
            }
            return result.OrderByDescending(output => output.Active).ThenBy(output => output.Name, StringComparer.CurrentCultureIgnoreCase).ToArray();
        }
        finally { if (collection != null) Marshal.ReleaseComObject(collection); Marshal.ReleaseComObject(owner); }
    }

    private static string Name(IMMDevice device, uint index)
    {
        IAudioPropertyStore? properties = null; AudioPropVariant value = default;
        try
        {
            Check(device.OpenPropertyStore(0, out properties));
            var key = new AudioPropertyKey { Format = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), Id = 14 };
            Check(properties.GetValue(ref key, out value));
            string? name = value.Type == 31 ? Marshal.PtrToStringUni(value.Pointer) : null;
            if (!string.IsNullOrWhiteSpace(name)) return new string(name.Where(c => !char.IsControl(c)).Take(160).ToArray());
        }
        catch (COMException) { }
        finally { PropVariantClear(ref value); if (properties != null) Marshal.ReleaseComObject(properties); }
        return L.T("Audio output ") + (index + 1);
    }
    private static void Check(int hr) => Marshal.ThrowExceptionForHR(hr);
    [DllImport("ole32.dll")] private static extern int PropVariantClear(ref AudioPropVariant value);
}

// Windows SDK MMDevice/Property System ABI. PROPVARIANT is 24 bytes on x64, 16 on x86.
[ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDeviceCollection
{
    [PreserveSig] int GetCount(out uint count);
    [PreserveSig] int Item(uint index, out IMMDevice device);
}
[StructLayout(LayoutKind.Sequential)] internal struct AudioPropertyKey { internal Guid Format; internal uint Id; }
[StructLayout(LayoutKind.Sequential)] internal struct AudioPropVariant
{
    internal ushort Type, Reserved1, Reserved2, Reserved3; internal IntPtr Pointer, Extra;
}
[ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioPropertyStore
{
    [PreserveSig] int GetCount(out uint count);
    [PreserveSig] int GetAt(uint index, out AudioPropertyKey key);
    [PreserveSig] int GetValue(ref AudioPropertyKey key, out AudioPropVariant value);
}
