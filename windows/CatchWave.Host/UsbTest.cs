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
        var transfer=typeof(UsbAccessory).GetNestedType("Transfer",System.Reflection.BindingFlags.NonPublic)!;
        if(Marshal.SizeOf(transfer)!=72||Marshal.OffsetOf(transfer,"Bytes")!=48)throw new Exception("UsbDk transfer ABI");checks++;
        if(UsbAccessory.TransferError(true,0x80000300,2,10)!="USB_NATIVE_INVALID_PARAMETER"||UsbAccessory.TransferError(true,0xffffffff80000300,2,10)!="USB_NATIVE_INVALID_PARAMETER")throw new Exception("Signed native status must preserve the invalid-parameter cause");checks++;
        if(UsbAccessory.TransferError(true,0,19,18)!="USB_TRANSFER_BOUNDS"||UsbAccessory.TransferError(false,0,2,10)!="USB_TRANSFER_FAILED"||UsbAccessory.TransferError(true,0,2,10)!=null)throw new Exception("USB completion contract");checks++;
        if(Marshal.SizeOf<WinUsbNegotiator.SetupPacket>()!=8)throw new Exception("WinUSB setup ABI");checks++;
        if(!WinUsbNegotiator.PathMatches(@"\\?\usb#vid_22d9&pid_2769#phone-one#{00000000-0000-0000-0000-000000000000}",@"USB\VID_22D9&PID_2769","PHONE-ONE")||WinUsbNegotiator.PathMatches(@"\\?\usb#vid_22d9&pid_2769#phone-two#{00000000-0000-0000-0000-000000000000}",@"USB\VID_22D9&PID_2769","PHONE-ONE")||WinUsbNegotiator.PathMatches(@"\\?\usb#vid_1234&pid_2769#phone-one#{00000000-0000-0000-0000-000000000000}",@"USB\VID_22D9&PID_2769","PHONE-ONE"))throw new Exception("WinUSB must bind the selected hardware and instance");checks++;
        if(Marshal.SizeOf<WinUsbBulkStream.InterfaceDescriptor>()!=9||Marshal.SizeOf<WinUsbBulkStream.PipeInfo>()!=12)throw new Exception("WinUSB data ABI");checks++;
        var selected=new UsbAccessory.Phone(new UsbAccessory.DeviceInfo{Id=new UsbAccessory.DeviceId{Device=@"USB\VID_18D1&PID_2D01",Instance="PHONE-ONE"},Descriptor=new UsbAccessory.DeviceDescriptor{Vendor=0x18d1,Product=0x2d01}});
        if(!WinUsbBulkStream.MatchesIdentity(@"USB\VID_18D1&PID_2D01&MI_00\CHILD",@"USB\VID_18D1&PID_2D01\phone-one",selected)||WinUsbBulkStream.MatchesIdentity(@"USB\VID_18D1&PID_2D01&MI_01\CHILD",@"USB\VID_18D1&PID_2D01\PHONE-ONE",selected)||WinUsbBulkStream.MatchesIdentity(@"USB\VID_18D1&PID_2D01&MI_00\CHILD",@"USB\VID_18D1&PID_2D01\PHONE-TWO",selected))throw new Exception("WinUSB data must exclude ADB and another phone");checks++;
        try{WinUsbBulkStream.TryOpen(selected,cancelled.Token,null);throw new Exception("Cancelled WinUSB data reached device enumeration");}catch(OperationCanceledException){checks++;}
        return checks;
    }
}
