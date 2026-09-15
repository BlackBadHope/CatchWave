package app.catchwave;

/** MediaSession seek timing. Does not measure speaker latency or claim audible milliseconds. */
final class SeekClock {
    static final long SETTLE_MS=200;
    static final long ACK_MS=400;
    static final long LAG_MAX_MS=1500;
    /** YouTube Music seekTo is a timeline position. Callback delay is not added to the target: adding it overshoots and then pauses. */
    static long command(long tEst,long learnedLagMs){
        return tEst;
    }
    static long delta(long tEst,long tSession){return tEst-tSession;}
    static long lag(long seekSentAt,long sessionUpdateAt){
        if(sessionUpdateAt<seekSentAt)return -1;
        return Math.min(LAG_MAX_MS,sessionUpdateAt-seekSentAt);
    }
    static long blend(long previous,long measured){
        if(measured<0)return previous;
        if(previous<0)return measured;
        return (previous+measured)/2;
    }
    static boolean settled(long now,long seekSentAt,long sessionUpdateAt,long tSession,long commanded){
        if(now-seekSentAt<SETTLE_MS||sessionUpdateAt<seekSentAt||tSession<0)return false;
        long expected=commanded+Math.max(0,now-sessionUpdateAt);
        return Math.abs(tSession-expected)<=ACK_MS;
    }
}
