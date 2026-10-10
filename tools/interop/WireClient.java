// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** JVM/Schannel test only: credentials are a separate loopback test identity passed via stdin, never owner QR. */
public final class WireClient {
    public static void main(String[] args){try{run();System.out.println("PASS: JVM TLS/pin/auth/format/policy + startup delay/backlog + synthetic PCM + locked command ACK");}catch(Exception e){System.err.println("FAIL: interop category="+e.getClass().getSimpleName());System.exit(1);}}
    private static void require(boolean ok)throws IOException{if(!ok)throw new IOException("Test assertion failed");}
    private static void run()throws Exception{
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in));
        int port=Integer.parseInt(input.readLine());byte[] pin=Base64.getDecoder().decode(input.readLine()),token=Base64.getDecoder().decode(input.readLine());
        X509TrustManager trust=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{try{require(c.length==1);c[0].checkValidity();require(MessageDigest.isEqual(pin,MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded())));}catch(Exception e){throw new CertificateException(e);}}
        };
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{trust},null);
        try(SSLSocket socket=(SSLSocket)context.getSocketFactory().createSocket()){
            socket.connect(new InetSocketAddress("127.0.0.1",port),2000);socket.setSoTimeout(2000);socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.startHandshake();
            DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());LanProtocol.write(out,LanProtocol.AUTH,token);
            LanProtocol.Packet p=LanProtocol.read(in);require(p.kind==LanProtocol.FORMAT);LanProtocol.Format format=new LanProtocol.Format(p.body);require(format.rate==48000&&format.channels==2);
            p=LanProtocol.read(in);require(p.kind==LanProtocol.POLICY&&p.body.length==9&&p.body[0]==0);
            p=LanProtocol.firstAudio(in,body->require(body.length==9));LanProtocol.AudioGate gate=new LanProtocol.AudioGate();gate.accept(p.body,format.bytes());
            long began=System.nanoTime()/1000000;LanTimeline timeline=new LanTimeline(ByteBuffer.wrap(p.body,8,8).getLong(),began);LanFreshStream fresh=new LanFreshStream();fresh.received(began);
            ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(8);queue.offer(p.body);AtomicInteger dropped=new AtomicInteger();AtomicBoolean running=new AtomicBoolean(true);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<LanProtocol.Packet> ack=new AtomicReference<>();
            Thread reader=new Thread(()->{try{while(running.get()){
                LanProtocol.Packet packet=LanProtocol.read(in);
                if(packet.kind==LanProtocol.AUDIO){gate.accept(packet.body,format.bytes());fresh.received(System.nanoTime()/1000000);if(!queue.offer(packet.body)){queue.poll();queue.offer(packet.body);dropped.incrementAndGet();}}
                else if(packet.kind==LanProtocol.ACK)ack.set(packet);else require(packet.kind==LanProtocol.POLICY&&packet.body.length==9);
            }}catch(Exception e){if(running.get())failure.set(e);}});reader.start();
            try {
                Thread.sleep(800);require(fresh.fresh(System.nanoTime()/1000000));require(dropped.get()>=25); // same freshness gate used by Android's delayed main focus callback.
                long deadline=began+3000;byte[] frame;
                do{require(System.nanoTime()/1000000<deadline);frame=queue.poll(1000,TimeUnit.MILLISECONDS);require(frame!=null);}while(!timeline.startupFresh(ByteBuffer.wrap(frame,8,8).getLong(),System.nanoTime()/1000000));
                timeline.arm(ByteBuffer.wrap(frame,8,8).getLong(),System.nanoTime()/1000000);
                for(int i=0;i<20;i++){
                    frame=queue.poll(1000,TimeUnit.MILLISECONDS);require(frame!=null);require(!timeline.stalled(ByteBuffer.wrap(frame,8,8).getLong(),System.nanoTime()/1000000));
                    for(int j=16;j<frame.length;j++)require(frame[j]==0);
                }
                LanProtocol.write(out,LanProtocol.CLIENT_STATE,ByteBuffer.allocate(13).put(new byte[]{1,1,1,0,0}).putLong(0).array());
                LanProtocol.write(out,LanProtocol.COMMAND,LanProtocol.command(format.session,1,ByteBuffer.wrap(frame).getLong(),1,0));
                deadline=System.nanoTime()/1000000+2000;while(ack.get()==null&&System.nanoTime()/1000000<deadline)Thread.sleep(10);
                require(ack.get()!=null&&ack.get().body.length==9&&ack.get().body[8]==0&&failure.get()==null);
            }finally{running.set(false);socket.close();reader.join(2000);require(!reader.isAlive());}

        }finally{java.util.Arrays.fill(token,(byte)0);}
    }
}
