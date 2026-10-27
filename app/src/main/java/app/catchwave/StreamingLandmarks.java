// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunked landmark extraction so a whole song never has to sit in memory.
 * Each pass keeps a pairing tail; anchors are emitted exactly once, fully paired.
 */
final class StreamingLandmarks {
    static final int TAIL_FRAMES=48; // LocalFingerprint's maximum pairing distance.
    private final List<LocalFingerprint.Landmark> out=new ArrayList<>();
    private short[] buffer=new short[LocalFingerprint.WINDOW*4];
    private int buffered,frameBase;
    private boolean overflow;

    void feed(short[] pcm,int length){
        if(overflow||length<=0)return;
        if(buffered+length>buffer.length){
            short[] grown=new short[Math.max(buffer.length*2,buffered+length)];
            System.arraycopy(buffer,0,grown,0,buffered);buffer=grown;
        }
        System.arraycopy(pcm,0,buffer,buffered,length);buffered+=length;
        drain(false);
    }
    List<LocalFingerprint.Landmark> finish(){
        drain(true);
        return out;
    }
    int frames(){return frameBase;}
    boolean overflowed(){return overflow;}

    private void drain(boolean flush){
        int frames=buffered<LocalFingerprint.WINDOW?0:(buffered-LocalFingerprint.WINDOW)/LocalFingerprint.HOP+1;
        int emit=flush?frames:frames-TAIL_FRAMES;
        if(emit<=0)return;
        for(LocalFingerprint.Landmark mark:LocalFingerprint.landmarks(buffer,0,buffered)){
            if(mark.time>=emit)continue;
            int time=mark.time+frameBase;
            if(time>LibraryIndex.MAX_SONG_FRAMES){overflow=true;return;}
            out.add(new LocalFingerprint.Landmark(time,mark.hash));
        }
        int consumed=emit*LocalFingerprint.HOP;
        System.arraycopy(buffer,consumed,buffer,0,buffered-consumed);
        buffered-=consumed;frameBase+=emit;
    }
}
