// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import java.util.ArrayList;
import java.util.List;

/**
 * Landmark hashes for the on-phone library matcher. Classic peak-pair fingerprinting:
 * nothing here resembles or produces a Shazam signature, and nothing leaves the device.
 * Input is 16 kHz mono PCM16; time is measured in hops of 32 ms.
 */
final class LocalFingerprint {
    static final int SAMPLE_RATE=16000,WINDOW=1024,HOP=512;
    static final int HOP_MS=HOP*1000/SAMPLE_RATE;
    static final int MIN_BIN=16,MAX_BIN=480; // ~250–7500 Hz: ignore rumble and the fragile top band.
    private static final int PEAKS_PER_FRAME=5,FAN_OUT=3,MIN_DT=3,MAX_DT=48,MAX_DF=127;
    private static final double[] HANN=new double[WINDOW];
    static { for(int i=0;i<WINDOW;i++)HANN[i]=0.5-0.5*Math.cos(2*Math.PI*i/(WINDOW-1)); }

    /** One landmark: when it starts (hop index) and its packed 24-bit hash. */
    static final class Landmark {
        final int time,hash;
        Landmark(int time,int hash){this.time=time;this.hash=hash;}
    }

    private LocalFingerprint(){}

    static List<Landmark> landmarks(short[] audio){
        return landmarks(audio,0,audio==null?0:audio.length);
    }
    static List<Landmark> landmarks(short[] audio,int from,int length){
        List<Landmark> result=new ArrayList<>();
        if(audio==null||length<WINDOW)return result;
        int frames=(length-WINDOW)/HOP+1;
        int[][] peaks=new int[frames][];
        float[][] magnitudes=new float[frames][];
        double[] re=new double[WINDOW],im=new double[WINDOW];
        for(int frame=0;frame<frames;frame++){
            int base=from+frame*HOP;
            for(int i=0;i<WINDOW;i++){re[i]=audio[base+i]*HANN[i];im[i]=0;}
            Fingerprint.transform(re,im);
            float[] mag=new float[WINDOW/2];
            for(int i=0;i<WINDOW/2;i++)mag[i]=(float)(re[i]*re[i]+im[i]*im[i]);
            peaks[frame]=framePeaks(mag);
            magnitudes[frame]=mag;
        }
        for(int frame=0;frame<frames;frame++){
            for(int anchor:peaks[frame]){
                int paired=0;
                for(int ahead=MIN_DT;ahead<=MAX_DT&&frame+ahead<frames&&paired<FAN_OUT;ahead++){
                    for(int target:peaks[frame+ahead]){
                        int df=target-anchor;
                        if(df<-MAX_DF||df>MAX_DF)continue;
                        result.add(new Landmark(frame,hash(anchor,df,ahead)));
                        if(++paired>=FAN_OUT)break;
                    }
                }
            }
        }
        return result;
    }

    /** Local maxima above the frame's adaptive floor; the strongest few carry the voice of the frame. */
    private static int[] framePeaks(float[] mag){
        double mean=0;int counted=0;
        for(int i=MIN_BIN;i<=MAX_BIN;i++){mean+=mag[i];counted++;}
        mean/=counted;
        double floor=mean*4;
        int[] best=new int[PEAKS_PER_FRAME];float[] bestMag=new float[PEAKS_PER_FRAME];int found=0;
        for(int i=MIN_BIN;i<=MAX_BIN;i++){
            float v=mag[i];
            if(v<floor||v<=mag[i-1]||v<mag[i+1]||i>=2&&v<=mag[i-2]||i+2<mag.length&&v<mag[i+2])continue;
            int weakest=-1;float weakestMag=Float.MAX_VALUE;
            for(int slot=0;slot<PEAKS_PER_FRAME;slot++){
                if(slot>=found){weakest=slot;weakestMag=-1;break;}
                if(bestMag[slot]<weakestMag){weakestMag=bestMag[slot];weakest=slot;}
            }
            if(weakestMag<0){best[weakest]=i;bestMag[weakest]=v;found++;}
            else if(v>weakestMag){best[weakest]=i;bestMag[weakest]=v;}
        }
        int[] out=new int[found];
        System.arraycopy(best,0,out,0,found);
        java.util.Arrays.sort(out);
        return out;
    }

    static int hash(int bin,int df,int dt){
        return (bin&0x1FF)<<15|(df+MAX_DF+1&0xFF)<<7|dt&0x7F;
    }
}
