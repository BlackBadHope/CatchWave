// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Text;
using System.Security.Cryptography;
namespace CatchWave.Host;

internal sealed class UsbHost(HostIdentity identity,HostWindow window,HostStatus lan,bool synthetic,int port,Func<string> address,CancellationToken outer,UsbAutoSettings? auto=null,HostAudioSource? audio=null):IAsyncDisposable {
    private CancellationTokenSource? active;private Task worker=Task.CompletedTask;
    internal bool Running=>!worker.IsCompleted;
    internal Task Connect(UsbAccessory.Phone selected,bool allowSetup=true){
        if(Running)return Task.CompletedTask;
        active?.Dispose();active=CancellationTokenSource.CreateLinkedTokenSource(outer);var token=active.Token;
        worker=Task.Run(async()=>{
            var state=new HostStatus(lan.Log);
            bool remembered=false;
            void Authenticated(){if(!remembered&&state.Snapshot.Stage==HostStage.Authenticated){remembered=true;if(auto!=null&&!auto.Remember(selected))state.Log.Event("USB","USB_AUTO_SAVE_FAILED");}}
            state.Changed+=Authenticated;
            try{
                using Stream pipe=await UsbAccessory.Open(selected,token,(code,values)=>state.Log.Event("USB",code,values),allowSetup);
                using(var setup=CancellationTokenSource.CreateLinkedTokenSource(token)){
                    setup.CancelAfter(TimeSpan.FromSeconds(90));
                    state.Log.Event("USB","USB_BOOTSTRAP_START");
                    await Bootstrap(pipe,identity.PairCode(address(),port),setup.Token);
                    state.Log.Event("USB","USB_BOOTSTRAP_ACK");
                }
                window.UseStatus(state,"USB");
                await new HostSession(identity,synthetic,false,status:state,permission:()=>window.Permission,authorizedSink:window.Execute,audio:audio).Run(pipe,token);
            }catch(Exception e)when(e is IOException or OperationCanceledException or System.Security.Authentication.AuthenticationException or NotSupportedException or System.Runtime.InteropServices.COMException){
                if(!token.IsCancellationRequested)window.UsbMessage(e is IOException?e.Message:"USB_SESSION_ENDED");
            }finally{state.Changed-=Authenticated;window.UseStatus(lan,"Wi-Fi");}
        },CancellationToken.None);
        return Task.CompletedTask;
    }
    internal static async Task Bootstrap(Stream stream,string pair,CancellationToken token){
        byte[] record=Encoding.ASCII.GetBytes(pair);
        if(record.Length is <1 or >256)throw new IOException("USB_PAIRING_INVALID");
        byte[] packet=new byte[9+record.Length];Encoding.ASCII.GetBytes("CWUSB1\n").CopyTo(packet,0);BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(7),(ushort)record.Length);record.CopyTo(packet,9);
        try{await stream.WriteAsync(packet,token);await stream.FlushAsync(token);byte[] ack=new byte[9];await stream.ReadExactlyAsync(ack,token);
            if(!ack.AsSpan().SequenceEqual("CWUSB1OK\n"u8))throw new IOException("USB_PERMISSION_OR_APP_REQUIRED");
        }finally{CryptographicOperations.ZeroMemory(record);CryptographicOperations.ZeroMemory(packet);}
    }
    public async ValueTask DisposeAsync(){active?.Cancel();try{await worker;}catch(OperationCanceledException){}finally{active?.Dispose();}}
}
