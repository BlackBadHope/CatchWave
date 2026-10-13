// SPDX-License-Identifier: GPL-3.0-or-later
namespace CatchWave.Host;

/** Render the real WinForms controls with public fixtures: no listener, capture, keys or saved settings. */
internal static class HostPreview {
    internal static int Run(string directory){
        Directory.CreateDirectory(directory);
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
        foreach(string state in new[]{"waiting","ready","diagnostics","disconnected","maximized"}){
            using var stop=new CancellationTokenSource();using var log=new DiagnosticLog();var model=new HostStatus(log);
            using var form=new HostWindow(PairingQr.Fixture,new string('0',64),"192.0.2.1",49372,false,true,stop,_=>true,false,model);
            var audio=new HostAudioSource(factory:(_,_)=>new SilenceSource(),liveSwitch:true);var output=new AudioOutput("public-fixture","Наушники · пример аудиовыхода",1,true);
            audio.Select(output);form.ConfigureAudio(audio,()=>new[]{output});
            if(state!="waiting"){model.Begin();model.Frame(0,false);model.Client(Protocol.ClientState(2,2,true,true));}
            if(state=="disconnected")model.Failure(HostError.PeerClosed);
            form.Render();if(state=="diagnostics")form.SetDiagnosticsForPreview(true);
            form.ShowInTaskbar=false;
            form.StartPosition=FormStartPosition.Manual;form.Location=new Point(-32000,-32000);
            form.Show();if(state=="maximized")form.ClientSize=new Size(1920,1008);form.PerformLayout();Application.DoEvents();
            form.AssertLayout();using var bitmap=form.RenderClient();
            bitmap.Save(Path.Combine(directory,state+".png"),System.Drawing.Imaging.ImageFormat.Png);
        }
        return 0;
    }
}
