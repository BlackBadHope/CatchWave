// SPDX-License-Identifier: GPL-3.0-or-later
using System.Runtime.InteropServices;
namespace CatchWave.Host;
internal static class UsbTest {
    internal static async Task<int> Run(){
        if(Marshal.SizeOf<UsbAccessory.DeviceId>()!=800||Marshal.SizeOf<UsbAccessory.DeviceInfo>()!=848)throw new Exception("UsbDk native ABI");
        byte[] configuration={9,2,55,0,2,1,0,128,50,9,4,0,0,2,255,255,0,0,7,5,0x81,2,0,2,0,7,5,2,2,0,2,0,9,4,1,0,2,255,66,1,0,7,5,0x83,2,0,2,0,7,5,4,2,0,2,0};
        if(UsbAccessory.Endpoints(configuration)!=(0x81,2))throw new Exception("Accessory pipes must exclude ADB interface");
        int checks=2;
        foreach(var malformed in new[]{configuration[..54],new byte[9],configuration.Select((b,i)=>i==18?(byte)1:b).ToArray()}){
            try{UsbAccessory.Endpoints(malformed);throw new Exception("Malformed USB descriptor accepted");}catch(IOException){checks++;}
        }
        using var cancelled=new CancellationTokenSource();cancelled.Cancel();
        try{await UsbAccessory.Open(new UsbAccessory.Phone(default),cancelled.Token);throw new Exception("Cancelled USB selection reached driver");}catch(OperationCanceledException){checks++;}
        if(UsbAccessory.DriverError(2)!="USB_DRIVER_RESTART_REQUIRED"||UsbAccessory.DriverError(5)!="USB_DRIVER_ACCESS_DENIED"||UsbAccessory.DriverError(32)!="USB_DRIVER_BUSY")throw new Exception("USB error must distinguish installation from denied access and busy control device");checks++;
        return checks;
    }
}
