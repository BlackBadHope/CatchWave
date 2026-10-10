// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
/** Startup may wait for Android focus/output. Discard its backlog before arming live drift checks. */
final class LanTimeline {
    private final long initialStamp,initialArrival;
    private long liveStamp,liveArrival;
    LanTimeline(long stamp,long arrival){initialStamp=stamp;initialArrival=arrival;}
    boolean startupFresh(long stamp,long arrival){return (arrival-initialArrival)-(stamp-initialStamp)<=100;}
    void arm(long stamp,long arrival){liveStamp=stamp;liveArrival=arrival;}
    boolean stalled(long stamp,long arrival){return (arrival-liveArrival)-(stamp-liveStamp)>500;}
}
