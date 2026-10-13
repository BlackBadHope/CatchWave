// SPDX-License-Identifier: GPL-3.0-or-later
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32.SafeHandles;
namespace CatchWave.Host;

/** The accessory interface only. The adjacent ADB interface is never opened. */
internal sealed class WinUsbBulkStream:Stream {
    internal static readonly Guid InterfaceGuid=new("805936D7-7EEC-474A-970C-181FE29D96CF");
    [StructLayout(LayoutKind.Sequential)] private struct InterfaceData {internal uint Size;internal Guid Class;internal uint Flags;internal nint Reserved;}
    [StructLayout(LayoutKind.Sequential)] private struct DeviceData {internal uint Size;internal Guid Class;internal uint Node;internal nint Reserved;}
    [StructLayout(LayoutKind.Sequential,Pack=1)] internal struct InterfaceDescriptor {internal byte Length,Type,Number,Alternate,Endpoints,Class,Subclass,Protocol,String;}
    [StructLayout(LayoutKind.Sequential)] internal struct PipeInfo {internal int Type;internal byte Id;internal ushort MaximumPacketSize;internal byte Interval;}
    private sealed class UsbHandle(nint value):SafeHandleZeroOrMinusOneIsInvalid(true) {
        internal void Initialize()=>SetHandle(value);
        protected override bool ReleaseHandle()=>Native.Free(handle);
    }
    private readonly SafeFileHandle file;private readonly UsbHandle usb;private readonly byte input,output;
    private readonly UsbAccessory.ProgressSink? progress;
    private readonly CancellationTokenSource stop=new();private readonly SemaphoreSlim reader=new(1),writer=new(1);
    private readonly byte[] incoming=new byte[65536];private int position,available,closed;
    private WinUsbBulkStream(SafeFileHandle file,UsbHandle usb,byte input,byte output,UsbAccessory.ProgressSink? progress){this.file=file;this.usb=usb;this.input=input;this.output=output;this.progress=progress;}
    internal static bool MatchesIdentity(string node,string? parent,UsbAccessory.Phone selected)=>selected.Accessory&&
        (selected.Info.Descriptor.Product==0x2d00?node.Equals(selected.Info.Id.Device+"\\"+selected.Info.Id.Instance,StringComparison.OrdinalIgnoreCase):
        node.StartsWith(@"USB\VID_18D1&PID_2D01&MI_00\",StringComparison.OrdinalIgnoreCase)&&
        (parent??"").Equals(selected.Info.Id.Device+"\\"+selected.Info.Id.Instance,StringComparison.OrdinalIgnoreCase));
    internal static string ResolveInstance(UsbAccessory.Phone selected){
        string root=selected.Info.Id.Device+"\\"+selected.Info.Id.Instance;
        if(!selected.Accessory)throw new IOException("USB_ACCESSORY_REQUIRED");if(selected.Info.Descriptor.Product==0x2d00)return root;
        if(Native.Locate(out uint node,root,0)!=0||Native.Child(out uint child,node,0)!=0)throw new IOException("USB_ACCESSORY_INTERFACE_MISSING");
        for(int i=0;i<16;i++){
            var id=new StringBuilder(512);if(Native.Id(child,id,512,0)==0&&MatchesIdentity(id.ToString(),root,selected))return id.ToString();
            if(Native.Sibling(out uint next,child,0)!=0)break;child=next;
        }throw new IOException("USB_ACCESSORY_INTERFACE_MISSING");
    }
    internal static Stream? TryOpen(UsbAccessory.Phone selected,CancellationToken token,UsbAccessory.ProgressSink? progress){
        token.ThrowIfCancellationRequested();Guid guid=InterfaceGuid;nint devices=Native.List(ref guid,0,0,0x12);if(devices is 0 or -1)return null;
        try {for(uint i=0;i<1024;i++){
            token.ThrowIfCancellationRequested();var item=new InterfaceData{Size=(uint)Marshal.SizeOf<InterfaceData>()};if(!Native.Enumerate(devices,0,ref guid,i,ref item))break;
            Native.Detail(devices,ref item,0,0,out uint size,0);if(size is <8 or >65536)continue;
            nint detail=Marshal.AllocHGlobal((int)size),deviceData=Marshal.AllocHGlobal(Marshal.SizeOf<DeviceData>());
            try {
                Marshal.WriteInt32(detail,8);Marshal.StructureToPtr(new DeviceData{Size=(uint)Marshal.SizeOf<DeviceData>()},deviceData,false);
                if(!Native.Detail(devices,ref item,detail,size,out _,deviceData))continue;
                var data=Marshal.PtrToStructure<DeviceData>(deviceData);var node=new StringBuilder(512);if(Native.Id(data.Node,node,512,0)!=0)continue;
                string? parent=null;if(Native.Parent(out uint parentNode,data.Node,0)==0){var id=new StringBuilder(512);if(Native.Id(parentNode,id,512,0)==0)parent=id.ToString();}
                if(!MatchesIdentity(node.ToString(),parent,selected))continue;
                string? path=Marshal.PtrToStringUni(detail+4);if(path==null)continue;
                var file=Native.Open(path,0xc0000000,3,0,3,0x40000000,0);if(file.IsInvalid){file.Dispose();throw new IOException("USB_WINUSB_DATA_OPEN_FAILED");}
                UsbHandle? usb=null;
                try {
                    if(!Native.Initialize(file.DangerousGetHandle(),out nint raw))throw new IOException("USB_WINUSB_DATA_INIT_FAILED");usb=new UsbHandle(raw);usb.Initialize();
                    if(!Native.Interface(raw,0,out var descriptor)||descriptor.Number!=0||descriptor.Alternate!=0)throw new IOException("USB_ACCESSORY_INTERFACE_INVALID");
                    byte input=0,output=0;
                    for(byte pipe=0;pipe<descriptor.Endpoints;pipe++)if(Native.Pipe(raw,0,pipe,out var info)&&info.Type==2){if((info.Id&0x80)!=0)input=info.Id;else output=info.Id;}
                    if(input==0||output==0)throw new IOException("USB_ACCESSORY_PIPES_MISSING");
                    token.ThrowIfCancellationRequested();progress?.Invoke("USB_DATA_BACKEND_WINUSB");progress?.Invoke("USB_ACCESSORY_OPENED");
                    return new WinUsbBulkStream(file,usb,input,output,progress);
                }catch{usb?.Dispose();file.Dispose();throw;}
            }finally{Marshal.FreeHGlobal(deviceData);Marshal.FreeHGlobal(detail);}
        }}finally{Native.Destroy(devices);}return null;
    }
    private int Transfer(byte[] buffer,int offset,int length,bool read,CancellationToken token){
        token.ThrowIfCancellationRequested();bool fileLease=false,usbLease=false;
        using var done=new EventWaitHandle(false,EventResetMode.ManualReset);nint overlap=Marshal.AllocHGlobal(32);GCHandle pinned=default;
        try {
            file.DangerousAddRef(ref fileLease);usb.DangerousAddRef(ref usbLease);
            for(int i=0;i<32;i++)Marshal.WriteByte(overlap,i,0);Marshal.WriteIntPtr(overlap,24,done.SafeWaitHandle.DangerousGetHandle());pinned=GCHandle.Alloc(buffer,GCHandleType.Pinned);
            using var cancel=token.Register(()=>Native.Cancel(file.DangerousGetHandle(),overlap));
            bool complete=read?Native.Read(usb.DangerousGetHandle(),input,pinned.AddrOfPinnedObject()+offset,(uint)length,out uint bytes,overlap):Native.Write(usb.DangerousGetHandle(),output,pinned.AddrOfPinnedObject()+offset,(uint)length,out bytes,overlap);
            int error=Marshal.GetLastPInvokeError();
            if(!complete&&error==997){if(token.IsCancellationRequested)Native.Cancel(file.DangerousGetHandle(),overlap);complete=Native.Result(usb.DangerousGetHandle(),overlap,out bytes,true);error=Marshal.GetLastPInvokeError();}
            token.ThrowIfCancellationRequested();if(!complete){progress?.Invoke("USB_WINUSB_DATA_IO_FAILED",read?1:0,error);throw new IOException("USB_WINUSB_TRANSFER_FAILED");}if(bytes>(uint)length)throw new IOException("USB_TRANSFER_BOUNDS");return checked((int)bytes);
        }finally{if(pinned.IsAllocated)pinned.Free();Marshal.FreeHGlobal(overlap);if(usbLease)usb.DangerousRelease();if(fileLease)file.DangerousRelease();}
    }
    private int ReadBuffered(Span<byte> buffer,CancellationToken token){if(available==0){available=Transfer(incoming,0,incoming.Length,true,token);position=0;}int n=Math.Min(buffer.Length,available);incoming.AsSpan(position,n).CopyTo(buffer);CryptographicOperations.ZeroMemory(incoming.AsSpan(position,n));position+=n;available-=n;return n;}
    public override int Read(byte[] buffer,int offset,int count){ValidateBufferArguments(buffer,offset,count);if(count==0)return 0;reader.Wait(stop.Token);try{return ReadBuffered(buffer.AsSpan(offset,count),stop.Token);}finally{reader.Release();}}
    public override void Write(byte[] buffer,int offset,int count){ValidateBufferArguments(buffer,offset,count);if(count==0)return;writer.Wait(stop.Token);try{if(Transfer(buffer,offset,count,false,stop.Token)!=count)throw new IOException("USB_SHORT_WRITE");}finally{writer.Release();}}
    public override async ValueTask<int> ReadAsync(Memory<byte> buffer,CancellationToken token=default){using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,stop.Token);linked.Token.ThrowIfCancellationRequested();if(buffer.IsEmpty)return 0;await reader.WaitAsync(linked.Token);try{return await Task.Run(()=>ReadBuffered(buffer.Span,linked.Token));}finally{reader.Release();}}
    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer,CancellationToken token=default){using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,stop.Token);linked.Token.ThrowIfCancellationRequested();if(buffer.IsEmpty)return;await writer.WaitAsync(linked.Token);byte[] bytes=buffer.ToArray();try{if(await Task.Run(()=>Transfer(bytes,0,bytes.Length,false,linked.Token))!=bytes.Length)throw new IOException("USB_SHORT_WRITE");}finally{CryptographicOperations.ZeroMemory(bytes);writer.Release();}}
    protected override void Dispose(bool disposing){if(disposing&&Interlocked.Exchange(ref closed,1)==0){stop.Cancel();usb.Dispose();file.Dispose();}base.Dispose(disposing);}
    public override bool CanRead=>closed==0;public override bool CanWrite=>closed==0;public override bool CanSeek=>false;
    public override long Length=>throw new NotSupportedException();public override long Position{get=>throw new NotSupportedException();set=>throw new NotSupportedException();}
    public override void Flush(){}public override long Seek(long offset,SeekOrigin origin)=>throw new NotSupportedException();public override void SetLength(long value)=>throw new NotSupportedException();
    private static class Native {
        [DllImport("setupapi.dll",EntryPoint="SetupDiGetClassDevsW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern nint List(ref Guid guid,nint enumerator,nint window,uint flags);
        [DllImport("setupapi.dll",EntryPoint="SetupDiEnumDeviceInterfaces",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Enumerate(nint list,nint device,ref Guid guid,uint index,ref InterfaceData item);
        [DllImport("setupapi.dll",EntryPoint="SetupDiGetDeviceInterfaceDetailW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Detail(nint list,ref InterfaceData item,nint detail,uint length,out uint needed,nint device);
        [DllImport("setupapi.dll",EntryPoint="SetupDiDestroyDeviceInfoList"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Destroy(nint list);
        [DllImport("cfgmgr32.dll",EntryPoint="CM_Get_Parent"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern uint Parent(out uint parent,uint node,uint flags);
        [DllImport("cfgmgr32.dll",EntryPoint="CM_Locate_DevNodeW",CharSet=CharSet.Unicode),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern uint Locate(out uint node,string id,uint flags);
        [DllImport("cfgmgr32.dll",EntryPoint="CM_Get_Child"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern uint Child(out uint child,uint node,uint flags);
        [DllImport("cfgmgr32.dll",EntryPoint="CM_Get_Sibling"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern uint Sibling(out uint sibling,uint node,uint flags);
        [DllImport("cfgmgr32.dll",EntryPoint="CM_Get_Device_IDW",CharSet=CharSet.Unicode),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern uint Id(uint node,StringBuilder id,uint length,uint flags);
        [DllImport("kernel32.dll",EntryPoint="CreateFileW",CharSet=CharSet.Unicode,SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern SafeFileHandle Open(string name,uint access,uint share,nint security,uint disposition,uint flags,nint template);
        [DllImport("kernel32.dll",EntryPoint="CancelIoEx",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Cancel(nint file,nint overlap);
        [DllImport("winusb.dll",EntryPoint="WinUsb_Initialize",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Initialize(nint file,out nint usb);
        [DllImport("winusb.dll",EntryPoint="WinUsb_Free"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Free(nint usb);
        [DllImport("winusb.dll",EntryPoint="WinUsb_QueryInterfaceSettings",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Interface(nint usb,byte alternate,out InterfaceDescriptor descriptor);
        [DllImport("winusb.dll",EntryPoint="WinUsb_QueryPipe",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Pipe(nint usb,byte alternate,byte index,out PipeInfo pipe);
        [DllImport("winusb.dll",EntryPoint="WinUsb_ReadPipe",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Read(nint usb,byte pipe,nint buffer,uint length,out uint bytes,nint overlap);
        [DllImport("winusb.dll",EntryPoint="WinUsb_WritePipe",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Write(nint usb,byte pipe,nint buffer,uint length,out uint bytes,nint overlap);
        [DllImport("winusb.dll",EntryPoint="WinUsb_GetOverlappedResult",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Result(nint usb,nint overlap,out uint bytes,bool wait);
    }
}
