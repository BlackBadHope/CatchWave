// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.io.*;
import java.util.Arrays;
import java.util.Objects;

/** Accessory transfers are packets. Never query available() on the USB character descriptor. */
final class UsbPacketInput extends InputStream {
    private final InputStream source;private final byte[] packet=new byte[16384];
    private int position,limit;private boolean closed;
    UsbPacketInput(InputStream source){this.source=Objects.requireNonNull(source);}
    @Override public int read()throws IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
    @Override public int read(byte[] bytes,int offset,int length)throws IOException {
        Objects.checkFromIndexSize(offset,length,bytes.length);if(closed)throw new IOException("USB stream closed");if(length==0)return 0;
        if(position==limit){int n=source.read(packet,0,packet.length);if(n<0)return -1;if(n==0)throw new IOException("Empty USB transfer");position=0;limit=n;}
        int n=Math.min(length,limit-position);System.arraycopy(packet,position,bytes,offset,n);Arrays.fill(packet,position,position+n,(byte)0);position+=n;return n;
    }
    @Override public int available(){return limit-position;}
    @Override public void close()throws IOException {closed=true;source.close();Arrays.fill(packet,(byte)0);position=limit=0;}
}
