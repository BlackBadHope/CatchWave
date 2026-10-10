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
    private readonly Queue<byte> queued=new();private readonly int sourceBits,blockAlign;private readonly bool floating;
    public PcmFormat Format{get;}
    public WasapiSource() {
        object? partialEnumerator=null,partialClient=null,partialCapture=null;IMMDevice? partialDevice=null;
        try {
        enumeratorObject=Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"))!)!;
        partialEnumerator=enumeratorObject;
        var enumerator=(IMMDeviceEnumerator)enumeratorObject;
        Check(enumerator.GetDefaultAudioEndpoint(0,1,out device));partialDevice=device;Guid iid=typeof(IAudioClient).GUID;
        Check(device.Activate(ref iid,23,IntPtr.Zero,out object value));partialClient=value;client=(IAudioClient)value;
        Check(client.GetMixFormat(out IntPtr mix));
        try {
            ushort tag=(ushort)Marshal.ReadInt16(mix),channels=(ushort)Marshal.ReadInt16(mix,2);
            int rate=Marshal.ReadInt32(mix,4);blockAlign=(ushort)Marshal.ReadInt16(mix,12);sourceBits=(ushort)Marshal.ReadInt16(mix,14);
            Format=new(rate,channels);
            if(tag==0xFFFE){Guid sub=Marshal.PtrToStructure<Guid>(mix+24);floating=sub==new Guid("00000003-0000-0010-8000-00aa00389b71");if(!floating&&sub!=new Guid("00000001-0000-0010-8000-00aa00389b71"))throw new NotSupportedException("PCM subtype unsupported");}
            else {floating=tag==3;if(tag!=1&&!floating)throw new NotSupportedException("PCM format unsupported");}
            if(!Format.Valid||sourceBits is not (16 or 24 or 32)||floating&&sourceBits!=32||blockAlign!=channels*sourceBits/8)
                throw new NotSupportedException("Default output must be mono/stereo PCM at 44100/48000/96000 Hz");
            Check(client.Initialize(0,0x00020000,1_000_000,0,mix,IntPtr.Zero)); // Shared, AUDCLNT_STREAMFLAGS_LOOPBACK.
        }finally{Marshal.FreeCoTaskMem(mix);}
        Guid captureId=typeof(IAudioCaptureClient).GUID;Check(client.GetService(ref captureId,out object service));partialCapture=service;capture=(IAudioCaptureClient)service;Check(client.Start());
        }catch{
            if(partialCapture!=null)Marshal.ReleaseComObject(partialCapture);if(partialClient!=null)Marshal.ReleaseComObject(partialClient);
            if(partialDevice!=null)Marshal.ReleaseComObject(partialDevice);if(partialEnumerator!=null)Marshal.ReleaseComObject(partialEnumerator);throw;
        }
    }
    public byte[] Frame() {
        Check(capture.GetNextPacketSize(out uint available));
        while(available>0){
            Check(capture.GetBuffer(out IntPtr ptr,out uint frames,out uint flags,out _,out _));
            try {
                if((flags&1)!=0)queued.Clear(); // Discontinuity: drop old samples.
                int count=checked((int)frames*blockAlign);byte[] native=new byte[count];
                if((flags&2)==0)Marshal.Copy(ptr,native,0,count); // Silent buffers have no readable pointer.
                byte[] pcm=Convert(native,sourceBits,floating);
                foreach(byte sample in pcm)queued.Enqueue(sample);
                // Keep at most 160 ms; packet overflow never turns into unlimited latency.
                while(queued.Count>Format.Bytes*8)queued.Dequeue();
            }finally{Check(capture.ReleaseBuffer(frames));}
            Check(capture.GetNextPacketSize(out available));
        }
        byte[] frame=new byte[Format.Bytes];int n=Math.Min(frame.Length,queued.Count);
        for(int i=0;i<n;i++)frame[i]=queued.Dequeue();return frame;
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
    [PreserveSig]int EnumAudioEndpoints(int flow,uint mask,out IntPtr devices);
    [PreserveSig]int GetDefaultAudioEndpoint(int flow,int role,out IMMDevice device);
}
[ComImport,Guid("D666063F-1587-4E43-81F1-B948E807363F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDevice {
    [PreserveSig]int Activate(ref Guid id,uint context,IntPtr parameters,[MarshalAs(UnmanagedType.IUnknown)]out object value);
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
