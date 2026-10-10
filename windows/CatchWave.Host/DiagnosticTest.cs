// SPDX-License-Identifier: GPL-3.0-or-later
namespace CatchWave.Host;
internal static class DiagnosticTest {
    internal static int Run(){int checks=0;void Check(bool value){if(!value)throw new Exception("Diagnostic assertion failed");checks++;}
        using var log=new DiagnosticLog();log.Session(new byte[16]);log.Event("STOP","private endpoint/token");for(int i=0;i<1000;i++)log.Event("SNAPSHOT","PCM_SENT",i);
        string text=log.Text();Check(text.Split('\n',StringSplitOptions.RemoveEmptyEntries).Length==256);Check(text.Contains("zone=")&&text.Contains("session=00000000000000000000000000000000"));Check(!text.Contains("private"));Check(System.Text.Encoding.UTF8.GetByteCount(text)<DiagnosticLog.MaxBytes);
        var state=new HostStatus(log);state.Begin();state.Frame(0,true);Check(state.Snapshot.Stage==HostStage.SilenceSent&&state.Snapshot.Error==HostError.None);state.Failure(HostError.WriteTimeout);Check(state.Snapshot.Stage==HostStage.Error);Check(log.Text().Contains("code=SILENT_SOURCE")&&log.Text().Contains("code=WRITETIMEOUT"));
        using var clockFailure=new DiagnosticLog(clock:()=>throw new InvalidOperationException("private"));clockFailure.Event("STOP","INTERNAL");Check(clockFailure.StorageFailures==1);
        state.Begin();state.Frame(0,false);Check(log.Text().Contains("code=PCM_SENT"));Check(log.Text().Contains("code=ATTEMPT"));
        string blocker=Path.Combine(Path.GetTempPath(),"catchwave-log-test-"+Guid.NewGuid().ToString("N"));File.WriteAllText(blocker,"fixture");
        try{using var fail=new DiagnosticLog(Path.Combine(blocker,"diagnostics.log"));fail.Event("ROUTE","DEFAULT_CHANGED");fail.Flush();Check(fail.StorageFailures>0&&fail.Text().Contains("code=DEFAULT_CHANGED"));}finally{File.Delete(blocker);}
        return checks;
    }
}
