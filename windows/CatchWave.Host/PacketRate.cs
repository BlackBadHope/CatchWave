// SPDX-License-Identifier: GPL-3.0-or-later
namespace CatchWave.Host;
internal sealed class PacketRate(int limit) {
    private long window=-1;private int count;
    internal void Accept(long now){
        if(window<0||now-window>=1000){window=now;count=0;}
        if(now<window||++count>limit)throw new IOException("Peer packet rate exceeded");
    }
}
