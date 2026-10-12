// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Runtime.InteropServices;
using System.Text;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;
namespace CatchWave.Host;

/** AOA data path. No adb process, developer setting, tethering or device-driver replacement. */
internal static class UsbAccessory {
    internal delegate void ProgressSink(string code,params long[] values);
    internal sealed record Phone(DeviceInfo Info) {
        internal bool Accessory=>Info.Descriptor.Vendor==0x18d1&&Info.Descriptor.Product is 0x2d00 or 0x2d01;
        public override string ToString()=>Accessory?"Android · USB":"Android · USB ("+Info.Descriptor.Vendor.ToString("X4")+")";
    }
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
    internal struct DeviceId {
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=200)] internal string Device;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=200)] internal string Instance;
    }
    [StructLayout(LayoutKind.Sequential,Pack=1)]
    internal struct DeviceDescriptor {
        internal byte Length,Type;internal ushort UsbVersion;
        internal byte Class,Subclass,Protocol,PacketSize;
        internal ushort Vendor,Product,DeviceVersion;
        internal byte Manufacturer,Model,Serial,Configurations;
    }
    [StructLayout(LayoutKind.Sequential)]
    internal struct DeviceInfo {internal DeviceId Id;internal ulong Filter,Port,Speed;internal DeviceDescriptor Descriptor;}
    [StructLayout(LayoutKind.Sequential)]
    private struct ConfigRequest {internal DeviceId Id;internal ulong Index;}
    [StructLayout(LayoutKind.Sequential)]
    private struct Transfer {
        internal ulong Endpoint,Buffer,Length,Type,Packets,PacketArray;
        internal ulong Bytes,Status,IsoResults;
    }
    private const string Dll="UsbDkHelper.dll";
    private static readonly string Helper=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"UsbDk Runtime Library",Dll);
    private static readonly object loadGate=new();private static bool loaded;
    internal static bool Available=>Environment.Is64BitProcess&&File.Exists(Helper);
    private static void Load(){lock(loadGate){if(loaded)return;if(!Available)throw new IOException("USB_DRIVER_MISSING");
        using var file=File.OpenRead(Helper);
        if(Convert.ToHexString(SHA256.HashData(file))!="66BBFE6F99B417F97C7A9E97816E24AF7DDE0E01B8E535C41E91E1C6C19CFEDA")throw new IOException("USB_DRIVER_VERSION_UNSUPPORTED");
        nint library=NativeLibrary.Load(Helper);NativeLibrary.SetDllImportResolver(typeof(UsbAccessory).Assembly,(name,_,_)=>name==Dll?library:0);loaded=true;}}
    internal static IReadOnlyList<Phone> Phones(){
        if(!Available)return Array.Empty<Phone>();Load();
        if(!Native.List(out nint array,out uint count))throw new IOException(DriverError(ControlOpenError()));
        try {if(count>1024)throw new IOException("USB_LIST_INVALID");var phones=new List<Phone>();int size=Marshal.SizeOf<DeviceInfo>();
            for(int i=0;i<count;i++){var info=Marshal.PtrToStructure<DeviceInfo>(array+i*size);
                // Enumeration is metadata only. A vendor request is sent solely after explicit selection.
                if(info.Descriptor.Vendor is 0x18d1 or 0x22d9 or 0x2a70 or 0x04e8 or 0x2717 or 0x12d1 or 0x0bb4 or 0x2a45 or 0x1004 or 0x2d95)
                    if(info.Descriptor.Class is 0 or 239)phones.Add(new Phone(info));
            }return phones;
        }finally{Native.ReleaseList(array);}
    }
    internal static string DriverError(int code)=>code switch{2=>"USB_DRIVER_RESTART_REQUIRED",5=>"USB_DRIVER_ACCESS_DENIED",32=>"USB_DRIVER_BUSY",_=>"USB_ENUMERATION_FAILED"};
    private static int ControlOpenError(){using var file=Native.OpenControl("\\\\.\\UsbDk",0xc0000000,0,0,3,0,0);return file.IsInvalid?Marshal.GetLastPInvokeError():0;}
    private sealed class Device:SafeHandleZeroOrMinusOneIsInvalid {
        internal readonly ProgressSink? Progress;
        internal Device(DeviceId id,ProgressSink? progress=null):base(true){Progress=progress;SetHandle(Native.Redirect(ref id));int error=Marshal.GetLastPInvokeError();if(IsInvalid){progress?.Invoke("USB_OPEN_FAILED",error);throw new IOException(error==1167?"USB_DEVICE_NOT_READY":"USB_OPEN_FAILED");}}
        protected override bool ReleaseHandle()=>Native.Stop(handle);
    }
    internal static async Task<Stream> Open(Phone selected,CancellationToken stop,ProgressSink? progress=null){
        stop.ThrowIfCancellationRequested();Load();
        // Reject stale selections; never substitute a different phone or try every attached USB device.
        var phone=Phones().SingleOrDefault(p=>p.Info.Id.Device==selected.Info.Id.Device&&p.Info.Id.Instance==selected.Info.Id.Instance)
            ??throw new IOException("USB_PHONE_REMOVED");
        if(!phone.Accessory){
            // StopRedirect resets the selected USB port. Keep the negotiation handle until the
            // old device has disappeared and the accessory identity is actually enumerated.
            progress?.Invoke("USB_SELECTED_PHONE",phone.Info.Descriptor.Vendor,phone.Info.Descriptor.Product,unchecked((long)phone.Info.Port));
            using var device=new Device(phone.Info.Id,progress);progress?.Invoke("USB_NEGOTIATION_OPEN");
                byte[] protocol=await Task.Run(()=>Control(device,0xc0,51,0,2,null,stop),stop);
                if(protocol.Length!=2||BinaryPrimitives.ReadUInt16LittleEndian(protocol)<1)throw new IOException("USB_AOA_UNSUPPORTED");
                string[] identity={"CatchWave","CatchWave Host","PC audio and optional media control","1","https://github.com/BlackBadHope/CatchWave","CW-local"};
                for(ushort i=0;i<identity.Length;i++){byte[] text=Encoding.UTF8.GetBytes(identity[i]+"\0");await Task.Run(()=>Control(device,0x40,52,i,0,text,stop),stop);}
                await Task.Run(()=>Control(device,0x40,53,0,0,null,stop),stop);progress?.Invoke("USB_AOA_START_SENT");
            // Protocol re-enumeration, bounded to eight seconds; topology must remain the selected port.
            var deadline=DateTime.UtcNow.AddSeconds(8);Phone? next=null;
            while(DateTime.UtcNow<deadline){stop.ThrowIfCancellationRequested();next=Phones().SingleOrDefault(p=>p.Accessory&&p.Info.Filter==phone.Info.Filter&&p.Info.Port==phone.Info.Port);
                if(next!=null)break;await Task.Delay(150,stop);
            }
            phone=next??throw new IOException("USB_AOA_REENUMERATION_TIMEOUT");
            progress?.Invoke("USB_ACCESSORY_ENUMERATED");
        }
        var request=new ConfigRequest{Id=phone.Info.Id};
        if(!Native.Config(ref request,out nint descriptor,out uint length))throw new IOException("USB_CONFIG_FAILED");
        (byte input,byte output) endpoints;
        try {if(length>65535||length<9)throw new IOException("USB_CONFIG_INVALID");byte[] bytes=new byte[length];Marshal.Copy(descriptor,bytes,0,bytes.Length);endpoints=Endpoints(bytes);}
        finally{Native.ReleaseConfig(descriptor);}
        stop.ThrowIfCancellationRequested();var opened=new Device(phone.Info.Id,progress);
        progress?.Invoke("USB_ACCESSORY_OPENED");
        if(stop.IsCancellationRequested){opened.Dispose();stop.ThrowIfCancellationRequested();}
        return new BulkStream(opened,endpoints.input,endpoints.output);
    }
    internal static (byte input,byte output) Endpoints(byte[] descriptor){
        if(descriptor.Length<9||descriptor[0]!=9||descriptor[1]!=2||BinaryPrimitives.ReadUInt16LittleEndian(descriptor.AsSpan(2))!=descriptor.Length)throw new IOException("USB_CONFIG_INVALID");
        byte input=0,output=0;bool accessory=false;
        for(int i=0;i<descriptor.Length;){int size=descriptor[i];if(size<2||i+size>descriptor.Length)throw new IOException("USB_DESCRIPTOR_INVALID");
            byte type=descriptor[i+1];if(type==4){if(size<9)throw new IOException("USB_INTERFACE_INVALID");accessory=descriptor[i+2]==0&&descriptor[i+3]==0;}
            if(accessory&&type==5){if(size<7)throw new IOException("USB_ENDPOINT_INVALID");if((descriptor[i+3]&3)==2){byte address=descriptor[i+2];if((address&0x80)!=0)input=address;else output=address;}}
            i+=size;
        }
        if(input==0||output==0)throw new IOException("USB_ACCESSORY_PIPES_MISSING");return(input,output);
    }
    private static byte[] Control(Device device,byte kind,byte request,ushort index,ushort readLength,byte[]? payload,CancellationToken outer){
        int length=payload?.Length??readLength;byte[] buffer=new byte[8+length];buffer[0]=kind;buffer[1]=request;
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(4),index);BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(6),(ushort)length);
        payload?.CopyTo(buffer,8);using var deadline=CancellationTokenSource.CreateLinkedTokenSource(outer);deadline.CancelAfter(3000);
        int transferred=Io(device,0,0,buffer,0,buffer.Length,(kind&0x80)!=0,deadline.Token);
        if((kind&0x80)==0)return Array.Empty<byte>();if(transferred!=readLength)throw new IOException("USB_CONTROL_SHORT_READ");return buffer.AsSpan(8,readLength).ToArray();
    }
    private static int Io(Device device,byte endpoint,ulong type,byte[] bytes,int offset,int length,bool read,CancellationToken stop){
        stop.ThrowIfCancellationRequested();bool lease=false;device.DangerousAddRef(ref lease);
        using var done=new EventWaitHandle(false,EventResetMode.ManualReset);nint overlap=Marshal.AllocHGlobal(32),request=Marshal.AllocHGlobal(Marshal.SizeOf<Transfer>());
        GCHandle pinned=default;
        try {pinned=GCHandle.Alloc(bytes,GCHandleType.Pinned);for(int i=0;i<32;i++)Marshal.WriteByte(overlap,i,0);Marshal.WriteIntPtr(overlap,24,done.SafeWaitHandle.DangerousGetHandle());
            var transfer=new Transfer{Endpoint=endpoint,Buffer=(ulong)(pinned.AddrOfPinnedObject()+offset),Length=(ulong)length,Type=type};Marshal.StructureToPtr(transfer,request,false);
            nint system=Native.SystemHandle(device.DangerousGetHandle());
            using var cancellation=stop.Register(()=>Native.Cancel(system,overlap));
            int started=read?Native.Read(device.DangerousGetHandle(),request,overlap):Native.Write(device.DangerousGetHandle(),request,overlap);
            int startError=Marshal.GetLastPInvokeError();
            if(type==0)device.Progress?.Invoke("USB_CONTROL_SUBMITTED",started,startError);
            if(started==0){device.Progress?.Invoke("USB_IO_START_FAILED",startError);throw new IOException("USB_TRANSFER_FAILED");}
            // Cancellation before submission must also cancel the newly submitted request.
            if(stop.IsCancellationRequested)Native.Cancel(system,overlap);
            bool complete=started==1||Native.Result(system,overlap,out _,true);stop.ThrowIfCancellationRequested();
            int completionError=Marshal.GetLastPInvokeError();
            transfer=Marshal.PtrToStructure<Transfer>(request);
            if(type==0)device.Progress?.Invoke("USB_CONTROL_COMPLETED",complete?1:0,unchecked((uint)transfer.Status),unchecked((long)transfer.Bytes),completionError);
            string? failure=TransferError(complete,transfer.Status,transfer.Bytes,length);
            if(failure!=null){device.Progress?.Invoke(failure,unchecked((uint)transfer.Status),completionError);throw new IOException(failure);}
            return checked((int)transfer.Bytes);
        }finally{if(pinned.IsAllocated)pinned.Free();Marshal.FreeHGlobal(request);Marshal.FreeHGlobal(overlap);if(lease)device.DangerousRelease();}
    }
    internal static string? TransferError(bool complete,ulong status,ulong actual,int length)=>!complete?"USB_TRANSFER_FAILED":unchecked((uint)status)==0x80000300?"USB_NATIVE_INVALID_PARAMETER":status!=0?"USB_TRANSFER_FAILED":actual>(ulong)length?"USB_TRANSFER_BOUNDS":null;
    private sealed class BulkStream(Device device,byte input,byte output):Stream {
        private readonly CancellationTokenSource stop=new();private int closed;
        private readonly byte[] incoming=new byte[65536];private int position,available;private readonly SemaphoreSlim reader=new(1);
        public override bool CanRead=>closed==0;public override bool CanWrite=>closed==0;public override bool CanSeek=>false;
        public override long Length=>throw new NotSupportedException();public override long Position{get=>throw new NotSupportedException();set=>throw new NotSupportedException();}
        public override void Flush(){}public override long Seek(long o,SeekOrigin s)=>throw new NotSupportedException();public override void SetLength(long n)=>throw new NotSupportedException();
        private int ReadBuffered(Span<byte> target,CancellationToken token){
            if(available==0){available=Io(device,input,1,incoming,0,incoming.Length,true,token);position=0;}
            int n=Math.Min(available,target.Length);incoming.AsSpan(position,n).CopyTo(target);CryptographicOperations.ZeroMemory(incoming.AsSpan(position,n));position+=n;available-=n;return n;
        }
        public override int Read(byte[] b,int o,int n){ValidateBufferArguments(b,o,n);if(n==0)return 0;reader.Wait(stop.Token);try{return ReadBuffered(b.AsSpan(o,n),stop.Token);}finally{reader.Release();}}
        public override void Write(byte[] b,int o,int n){ValidateBufferArguments(b,o,n);if(n>0&&Io(device,output,1,b,o,n,false,stop.Token)!=n)throw new IOException("USB_SHORT_WRITE");}
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer,CancellationToken token=default){using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,stop.Token);linked.Token.ThrowIfCancellationRequested();if(buffer.IsEmpty)return 0;await reader.WaitAsync(linked.Token);try{return await Task.Run(()=>ReadBuffered(buffer.Span,linked.Token));}finally{reader.Release();}}
        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer,CancellationToken token=default){using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,stop.Token);linked.Token.ThrowIfCancellationRequested();if(buffer.IsEmpty)return;byte[] bytes=buffer.ToArray();try{int n=await Task.Run(()=>Io(device,output,1,bytes,0,bytes.Length,false,linked.Token));if(n!=bytes.Length)throw new IOException("USB_SHORT_WRITE");}finally{CryptographicOperations.ZeroMemory(bytes);}}
        protected override void Dispose(bool disposing){if(disposing&&Interlocked.Exchange(ref closed,1)==0){stop.Cancel();device.Dispose();}base.Dispose(disposing);}
    }
    private static class Native {
        [DllImport("kernel32.dll",EntryPoint="CreateFileW",CharSet=CharSet.Unicode,SetLastError=true)] internal static extern SafeFileHandle OpenControl(string name,uint access,uint share,nint security,uint disposition,uint flags,nint template);
        [DllImport(Dll,EntryPoint="UsbDk_GetDevicesList")] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool List(out nint list,out uint count);
        [DllImport(Dll,EntryPoint="UsbDk_ReleaseDevicesList")] internal static extern void ReleaseList(nint list);
        [DllImport(Dll,EntryPoint="UsbDk_StartRedirect",SetLastError=true)] internal static extern nint Redirect(ref DeviceId id);
        [DllImport(Dll,EntryPoint="UsbDk_StopRedirect")] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Stop(nint device);
        [DllImport(Dll,EntryPoint="UsbDk_GetConfigurationDescriptor")] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Config(ref ConfigRequest request,out nint bytes,out uint length);
        [DllImport(Dll,EntryPoint="UsbDk_ReleaseConfigurationDescriptor")] internal static extern void ReleaseConfig(nint bytes);
        [DllImport(Dll,EntryPoint="UsbDk_ReadPipe",SetLastError=true)] internal static extern int Read(nint device,nint request,nint overlap);
        [DllImport(Dll,EntryPoint="UsbDk_WritePipe",SetLastError=true)] internal static extern int Write(nint device,nint request,nint overlap);
        [DllImport(Dll,EntryPoint="UsbDk_GetRedirectorSystemHandle")] internal static extern nint SystemHandle(nint device);
        [DllImport("kernel32.dll",EntryPoint="CancelIoEx",SetLastError=true)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Cancel(nint device,nint overlap);
        [DllImport("kernel32.dll",EntryPoint="GetOverlappedResult",SetLastError=true)] [return:MarshalAs(UnmanagedType.Bool)] internal static extern bool Result(nint device,nint overlap,out uint bytes,[MarshalAs(UnmanagedType.Bool)]bool wait);
    }
}
