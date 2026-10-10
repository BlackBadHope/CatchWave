// SPDX-License-Identifier: GPL-3.0-or-later
using System.Reflection;
namespace CatchWave.Host;
/** Inspect an unshown test form; no screen capture or owner QR, no Windows media input. */
internal static class HostUiTest {
    internal static int Run(){int checks=0;Exception? failure=null;var thread=new Thread(()=>{try{
        using var identity=new HostIdentity();using var stop=new CancellationTokenSource();var status=new HostStatus();
        using var form=new HostWindow(identity,"127.0.0.1",49372,true,false,stop,_=>true,false,status);
        var action=(Button)typeof(HostWindow).GetField("control",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(form)!;
        var label=(Label)typeof(HostWindow).GetField("controlStatus",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(form)!;
        void Check(bool value){if(!value)throw new Exception("UI state assertion failed");checks++;}
        Check(!action.Enabled&&action.Text=="Включить управление хостом"&&label.Text.StartsWith("Управление выключено"));
        status.Begin();status.Stage(HostStage.Tls);form.Render();Check(!action.Enabled&&label.Text.StartsWith("Управление выключено"));
        status.Frame(0,true);form.Render();Check(!action.Enabled); // Sent silence alone does not confirm phone output.
        status.Client(Protocol.ClientState(1,1,false,false));form.Render();Check(action.Enabled&&action.Text=="Включить управление хостом");
        typeof(HostWindow).GetMethod("SetMedia",BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(form,new object[]{true});
        Check(!action.Enabled&&label.Text.Contains("включите управление на телефоне"));
        status.Client(Protocol.ClientState(1,2,true,true,1));form.Render();Check(action.Enabled&&action.Text=="Выключить управление хостом"&&label.Text.StartsWith("Управление включено"));
        status.Failure(HostError.PeerClosed);form.Render();Check(!action.Enabled&&action.Text=="Включить управление хостом"&&label.Text.StartsWith("Управление выключено"));
        using var locked=new HostWindow(identity,"127.0.0.1",49373,true,true,stop,_=>throw new Exception("Locked pilot must not save"),true,new HostStatus());
        Check(!locked.MediaEnabled);
        using var failedSave=new HostWindow(identity,"127.0.0.1",49374,true,true,stop,_=>false,false,new HostStatus());
        typeof(HostWindow).GetMethod("SetMedia",BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(failedSave,new object[]{false});failedSave.Render();failedSave.Render();
        var warning=(Label)typeof(HostWindow).GetField("saving",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(failedSave)!;Check(warning.Text.Contains("Выключение не сохранено")&&!failedSave.MediaEnabled);
    }catch(Exception e){failure=e;}});thread.SetApartmentState(ApartmentState.STA);thread.Start();if(!thread.Join(TimeSpan.FromSeconds(5)))throw new Exception("UI test timed out");if(failure!=null)throw failure;return checks;}
}
