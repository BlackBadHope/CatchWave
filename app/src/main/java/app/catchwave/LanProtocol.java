// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** CatchWave LAN v1. TCP/TLS preserves order; never replay a backlog after a stall. */
public final class LanProtocol {
    public static final int AUTH=0, FORMAT=1, AUDIO=2, COMMAND=3, ACK=4, POLICY=5, CLIENT_STATE=6;
    public static final int MAX_PACKET=32768, MAX_AUDIO=16384;
    public static final class Packet {
        public final int kind; public final byte[] body;
        Packet(int kind,byte[] body){this.kind=kind;this.body=body;}
    }
    public static Packet read(DataInputStream input)throws IOException {
        int kind=input.readUnsignedByte(),length=input.readInt();
        if(kind>CLIENT_STATE||length<0||length>MAX_PACKET)throw new IOException("Invalid frame");
        byte[] body=new byte[length];input.readFully(body);return new Packet(kind,body);
    }
    public static void write(DataOutputStream output,int kind,byte[] body)throws IOException {
        if(kind<0||kind>CLIENT_STATE||body.length>MAX_PACKET)throw new IOException("Invalid frame");
        output.writeByte(kind);output.writeInt(body.length);output.write(body);output.flush();
    }
    public static final class Format {
        public final byte[] session;public final int rate,channels,frameSamples;
        public Format(byte[] body)throws IOException {
            if(body.length!=24)throw new IOException("Invalid format");
            ByteBuffer b=ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            session=new byte[16];b.get(session);rate=b.getInt();channels=b.get()&255;
            int bits=b.get()&255;frameSamples=b.getShort()&65535;
            if((rate!=44100&&rate!=48000&&rate!=96000)||channels<1||channels>2||bits!=16||frameSamples!=rate/50)
                throw new IOException("Unsupported PCM format");
        }
        public int bytes(){return frameSamples*channels*2;}
    }
    public static byte[] command(byte[] session,long sequence,long observedAudio,int action){
        return command(session,sequence,observedAudio,action,0);
    }
    public static byte[] command(byte[] session,long sequence,long observedAudio,int action,long policyGeneration){return ByteBuffer.allocate(41).put(session).putLong(sequence).putLong(observedAudio).put((byte)action).putLong(policyGeneration).array();}
    interface PolicyReceiver {void receive(byte[] body)throws IOException;}
    static Packet firstAudio(DataInputStream input,PolicyReceiver policy)throws IOException {
        for(int i=0;i<16;i++){Packet packet=read(input);if(packet.kind==POLICY){policy.receive(packet.body);continue;}if(packet.kind!=AUDIO)throw new IOException("Expected audio");return packet;}
        throw new IOException("Policy flood before audio");
    }
    /** Receiver gate: duplicate/out-of-order/time-regressing or malformed audio is fatal. */
    public static final class AudioGate {
        private long sequence=-1,time=-1;
        public boolean accept(byte[] body,int pcmBytes)throws IOException {
            if(body.length!=16+pcmBytes)throw new IOException("Invalid PCM frame");
            ByteBuffer b=ByteBuffer.wrap(body);long next=b.getLong(),stamp=b.getLong();
            if(next<0||stamp<0||next<=sequence||stamp<=time)throw new IOException("Stale audio frame");
            boolean gap=sequence>=0&&next!=sequence+1;sequence=next;time=stamp;return gap;
        }
        public long sequence(){return sequence;}
    }
    /** Sink contract includes short writes; neither skip bytes nor spin on a zero write. */
    public interface Sink {int write(byte[] bytes,int offset,int count)throws IOException;}
    public static void writeFully(Sink sink,byte[] bytes)throws IOException {
        int offset=0;while(offset<bytes.length){
            int n=sink.write(bytes,offset,bytes.length-offset);
            if(n<=0||n>bytes.length-offset)throw new IOException("PCM output stopped");offset+=n;
        }
    }
    private LanProtocol(){}
}
