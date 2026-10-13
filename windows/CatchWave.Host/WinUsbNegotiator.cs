// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;
namespace CatchWave.Host;

/** Uses an existing registered WinUSB interface only for AOA control requests, never ADB data. */
internal static class WinUsbNegotiator {
    [StructLayout(LayoutKind.Sequential,Pack=1)]
    internal struct SetupPacket {internal byte Kind,Request;internal ushort Value,Index,Length;}
    [StructLayout(LayoutKind.Sequential)]
    private struct InterfaceData {internal uint Size;internal Guid Class;internal uint Flags;internal nint Reserved;}
    private sealed class InterfaceHandle:SafeHandleZeroOrMinusOneIsInvalid {
        internal InterfaceHandle(nint value):base(true){SetHandle(value);}
        protected override bool ReleaseHandle()=>Native.Free(handle);
    }
    private sealed class ControlFailure(int error):IOException("USB_WINUSB_CONTROL_FAILED") {internal int Error=>error;}
    internal static bool PathMatches(string path,string device,string instance)=>device.StartsWith("USB\\",StringComparison.OrdinalIgnoreCase)
        &&path.StartsWith("\\\\?\\usb#",StringComparison.OrdinalIgnoreCase)
        &&path.Contains("#"+device[4..]+"#"+instance+"#",StringComparison.OrdinalIgnoreCase);
    internal static bool TryStart(UsbAccessory.Phone selected,CancellationToken token,UsbAccessory.ProgressSink? progress){
        token.ThrowIfCancellationRequested();
        using var parameters=Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Enum\"+selected.Info.Id.Device+"\\"+selected.Info.Id.Instance+@"\Device Parameters");
        if(parameters==null)return false;
        var names=new List<string>();
        if(parameters.GetValue("DeviceInterfaceGUID") is string single)names.Add(single);
        object? multiple=parameters.GetValue("DeviceInterfaceGUIDs");
        if(multiple is string[] many)names.AddRange(many.Take(16));else if(multiple is string one)names.Add(one);
        foreach(string value in names.Distinct(StringComparer.OrdinalIgnoreCase)){
            if(!Guid.TryParse(value,out var guid))continue;
            nint devices=Native.List(ref guid,0,0,0x12);if(devices is 0 or -1)continue;
            try {for(uint i=0;i<1024;i++){
                token.ThrowIfCancellationRequested();var item=new InterfaceData{Size=(uint)Marshal.SizeOf<InterfaceData>()};
                if(!Native.Enumerate(devices,0,ref guid,i,ref item))break;
                Native.Detail(devices,ref item,0,0,out uint size,0);if(size is <8 or >65536)continue;
                nint detail=Marshal.AllocHGlobal((int)size);
                try {Marshal.WriteInt32(detail,IntPtr.Size==8?8:6);
                    if(!Native.Detail(devices,ref item,detail,size,out _,0))continue;
                    string? path=Marshal.PtrToStringUni(detail+4);if(path==null||!PathMatches(path,selected.Info.Id.Device,selected.Info.Id.Instance))continue;
                    using var file=Native.Open(path,0xc0000000,3,0,3,0x40000000,0);
                    if(file.IsInvalid){progress?.Invoke("USB_WINUSB_OPEN_FAILED",Marshal.GetLastPInvokeError());continue;}
                    if(!Native.Initialize(file.DangerousGetHandle(),out nint handle)){progress?.Invoke("USB_WINUSB_INIT_FAILED",Marshal.GetLastPInvokeError());continue;}
                    using var usb=new InterfaceHandle(handle);progress?.Invoke("USB_CONTROL_BACKEND_WINUSB");
                    byte[] version=new byte[2];Control(file,usb,0xc0,51,0,version,token,progress);
                    if(BinaryPrimitives.ReadUInt16LittleEndian(version)<1)throw new IOException("USB_AOA_UNSUPPORTED");
                    string[] identity={"CatchWave","CatchWave Host","PC audio and optional media control","1","https://github.com/BlackBadHope/CatchWave","CW-local"};
                    for(ushort index=0;index<identity.Length;index++)Control(file,usb,0x40,52,index,Encoding.UTF8.GetBytes(identity[index]+"\0"),token,progress);
                    try{Control(file,usb,0x40,53,0,Array.Empty<byte>(),token,progress);}
                    catch(ControlFailure e)when(!token.IsCancellationRequested&&e.Error is 1167 or 995){
                        // START can remove the original PDO before its status completion. The caller
                        // must still observe the accessory on this exact USB port before proceeding.
                        progress?.Invoke("USB_START_DEVICE_CHANGED",e.Error);
                    }
                    return true;
                }finally{Marshal.FreeHGlobal(detail);}
            }}finally{Native.Destroy(devices);}
        }
        return false;
    }
    private static void Control(SafeFileHandle file,InterfaceHandle usb,byte kind,byte request,ushort index,byte[] data,CancellationToken outer,UsbAccessory.ProgressSink? progress){
        outer.ThrowIfCancellationRequested();if(data.Length>256)throw new IOException("USB_CONTROL_BOUNDS");
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(outer);deadline.CancelAfter(3000);
        using var done=new EventWaitHandle(false,EventResetMode.ManualReset);nint overlap=Marshal.AllocHGlobal(32);GCHandle pinned=default;
        try {for(int i=0;i<32;i++)Marshal.WriteByte(overlap,i,0);Marshal.WriteIntPtr(overlap,24,done.SafeWaitHandle.DangerousGetHandle());
            if(data.Length>0)pinned=GCHandle.Alloc(data,GCHandleType.Pinned);
            var setup=new SetupPacket{Kind=kind,Request=request,Index=index,Length=(ushort)data.Length};
            using var cancellation=deadline.Token.Register(()=>Native.Cancel(file.DangerousGetHandle(),overlap));
            bool complete=Native.Control(usb.DangerousGetHandle(),setup,pinned.IsAllocated?pinned.AddrOfPinnedObject():0,(uint)data.Length,out uint bytes,overlap);
            int error=Marshal.GetLastPInvokeError();
            if(!complete&&error==997){
                if(deadline.IsCancellationRequested)Native.Cancel(file.DangerousGetHandle(),overlap);
                complete=Native.Result(usb.DangerousGetHandle(),overlap,out bytes,true);error=Marshal.GetLastPInvokeError();
            }
            deadline.Token.ThrowIfCancellationRequested();
            progress?.Invoke("USB_WINUSB_CONTROL",request,index,complete?1:0,bytes,complete?0:error);
            if(!complete)throw new ControlFailure(error);
            if(bytes!=data.Length)throw new IOException("USB_CONTROL_SHORT_READ");
        }finally{if(pinned.IsAllocated)pinned.Free();Marshal.FreeHGlobal(overlap);}
    }
    private static class Native {
        [DllImport("setupapi.dll",EntryPoint="SetupDiGetClassDevsW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] internal static extern nint List(ref Guid guid,nint enumerator,nint window,uint flags);
        [DllImport("setupapi.dll",EntryPoint="SetupDiEnumDeviceInterfaces",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Enumerate(nint devices,nint device,ref Guid guid,uint index,ref InterfaceData data);
        [DllImport("setupapi.dll",EntryPoint="SetupDiGetDeviceInterfaceDetailW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Detail(nint devices,ref InterfaceData data,nint detail,uint size,out uint required,nint device);
        [DllImport("setupapi.dll",EntryPoint="SetupDiDestroyDeviceInfoList"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Destroy(nint devices);
        [DllImport("kernel32.dll",EntryPoint="CreateFileW",CharSet=CharSet.Unicode,SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] internal static extern SafeFileHandle Open(string name,uint access,uint share,nint security,uint disposition,uint flags,nint template);
        [DllImport("kernel32.dll",EntryPoint="CancelIoEx",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Cancel(nint file,nint overlap);
        [DllImport("winusb.dll",EntryPoint="WinUsb_Initialize",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Initialize(nint file,out nint usb);
        [DllImport("winusb.dll",EntryPoint="WinUsb_Free"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Free(nint usb);
        [DllImport("winusb.dll",EntryPoint="WinUsb_ControlTransfer",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Control(nint usb,SetupPacket setup,nint data,uint length,out uint transferred,nint overlap);
        [DllImport("winusb.dll",EntryPoint="WinUsb_GetOverlappedResult",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Result(nint usb,nint overlap,out uint bytes,[MarshalAs(UnmanagedType.Bool)]bool wait);
    }
}
