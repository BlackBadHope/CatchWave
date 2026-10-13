// SPDX-License-Identifier: GPL-3.0-or-later
// Original interop declarations from Windows SDK public interfaces; no third-party implementation.
using System.Runtime.InteropServices;
namespace CatchWave.Host;

internal interface IPcmSource:IDisposable {PcmFormat Format{get;} byte[] Frame();}
internal sealed class SilenceSource:IPcmSource {
    public PcmFormat Format=>new(48000,2);public byte[] Frame()=>new byte[Format.Bytes];public void Dispose(){}
}
internal sealed class WasapiSource:IPcmSource {
    private readonly IAudioClient client;private readonly IAudioCaptureClient capture;
    private readonly object enumeratorObject;private readonly IMMDevice device;
    private readonly PcmFrameBuffer queued=new(3840*8);private readonly int blockAlign=4;
    public PcmFormat Format{get;}
    private readonly DiagnosticLog? log;private readonly System.Diagnostics.Stopwatch clock=System.Diagnostics.Stopwatch.StartNew();private long reportAt=-10000,packets,silentPackets;private readonly string selectedId;private readonly bool followDefault;
    internal string SelectedId=>selectedId;
    public WasapiSource(DiagnosticLog? log=null,string? endpointId=null) {
        this.log=log;
        followDefault=endpointId==null;
        object? partialEnumerator=null,partialClient=null,partialCapture=null;IMMDevice? partialDevice=null;
        try {
        enumeratorObject=AudioOutputs.Enumerator();
        partialEnumerator=enumeratorObject;
        var enumerator=(IMMDeviceEnumerator)enumeratorObject;
        Check(endpointId==null?enumerator.GetDefaultAudioEndpoint(0,1,out device):enumerator.GetDevice(endpointId,out device));partialDevice=device;
        Check(device.GetState(out uint state));if(state!=1)throw new IOException("Selected audio output is unavailable");
        Check(device.GetId(out selectedId));Guid iid=typeof(IAudioClient).GUID;
        Check(device.Activate(ref iid,23,IntPtr.Zero,out object value));partialClient=value;client=(IAudioClient)value;
        Check(client.GetMixFormat(out IntPtr mix));
        try {
            int nativeRate=Marshal.ReadInt32(mix,4),nativeChannels=(ushort)Marshal.ReadInt16(mix,2),nativeBits=(ushort)Marshal.ReadInt16(mix,14);
            log?.Event("CAPTURE","NATIVE_MIX_FORMAT",nativeRate,nativeChannels,nativeBits);
            // Windows' shared audio engine performs the channel matrix/sample-rate conversion.
            // The Android stream is always PCM16 stereo 48 kHz, without changing device settings.
            Format=new(48000,2);
            byte[] requested=new byte[18];
            BitConverter.GetBytes((ushort)1).CopyTo(requested,0);BitConverter.GetBytes((ushort)2).CopyTo(requested,2);
            BitConverter.GetBytes(48000).CopyTo(requested,4);BitConverter.GetBytes(192000).CopyTo(requested,8);
            BitConverter.GetBytes((ushort)4).CopyTo(requested,12);BitConverter.GetBytes((ushort)16).CopyTo(requested,14);
            IntPtr format=Marshal.AllocCoTaskMem(requested.Length);
            try{Marshal.Copy(requested,0,format,requested.Length);int hr=client.Initialize(0,0x88020000,1_000_000,0,format,IntPtr.Zero);
                if(hr<0)log?.Event("CAPTURE","AUTO_FORMAT_REJECTED",hr,nativeRate,nativeChannels,nativeBits);Check(hr);
                log?.Event("CAPTURE","AUTO_FORMAT_READY",48000,2,16);
            }finally{Marshal.FreeCoTaskMem(format);}
        }finally{Marshal.FreeCoTaskMem(mix);}
        Guid captureId=typeof(IAudioCaptureClient).GUID;Check(client.GetService(ref captureId,out object service));partialCapture=service;capture=(IAudioCaptureClient)service;Check(client.Start());
        log?.Event("ROUTE",endpointId==null?"DEFAULT_CAPTURE_OPENED":"SELECTED_CAPTURE_OPENED");
        }catch{
            if(partialCapture!=null)Marshal.ReleaseComObject(partialCapture);if(partialClient!=null)Marshal.ReleaseComObject(partialClient);
            if(partialDevice!=null)Marshal.ReleaseComObject(partialDevice);if(partialEnumerator!=null)Marshal.ReleaseComObject(partialEnumerator);throw;
        }
    }
    public byte[] Frame() {
        Check(capture.GetNextPacketSize(out uint available));
        while(available>0){
            Check(capture.GetBuffer(out IntPtr ptr,out uint frames,out uint flags,out _,out _));packets++;if((flags&2)!=0)silentPackets++;
            try {
                if((flags&1)!=0)queued.Clear(); // Discontinuity: drop old samples.
                int count=checked((int)frames*blockAlign);byte[] native=new byte[count];
                if((flags&2)==0)Marshal.Copy(ptr,native,0,count); // Silent buffers have no readable pointer.
                queued.Write(native); // Windows already supplied PCM16; copy chunks, not individual bytes.
            }finally{Check(capture.ReleaseBuffer(frames));}
            Check(capture.GetNextPacketSize(out available));
        }
        byte[] frame=new byte[Format.Bytes];int n=queued.Read(frame);
        if(clock.ElapsedMilliseconds-reportAt>=2000){reportAt=clock.ElapsedMilliseconds;log?.Event("CAPTURE",n==0?"NO_CAPTURE_PACKET":frame.AsSpan().IndexOfAnyExcept((byte)0)<0?"SILENT_CAPTURE":"PCM_CAPTURE",packets,silentPackets,n,queued.DroppedBytes);
            if(followDefault){IMMDevice? current=null;try{Check(((IMMDeviceEnumerator)enumeratorObject).GetDefaultAudioEndpoint(0,1,out current));Check(current.GetId(out string id));if(id!=selectedId)log?.Event("ROUTE","DEFAULT_CHANGED");}catch(System.Runtime.InteropServices.COMException){log?.Event("ROUTE","DEFAULT_UNAVAILABLE");}finally{if(current!=null)Marshal.ReleaseComObject(current);}}}
        return frame;
    }
    internal static byte[] Convert(byte[] native,int bits,bool floating) {
        int stride=bits/8;if(stride is <2 or >4||native.Length%stride!=0||floating&&bits!=32)throw new IOException("Invalid samples");
        byte[] output=new byte[native.Length/stride*2];
        for(int i=0,j=0;i<native.Length;i+=stride,j+=2){
            short sample;
            if(floating){float x=BitConverter.ToSingle(native,i);sample=!float.IsFinite(x)?(short)0:(short)Math.Clamp((int)(Math.Clamp(x,-1f,1f)*32768f),-32768,32767);}
            else sample=bits==16?BitConverter.ToInt16(native,i):bits==24?(short)((native[i+1]|native[i+2]<<8)):(short)(BitConverter.ToInt32(native,i)>>16);
            output[j]=(byte)sample;output[j+1]=(byte)(sample>>8);
        }return output;
    }
    public void Dispose(){client.Stop();Marshal.ReleaseComObject(capture);Marshal.ReleaseComObject(client);Marshal.ReleaseComObject(device);Marshal.ReleaseComObject(enumeratorObject);}
    private static void Check(int hr){Marshal.ThrowExceptionForHR(hr);}
}
[ComImport,Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDeviceEnumerator {
    [PreserveSig]int EnumAudioEndpoints(int flow,uint mask,out IMMDeviceCollection devices);
    [PreserveSig]int GetDefaultAudioEndpoint(int flow,int role,out IMMDevice device);
    [PreserveSig]int GetDevice([MarshalAs(UnmanagedType.LPWStr)]string id,out IMMDevice device);
}
[ComImport,Guid("D666063F-1587-4E43-81F1-B948E807363F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDevice {
    [PreserveSig]int Activate(ref Guid id,uint context,IntPtr parameters,[MarshalAs(UnmanagedType.IUnknown)]out object value);
    [PreserveSig]int OpenPropertyStore(uint access,out IAudioPropertyStore properties);
    [PreserveSig]int GetId([MarshalAs(UnmanagedType.LPWStr)]out string id);
    [PreserveSig]int GetState(out uint state);
}
[ComImport,Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioClient {
    [PreserveSig]int Initialize(int share,uint flags,long duration,long periodicity,IntPtr format,IntPtr session);
    [PreserveSig]int GetBufferSize(out uint frames);[PreserveSig]int GetStreamLatency(out long latency);
    [PreserveSig]int GetCurrentPadding(out uint padding);
    [PreserveSig]int IsFormatSupported(int share,IntPtr format,out IntPtr closest);
    [PreserveSig]int GetMixFormat(out IntPtr format);[PreserveSig]int GetDevicePeriod(out long normal,out long minimum);
    [PreserveSig]int Start();[PreserveSig]int Stop();[PreserveSig]int Reset();[PreserveSig]int SetEventHandle(IntPtr handle);
    [PreserveSig]int GetService(ref Guid id,[MarshalAs(UnmanagedType.IUnknown)]out object service);
}
[ComImport,Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioCaptureClient {
    [PreserveSig]int GetBuffer(out IntPtr data,out uint frames,out uint flags,out ulong position,out ulong qpc);
    [PreserveSig]int ReleaseBuffer(uint frames);[PreserveSig]int GetNextPacketSize(out uint frames);
}
