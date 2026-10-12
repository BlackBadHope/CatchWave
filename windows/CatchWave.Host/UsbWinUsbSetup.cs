// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.IO;
using System.Text;
using System.Runtime.InteropServices;
using System.ComponentModel;
using Microsoft.Win32;

namespace CatchWave.Host;
internal static class UsbWinUsbSetup {
 const uint ClassDrivers=1,SingleInf=0x10000;
 static readonly Guid InterfaceGuid=new("805936D7-7EEC-474A-970C-181FE29D96CF");
 [StructLayout(LayoutKind.Sequential)] struct Device {internal uint Size;internal Guid Class;internal uint Node;internal nint Reserved;}
 [StructLayout(LayoutKind.Sequential)] struct Change {internal uint HeaderSize,Function,State,Scope,Profile;}
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct InstallParams {
  internal uint Size,Flags,FlagsEx;internal nint Parent,Handler,Context,Queue,ClassReserved;internal uint Reserved;
  [MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)]internal string DriverPath;
 }
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct Driver {
  internal uint Size,Type;internal nint Reserved;
  [MarshalAs(UnmanagedType.ByValTStr,SizeConst=256)]internal string Description;
  [MarshalAs(UnmanagedType.ByValTStr,SizeConst=256)]internal string Manufacturer;
  [MarshalAs(UnmanagedType.ByValTStr,SizeConst=256)]internal string Provider;
  internal long Date;internal ulong Version;
 }
 static void Check(bool result,string step){if(!result)throw new Win32Exception(Marshal.GetLastPInvokeError(),step);}
 internal static int Run(string[] args){
  try {
   if(args.Length!=3||args[0] is not "inspect" and not "install" and not "restore")throw new ArgumentException("inspect|install|restore instance parent-serial");
   string stateDirectory=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),"CatchWave","USB");
   if(args[0]!="inspect"){Directory.CreateDirectory(stateDirectory);var log=new StreamWriter(Path.Combine(stateDirectory,"setup.log"),true){AutoFlush=true};Console.SetOut(log);Console.SetError(log);}
   string instance=args[1],serial=args[2];
   if(!System.Text.RegularExpressions.Regex.IsMatch(instance,@"^USB\\VID_18D1&PID_(2D01&MI_00|2D00)\\[A-Z0-9&]+$",System.Text.RegularExpressions.RegexOptions.IgnoreCase))throw new ArgumentException("Accessory interface zero only");
   if(!System.Text.RegularExpressions.Regex.IsMatch(serial,@"^[A-Z0-9&_.-]{1,200}$",System.Text.RegularExpressions.RegexOptions.IgnoreCase))throw new ArgumentException("Invalid serial");
   nint devices=Native.Create(0,0);if(devices is 0 or -1)throw new Win32Exception(Marshal.GetLastPInvokeError());
   try {
    var device=new Device{Size=(uint)Marshal.SizeOf<Device>()};Check(Native.Open(devices,instance,0,0,ref device),"Open selected accessory");
    if(instance.StartsWith(@"USB\VID_18D1&PID_2D00\",StringComparison.OrdinalIgnoreCase)){
     if(!instance.Equals(@"USB\VID_18D1&PID_2D00\"+serial,StringComparison.OrdinalIgnoreCase))throw new IOException("Selected phone mismatch");
    }else {
     if(Native.Parent(out uint parent,device.Node,0)!=0)throw new IOException("Accessory parent unavailable");
     var parentId=new StringBuilder(512);if(Native.Id(parent,parentId,512,0)!=0||!parentId.ToString().Equals(@"USB\VID_18D1&PID_2D01\"+serial,StringComparison.OrdinalIgnoreCase))throw new IOException("Selected phone ancestry mismatch");
    }
    using(var key=Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Enum\"+instance)){
     string service=key?.GetValue("Service") as string??"";
     if(service.Length>0&&service!="WINUSB")throw new IOException("Existing accessory driver must be preserved");
    }
    string beforePath=Path.Combine(stateDirectory,"usb-binding-"+Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(instance)))[..20]+".json");
    if(args[0]=="restore"){
     using var before=System.Text.Json.JsonDocument.Parse(File.ReadAllText(beforePath));var saved=before.RootElement;
     if(!saved.GetProperty("Instance").GetString()!.Equals(instance,StringComparison.OrdinalIgnoreCase)||!saved.GetProperty("ParentSerial").GetString()!.Equals(serial,StringComparison.OrdinalIgnoreCase))throw new IOException("Rollback target mismatch");
     if(saved.GetProperty("Service").ValueKind==System.Text.Json.JsonValueKind.Null){
      Check(Native.InstallNull(0,devices,ref device,0,4,out _),"Restore unconfigured accessory");
      string? previousClass=saved.GetProperty("ClassGuid").GetString();byte[]? classValue=previousClass==null?null:Encoding.Unicode.GetBytes(previousClass+"\0");
      Check(Native.ClassProperty(devices,ref device,8,classValue,(uint)(classValue?.Length??0)),"Restore accessory setup class");
     }else if(saved.GetProperty("Service").GetString()!="WINUSB")throw new IOException("Unsupported rollback service");
     bool ownedBefore=saved.TryGetProperty("InterfaceGuids",out var oldGuids)&&oldGuids.ValueKind==System.Text.Json.JsonValueKind.Array&&oldGuids.EnumerateArray().Any(x=>Guid.TryParse(x.GetString(),out var g)&&g==InterfaceGuid);
     if(!ownedBefore)using(var key=Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Enum\"+instance+@"\Device Parameters",true)){
      if(key?.GetValue("DeviceInterfaceGUIDs") is string[] current){var remaining=current.Where(x=>!Guid.TryParse(x,out var g)||g!=InterfaceGuid).ToArray();if(remaining.Length==0)key.DeleteValue("DeviceInterfaceGUIDs",false);else key.SetValue("DeviceInterfaceGUIDs",remaining,RegistryValueKind.MultiString);}
     }Console.WriteLine("SELECTED_ACCESSORY_RESTORED");return 0;
    }
    nint drivers=devices,devicePointer=0;var setupClass=new Guid("88BAE032-5A81-49F0-BC3D-A4FF138216D6");
    if(args[0]=="inspect"){
     drivers=Native.CreateClass(ref setupClass,0);if(drivers is 0 or -1)throw new Win32Exception(Marshal.GetLastPInvokeError());
    }else {
     using(var key=Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Enum\"+instance)){
      using var parametersBefore=key?.OpenSubKey("Device Parameters");
      var before=new { Instance=instance,ParentSerial=serial,Service=key?.GetValue("Service"),Class=key?.GetValue("Class"),ClassGuid=key?.GetValue("ClassGUID"),InterfaceGuid=parametersBefore?.GetValue("DeviceInterfaceGUID"),InterfaceGuids=parametersBefore?.GetValue("DeviceInterfaceGUIDs")};
      if(!File.Exists(beforePath))File.WriteAllText(beforePath,System.Text.Json.JsonSerializer.Serialize(before));
     }
     byte[] classId=Encoding.Unicode.GetBytes(setupClass.ToString("B")+"\0");Check(Native.ClassProperty(devices,ref device,8,classId,(uint)classId.Length),"Set selected accessory setup class");
     device.Class=setupClass;devicePointer=Marshal.AllocHGlobal(Marshal.SizeOf<Device>());Marshal.StructureToPtr(device,devicePointer,false);
    }
    try {
    var parameters=new InstallParams{Size=(uint)Marshal.SizeOf<InstallParams>(),DriverPath=""};Check(Native.GetParams(drivers,devicePointer,ref parameters),"Read install parameters");
    parameters.Flags|=SingleInf;parameters.FlagsEx|=0x800;parameters.DriverPath=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows),"INF","winusb.inf");
    Check(Native.SetParams(drivers,devicePointer,ref parameters),"Select inbox INF");Check(Native.Build(drivers,devicePointer,ClassDrivers),"Build inbox driver list");
    try {
     Driver? selected=null;
     for(uint index=0;index<16;index++){
      var driver=new Driver{Size=(uint)Marshal.SizeOf<Driver>()};if(!Native.Enum(drivers,devicePointer,ClassDrivers,index,ref driver)){Console.WriteLine("ENUM_END error="+Marshal.GetLastPInvokeError());break;}
      Native.Detail(drivers,devicePointer,ref driver,0,0,out uint size);Console.WriteLine("DETAIL size="+size+" error="+Marshal.GetLastPInvokeError());if(size is <1580 or >65536)continue;
      nint detail=Marshal.AllocHGlobal((int)size);
      try {
       Marshal.WriteInt32(detail,1584);Check(Native.Detail(drivers,devicePointer,ref driver,detail,size,out _),"Read inbox driver details");
       string section=Marshal.PtrToStringUni(detail+32)??"";string inf=Marshal.PtrToStringUni(detail+544)??"";
       Console.WriteLine("INBOX_CANDIDATE section="+section+" provider="+driver.Provider);
       if(section.Equals("WINUSB",StringComparison.OrdinalIgnoreCase)&&Path.GetFullPath(inf).Equals(parameters.DriverPath,StringComparison.OrdinalIgnoreCase)&&driver.Provider=="Microsoft")selected=driver;
      }finally{Marshal.FreeHGlobal(detail);}
     }
     if(selected==null)throw new IOException("Signed inbox WinUSB candidate unavailable");
     Console.WriteLine("SELECTED_ACCESSORY_ONLY INSPECT_PASS");
     if(args[0]=="inspect")return 0;
     var chosen=selected.Value;Check(Native.Select(devices,ref device,ref chosen),"Select signed inbox WinUSB");
     using(var key=Registry.LocalMachine.CreateSubKey(@"SYSTEM\CurrentControlSet\Enum\"+instance+@"\Device Parameters")){
      var guids=(key.GetValue("DeviceInterfaceGUIDs") as string[]??Array.Empty<string>()).Take(16).Append(InterfaceGuid.ToString("B")).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
      key.SetValue("DeviceInterfaceGUIDs",guids,RegistryValueKind.MultiString);
     }
     Check(Native.Install(0,devices,ref device,ref chosen,0,out bool reboot),"Install selected inbox WinUSB");
     if(!reboot){var change=new Change{HeaderSize=8,Function=0x12,State=3,Scope=1};Check(Native.ChangeParameters(devices,ref device,ref change,(uint)Marshal.SizeOf<Change>()),"Set selected accessory restart");Check(Native.ChangeDevice(0x12,devices,ref device),"Restart selected accessory interface");}
     Console.WriteLine("SELECTED_ACCESSORY_WINUSB_INSTALLED reboot="+reboot);
     return 0;
    }finally{Native.DestroyDrivers(drivers,devicePointer,ClassDrivers);}
    }finally{if(devicePointer!=0)Marshal.FreeHGlobal(devicePointer);if(drivers!=devices)Native.Destroy(drivers);}
   }finally{Native.Destroy(devices);}
  }catch(Exception e){Console.Error.WriteLine("BINDING_FAILED "+e.GetType().Name+" "+e.Message);return 2;}
 }
 static class Native {
  [DllImport("setupapi.dll",EntryPoint="SetupDiCreateDeviceInfoList",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern nint Create(nint guid,nint window);
  [DllImport("setupapi.dll",EntryPoint="SetupDiCreateDeviceInfoList",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern nint CreateClass(ref Guid guid,nint window);
  [DllImport("setupapi.dll",EntryPoint="SetupDiSetDeviceRegistryPropertyW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool ClassProperty(nint list,ref Device device,uint property,byte[]? data,uint length);
  [DllImport("setupapi.dll",EntryPoint="SetupDiOpenDeviceInfoW",CharSet=CharSet.Unicode,SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Open(nint list,string id,nint window,uint flags,ref Device device);
  [DllImport("setupapi.dll",EntryPoint="SetupDiGetDeviceInstallParamsW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool GetParams(nint list,nint device,ref InstallParams parameters);
  [DllImport("setupapi.dll",EntryPoint="SetupDiSetDeviceInstallParamsW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool SetParams(nint list,nint device,ref InstallParams parameters);
  [DllImport("setupapi.dll",EntryPoint="SetupDiBuildDriverInfoList",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Build(nint list,nint device,uint type);
  [DllImport("setupapi.dll",EntryPoint="SetupDiEnumDriverInfoW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Enum(nint list,nint device,uint type,uint index,ref Driver driver);
  [DllImport("setupapi.dll",EntryPoint="SetupDiGetDriverInfoDetailW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Detail(nint list,nint device,ref Driver driver,nint detail,uint size,out uint needed);
  [DllImport("setupapi.dll",EntryPoint="SetupDiSetSelectedDriverW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Select(nint list,ref Device device,ref Driver driver);
  [DllImport("newdev.dll",EntryPoint="DiInstallDevice",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Install(nint window,nint list,ref Device device,ref Driver driver,uint flags,[MarshalAs(UnmanagedType.Bool)]out bool reboot);
  [DllImport("newdev.dll",EntryPoint="DiInstallDevice",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool InstallNull(nint window,nint list,ref Device device,nint driver,uint flags,[MarshalAs(UnmanagedType.Bool)]out bool reboot);
  [DllImport("setupapi.dll",EntryPoint="SetupDiDestroyDriverInfoList"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool DestroyDrivers(nint list,nint device,uint type);
  [DllImport("setupapi.dll",EntryPoint="SetupDiSetClassInstallParamsW",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool ChangeParameters(nint list,ref Device device,ref Change change,uint size);
  [DllImport("setupapi.dll",EntryPoint="SetupDiCallClassInstaller",SetLastError=true),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool ChangeDevice(uint function,nint list,ref Device device);
  [DllImport("setupapi.dll",EntryPoint="SetupDiDestroyDeviceInfoList"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern bool Destroy(nint list);
  [DllImport("cfgmgr32.dll",EntryPoint="CM_Get_Parent"),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern uint Parent(out uint parent,uint device,uint flags);
  [DllImport("cfgmgr32.dll",EntryPoint="CM_Get_Device_IDW",CharSet=CharSet.Unicode),DefaultDllImportSearchPaths(DllImportSearchPath.System32)]internal static extern uint Id(uint device,StringBuilder id,uint size,uint flags);
 }
}
