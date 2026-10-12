// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
/** Startup backlog is discarded; a slow bounded clock correction preserves long live sessions. */
final class LanTimeline {
    private final long initialStamp,initialArrival;
    private long offset,armedOffset,windowSince,windowMinimum=Long.MAX_VALUE;
    LanTimeline(long stamp,long arrival){initialStamp=stamp;initialArrival=arrival;}
    boolean startupFresh(long stamp,long arrival){return (arrival-initialArrival)-(stamp-initialStamp)<=100;}
    synchronized void arm(long stamp,long arrival){offset=armedOffset=arrival-stamp;windowSince=arrival;windowMinimum=Long.MAX_VALUE;}
    // Only network arrivals estimate clock offset. Playback waiting must never age the baseline.
    // A ten-second lower envelope rejects transient backlog; upward correction is capped at
    // 1000 ppm. A sudden delay cannot rebase itself or renew the five-second recovery grace.
    synchronized void observe(long stamp,long arrival){
        if(arrival<windowSince)return;
        windowMinimum=Math.min(windowMinimum,arrival-stamp);
        long elapsed=arrival-windowSince;
        if(elapsed>=10000){offset=Math.min(windowMinimum,offset+10);windowSince=arrival;windowMinimum=Long.MAX_VALUE;}
    }
    synchronized long excessAge(long stamp,long arrival){return arrival-stamp-offset;}
    synchronized long clockCorrection(){return offset-armedOffset;}
    synchronized boolean stalled(long stamp,long arrival){return excessAge(stamp,arrival)>500;}
}
