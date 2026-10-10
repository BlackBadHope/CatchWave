// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** One bounded volume step per fresh sensor sample; there is no timer or pending queue. */
final class TiltRepeater {
    static final long DWELL_MS=250,REPEAT_MS=400;
    static final double ENTER=.42,EXIT=.26;
    private int direction,candidate;private long since,last=-1,emitted=-10000;
    void reset(long now){stop();last=-1;emitted=now-REPEAT_MS;}
    void stop(){direction=0;candidate=0;}
    boolean active(){return direction!=0;}
    int update(long now,double angle,boolean ready){
        if(last>=0&&(now<last||now-last>150)){stop();}last=now;
        if(!ready||!Double.isFinite(angle)||Math.abs(angle)<EXIT){stop();return 0;}
        int sign=angle>0?1:-1;
        if(direction==sign){
            if(now-emitted>=REPEAT_MS){emitted=now;return sign;}
            return 0;
        }
        if(direction!=0)stop();
        if(Math.abs(angle)<=ENTER){candidate=0;return 0;}
        if(candidate!=sign){candidate=sign;since=now;return 0;}
        if(now-since<DWELL_MS||now-emitted<REPEAT_MS)return 0;
        direction=sign;emitted=now;return sign;
    }
}
