// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.security.MessageDigest;

/** A replaced receiver and an explicit stop invalidate every queued transport callback. */
final class TransportEpoch {
    private volatile long generation;
    long next(){return ++generation;}
    boolean current(long value,boolean resume){return value==generation&&resume;}
    static boolean sameHost(LanSettings saved,PairingCode usb){
        return usb!=null&&saved.paired()&&saved.host().equals(usb.host)&&saved.port()==usb.port
            &&MessageDigest.isEqual(saved.pin(),usb.pin)&&MessageDigest.isEqual(saved.token(),usb.token);
    }
}
