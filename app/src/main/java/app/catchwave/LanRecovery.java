// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** A short recovery episode, never an always-on reconnect loop. Owner stop invalidates it. */
final class LanRecovery {
    static final long WINDOW_MS=20000;
    private long deadline=-1;private int failures;
    void begin(long now){deadline=now+WINDOW_MS;failures=0;}
    void stop(){deadline=-1;failures=0;}
    boolean active(){return deadline>=0;}
    long retry(LanConnection.Error error,long now){
        if(!active()||now>=deadline||!recoverable(error)||failures>=3)return -1;
        return new long[]{250,750,1500}[failures++];
    }
    static boolean recoverable(LanConnection.Error error){return switch(error){
        case NETWORK,CONNECT_TIMEOUT,CONNECT_REFUSED,HOST_CLOSED,HOST_TIMEOUT,AUTH_REJECTED_OR_CLOSED -> true;
        default -> false;
    };}
}
