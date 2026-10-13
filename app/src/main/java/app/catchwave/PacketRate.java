// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.io.IOException;
/** Single-reader burst bound, independent of the official sender's cadence. */
final class PacketRate {
    private final int limit;private long window=-1;private int count;
    PacketRate(int limit){this.limit=limit;}
    void accept(long now)throws IOException{
        if(window<0||now-window>=1000){window=now;count=0;}
        if(now<window||++count>limit)throw new LanFailure.Coded(LanConnection.Error.PROTOCOL);
    }
}
