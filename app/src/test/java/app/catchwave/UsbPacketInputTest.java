// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.io.*;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class UsbPacketInputTest {
    private static InputStream characterDevice(byte[] bytes){return new InputStream(){int at;
        public int read(){throw new AssertionError("Single-byte raw USB read drops a packet tail");}
        public int read(byte[] out,int off,int length){if(at==bytes.length)return -1;assertTrue(length>=16384);int n=Math.min(149,bytes.length-at);System.arraycopy(bytes,at,out,off,n);at+=n;return n;}
        public int available()throws IOException{throw new IOException("Unsupported USB character ioctl");}
    };}
    @Test public void standardBufferedStreamFailsOnShortUsbTransfer()throws Exception {
        var old=new BufferedInputStream(characterDevice(new byte[149]),16384);
        try{old.read(new byte[16384]);fail("Old buffer must reproduce unsupported available ioctl");}catch(IOException expected){assertEquals("Unsupported USB character ioctl",expected.getMessage());}
    }
    @Test public void packetReaderPreservesTailsAndNeverUsesCharacterIoctl()throws Exception {
        byte[] bytes=new byte[20000];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        try(var fixed=new UsbPacketInput(characterDevice(bytes))){byte[] actual=new byte[bytes.length];new DataInputStream(fixed).readFully(actual,0,7);new DataInputStream(fixed).readFully(actual,7,actual.length-7);assertArrayEquals(bytes,actual);assertEquals(-1,fixed.read());}
    }
}
