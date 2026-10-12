// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Bounded metadata only. Call sites supply fixed codes and numeric values, never exception text or credentials. */
final class DiagnosticLog {
    enum Event {SESSION,STAGE,STOP,RECONNECT,FOCUS,ROUTE,SNAPSHOT,GESTURE,VOLUME,ACK,ARMING,CONTROL,STORAGE,POWER}
    static final int LIMIT=256,MAX_BYTES=131072;
    private final ArrayDeque<String> ring=new ArrayDeque<>();
    private final Supplier<Instant> clock;private final ZoneId zone;
    private String session="local";private long version,failures;
    DiagnosticLog(){this(Instant::now,ZoneId.systemDefault());}
    DiagnosticLog(Supplier<Instant> clock,ZoneId zone){this.clock=clock;this.zone=zone;}
    synchronized void begin(){session=UUID.randomUUID().toString().replace("-","");event(Event.SESSION,"ATTEMPT");}
    synchronized void session(byte[] id){if(id.length!=16)return;StringBuilder b=new StringBuilder();for(byte v:id)b.append(String.format(Locale.ROOT,"%02x",v&255));session=b.toString();event(Event.SESSION,"CONNECTED");}
    synchronized void event(Event type,String code,long... values){
        try {
        if(!code.matches("[A-Z_]{1,40}")||values.length>12)return;
        StringBuilder b=new StringBuilder(clock.get().toString()).append(" zone=").append(zone.getId()).append(" offset=").append(zone.getRules().getOffset(clock.get())).append(" session=").append(session).append(" event=").append(type).append(" code=").append(code);
        for(int i=0;i<values.length;i++)b.append(" v").append(i).append('=').append(values[i]);
        if(ring.size()==LIMIT)ring.removeFirst();ring.addLast(b.toString());version++;
        }catch(RuntimeException ignored){failures++;}
    }
    synchronized String text(){return String.join("\n",ring)+"\n";}
    synchronized long version(){return version;}
    synchronized long failures(){return failures;}
    synchronized void restore(String text){if(text.length()>MAX_BYTES)return;ArrayList<String> current=new ArrayList<>(ring);ring.clear();for(String line:text.split("\n")){if(validLine(line)){if(ring.size()==LIMIT)ring.removeFirst();ring.addLast(line);}}for(String line:current){if(ring.size()==LIMIT)ring.removeFirst();ring.addLast(line);}version++;}
    private static boolean validLine(String s){return s.length()<=500&&s.matches("[0-9TZ:.+\\-]+ zone=[A-Za-z0-9_/+\\-]+ offset=[0-9Z:+\\-]+ session=(?:local|[0-9a-f]{32}) event=[A-Z]+ code=[A-Z_]{1,40}(?: v[0-9]+=-?[0-9]+)*");}
    static String output(boolean fresh,boolean silent,int volume,boolean focusLost,int route){return !fresh?"STOPPED_STREAM":focusLost?"FOCUS_LOST":route<0?"ROUTE_UNKNOWN":volume==0?"VOLUME_ZERO":silent?"SILENT_SOURCE":"OUTPUT_ACCEPTED";}
}
