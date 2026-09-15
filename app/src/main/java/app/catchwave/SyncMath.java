package app.catchwave;

public final class SyncMath {
    public static final long TOLERANCE_MS=150;
    public static long captureAnchor(long timestampNs,long framePosition,long samples,int window){
        return Math.round(timestampNs/1_000_000.0+(samples-window-framePosition)/16.0);
    }
    public static long recognitionInterval(boolean tracking){return tracking?3000:2500;}
    public static long recognitionInterval(boolean tracking,int windowSamples){
        return Math.max(recognitionInterval(tracking),windowSamples<=0?0:windowSamples/16);
    }
    public static long timelineDifference(Track a,Track b){return (a.offsetMs-a.anchorMs)-(b.offsetMs-b.anchorMs);}
    public static int captureWindow(long samples,boolean firstRequest) {
        int needed=firstRequest?48000:96000;
        return samples>=needed?needed:0;
    }
    /** Reject a window that is quiet in any third: loop splices and fade-ins poison Shazam offset/timeskew. */
    public static boolean stableFragment(short[] sample){
        if(sample==null||sample.length<48000)return false;
        int part=sample.length/3;
        for(int p=0;p<3;p++){
            int from=p*part,to=p==2?sample.length:from+part;
            double sum=0;for(int i=from;i<to;i++)sum+=(double)sample[i]*sample[i];
            if(Math.sqrt(sum/(to-from))/32768<0.002)return false;
        }
        return true;
    }
    public static long playerPosition(long position,long updateTime,float speed,long now,boolean playing) {
        if(position<0 || playing&&(updateTime<=0||updateTime>now+2000||!Float.isFinite(speed)))return -1;
        return position+(playing?Math.round(Math.max(0,now-updateTime)*speed):0);
    }
    public static boolean shouldSeek(long delta,long sinceLastSeek,int attempts) {
        return Math.abs(delta)>TOLERANCE_MS && sinceLastSeek>=1500 && attempts<3;
    }
}
