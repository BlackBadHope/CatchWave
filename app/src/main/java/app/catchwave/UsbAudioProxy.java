// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native accessory bytes; loopback only adapts the existing pinned-TLS receiver on this phone. */
final class UsbAudioProxy implements AutoCloseable {
    private final UsbManager manager;private final UsbAccessory accessory;
    private final AtomicBoolean closed=new AtomicBoolean();
    private ParcelFileDescriptor inputFd,outputFd;private ServerSocket server;private Socket peer;
    private InputStream input;private OutputStream output;
    private PairingCode pairing;private int port;
    UsbAudioProxy(Context context,UsbAccessory accessory){manager=context.getSystemService(UsbManager.class);this.accessory=accessory;}
    static boolean matches(UsbAccessory a){return a!=null&&"CatchWave".equals(a.getManufacturer())&&"CatchWave Host".equals(a.getModel())&&"1".equals(a.getVersion());}
    boolean present(){UsbAccessory[] list=manager.getAccessoryList();return list!=null&&Arrays.asList(list).contains(accessory);}
    void open()throws IOException {
        if(closed.get())throw new IOException("USB_CANCELLED");
        if(!matches(accessory)||!present()||!manager.hasPermission(accessory))throw new IOException("USB_PERMISSION_REQUIRED");
        synchronized(this){
            if(closed.get())throw new IOException("USB_CANCELLED");
            if(Build.VERSION.SDK_INT>=36){
                input=new UsbPacketInput(manager.openAccessoryInputStream(accessory));
                output=manager.openAccessoryOutputStream(accessory);
                DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"USB_STREAM_API_36");
            }else{
                ParcelFileDescriptor opened=manager.openAccessory(accessory);if(opened==null)throw new IOException("USB_OPEN_FAILED");
                inputFd=opened;outputFd=ParcelFileDescriptor.dup(opened.getFileDescriptor());
                // Drain an entire accessory transfer before parsing its bytes.
                input=new UsbPacketInput(new ParcelFileDescriptor.AutoCloseInputStream(inputFd));
                output=new ParcelFileDescriptor.AutoCloseOutputStream(outputFd);
            }
        }
        pairing=readPairing(input);
        ServerSocket openedServer=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        synchronized(this){if(closed.get()){openedServer.close();throw new IOException("USB_CANCELLED");}server=openedServer;port=server.getLocalPort();}
        output.write("CWUSB1OK\n".getBytes(StandardCharsets.US_ASCII));output.flush();
        new Thread(()->{
            try {
                Socket accepted=openedServer.accept();accepted.setTcpNoDelay(true);
                synchronized(this){if(closed.get()){accepted.close();return;}peer=accepted;}
                InputStream outbound=accepted.getInputStream();
                new Thread(()->pump(outbound,output,true),"CatchWave-USB-out").start();
                pump(input,accepted.getOutputStream(),false);
            }catch(IOException ignored){close();}
        },"CatchWave-USB-in").start();
    }
    static PairingCode readPairing(InputStream input)throws IOException{
        byte[] magic=new byte[7];DataInputStream bootstrap=new DataInputStream(input);bootstrap.readFully(magic);
        if(!Arrays.equals(magic,"CWUSB1\n".getBytes(StandardCharsets.US_ASCII)))throw new IOException("USB_BOOTSTRAP_INVALID");
        int length=bootstrap.readUnsignedShort();if(length<1||length>256)throw new IOException("USB_BOOTSTRAP_INVALID");
        byte[] record=new byte[length];bootstrap.readFully(record);
        try{return PairingCode.parse(new String(record,StandardCharsets.US_ASCII));}catch(IllegalArgumentException invalid){throw new IOException("USB_BOOTSTRAP_INVALID",invalid);}
        finally{Arrays.fill(record,(byte)0);}
    }
    PairingCode pairing(){return pairing;}
    PairingCode localPairing(){return pairing.loopback(port);}
    private void pump(InputStream from,OutputStream to,boolean outbound){
        boolean first=true;byte[] bytes=new byte[16384];try{while(!closed.get()){int n=from.read(bytes);if(n<0)break;
            if(first){first=false;boolean tls=n>=3&&(bytes[0]==22||bytes[0]==21)&&bytes[1]==3&&bytes[2]>=0&&bytes[2]<=3;
                DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,outbound?(tls?"USB_TX_TLS":"USB_TX_NOT_TLS"):(tls?"USB_RX_TLS":"USB_RX_NOT_TLS"),n);}
            to.write(bytes,0,n);to.flush();}}catch(IOException failure){if(!closed.get()){int errno=0;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof android.system.ErrnoException system){errno=system.errno;break;}DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,outbound?"USB_TX_IO_CLOSED":"USB_RX_IO_CLOSED",errno);}}
        finally{Arrays.fill(bytes,(byte)0);close();}
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;
        DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"USB_PIPE_CLOSED");
        // File and socket teardown stays off the Android main thread, including cancellation before setup finishes.
        new Thread(()->{synchronized(this){quietly(peer);quietly(server);quietly(inputFd);quietly(outputFd);quietly(input);quietly(output);}},"CatchWave-USB-close").start();
    }
    private static void quietly(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){}}
}
