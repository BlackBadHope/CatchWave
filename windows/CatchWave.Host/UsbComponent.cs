// SPDX-License-Identifier: GPL-3.0-or-later
using System.Diagnostics;
using System.Net.Http;
using System.Security.Cryptography;
namespace CatchWave.Host;

/** Explicit owner setup only; never invoked by Host startup or network discovery. */
internal static class UsbComponent {
    internal static async Task BindAccessory(UsbAccessory.Phone selected,CancellationToken token){
        token.ThrowIfCancellationRequested();string instance=WinUsbBulkStream.ResolveInstance(selected);
        string serial=selected.Info.Id.Instance;if(!System.Text.RegularExpressions.Regex.IsMatch(serial,"^[A-Za-z0-9&_.-]{1,200}$"))throw new IOException("USB_INSTANCE_INVALID");
        var start=new ProcessStartInfo{FileName=Path.Combine(AppContext.BaseDirectory,"CatchWave.Host.exe"),UseShellExecute=true,Verb="runas",WindowStyle=ProcessWindowStyle.Hidden};
        start.ArgumentList.Add("--usb-setup");start.ArgumentList.Add("install");start.ArgumentList.Add(instance);start.ArgumentList.Add(serial);
        try {using var process=Process.Start(start)??throw new IOException("USB_BINDING_FAILED");await process.WaitForExitAsync(token);if(process.ExitCode!=0)throw new IOException("USB_BINDING_FAILED");}
        catch(System.ComponentModel.Win32Exception e){throw new IOException(e.NativeErrorCode==1223?"USB_SETUP_CANCELLED":"USB_BINDING_FAILED");}
    }
    internal static async Task Install(){
        using var existing=Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\UsbDk");
        if(existing!=null)throw new IOException("USB_COMPONENT_ALREADY_INSTALLED");
        string folder=Path.Combine(Path.GetTempPath(),"CatchWave-UsbDk-1.0.22");Directory.CreateDirectory(folder);
        string msi=Path.Combine(folder,"UsbDk_1.0.22_x64.msi");
        if(!File.Exists(msi)){
            using var client=new HttpClient{Timeout=TimeSpan.FromSeconds(60)};
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(60));
            using var response=await client.GetAsync("https://github.com/daynix/UsbDk/releases/download/v1.00-22/UsbDk_1.0.22_x64.msi",HttpCompletionOption.ResponseHeadersRead,deadline.Token);
            response.EnsureSuccessStatusCode();
            if(response.Content.Headers.ContentLength is long length&&length!=6348800)throw new IOException("USB_INSTALLER_INVALID");
            string pending=msi+".download";
            try{await using(var file=File.Create(pending)){await response.Content.CopyToAsync(file,deadline.Token);}
                if(new FileInfo(pending).Length!=6348800)throw new IOException("USB_INSTALLER_INVALID");File.Move(pending,msi,true);
            }finally{if(File.Exists(pending))File.Delete(pending);}
        }
        // Digest pins the exact independently Authenticode-verified Red Hat package, including its signatures.
        using(var file=File.OpenRead(msi))if(Convert.ToHexString(SHA256.HashData(file))!="91F6F695E1E13C656024E6D3B55620BF08D8835EF05EE0496935BA6BB62466A5")throw new IOException("USB_INSTALLER_INVALID");
        var start=new ProcessStartInfo{FileName=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"msiexec.exe"),Arguments=$"/i \"{msi}\" /passive /norestart /l*v \"{Path.Combine(folder,"installation.log")}\"",UseShellExecute=true,Verb="runas"};
        using var process=Process.Start(start)??throw new IOException("USB_INSTALL_FAILED");await process.WaitForExitAsync();
        if(process.ExitCode is not 0 and not 3010)throw new IOException("USB_INSTALL_FAILED");
    }
}
