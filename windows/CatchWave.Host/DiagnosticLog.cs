// SPDX-License-Identifier: GPL-3.0-or-later
using System.Text.RegularExpressions;
namespace CatchWave.Host;

/** Fixed codes/numeric metadata only. No audio, device/address names, QR, keys or exception text. */
internal sealed class DiagnosticLog:IDisposable {
    internal const int Limit=256,MaxBytes=131072;
    private readonly object gate=new(),flushGate=new();private readonly Queue<string> ring=new();private readonly System.Threading.Timer? timer;
    private readonly string? file;private readonly Func<DateTimeOffset> clock;private long version,saved=-1;
    private string session="local";internal long StorageFailures;internal string LastReason="NONE";
    internal DiagnosticLog(string? file=null,Func<DateTimeOffset>? clock=null){this.file=file;this.clock=clock??(()=>DateTimeOffset.Now);
        if(file!=null){try{if(File.Exists(file)&&new FileInfo(file).Length<=MaxBytes)foreach(string line in File.ReadAllLines(file))if(Regex.IsMatch(line,@"^[0-9T:.+\-]+ zone=[A-Za-z0-9_/+\-]+ session=(local|[0-9a-f]{32}) event=[A-Z_]+ code=[A-Z_]{1,40}( v[0-9]+=-?[0-9]+)*$")){if(ring.Count==Limit)ring.Dequeue();ring.Enqueue(line);}}catch{StorageFailures++;}
            timer=new System.Threading.Timer(_=>Flush(),null,1000,1000);}
    }
    internal static DiagnosticLog Local()=>new(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"CatchWave","diagnostics.log"));
    internal void Begin(){lock(gate){session=Guid.NewGuid().ToString("N");}Event("SESSION","ATTEMPT");}
    internal void Session(byte[] id){if(id.Length!=16)return;lock(gate){session=Convert.ToHexString(id).ToLowerInvariant();}Event("SESSION","CONNECTED");}
    internal void Event(string type,string code,params long[] values){try{if(!Regex.IsMatch(type,"^[A-Z_]{1,24}$")||!Regex.IsMatch(code,"^[A-Z_]{1,40}$")||values.Length>12)return;
        lock(gate){string zone=Regex.Replace(TimeZoneInfo.Local.Id,"[^A-Za-z0-9_/+\\-]","_");string line=$"{clock():O} zone={zone} session={session} event={type} code={code}";
            for(int i=0;i<values.Length;i++)line+=$" v{i}={values[i]}";if(ring.Count==Limit)ring.Dequeue();ring.Enqueue(line);version++;if(type is "STOP" or "FOCUS" or "ROUTE")LastReason=code;}
        }catch{Interlocked.Increment(ref StorageFailures);}}
    internal string Text(){lock(gate)return string.Join('\n',ring)+"\n";}
    internal void Flush(){if(file==null||!Monitor.TryEnter(flushGate))return;try{long v;string text;lock(gate){v=version;if(saved==v)return;text=Text();}try{Directory.CreateDirectory(Path.GetDirectoryName(file)!);File.WriteAllText(file+".tmp",text);File.Move(file+".tmp",file,true);saved=v;}catch{Interlocked.Increment(ref StorageFailures);}}finally{Monitor.Exit(flushGate);}}
    public void Dispose(){timer?.Dispose();Flush();}
}
