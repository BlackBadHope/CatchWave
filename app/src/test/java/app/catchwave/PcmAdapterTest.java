// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PcmAdapterTest {
    private static short[] stereoSine(int rate,double hz,int frames,double phaseOffsetRight){
        short[] out=new short[frames*2];
        for(int i=0;i<frames;i++){
            out[i*2]=(short)(Math.sin(2*Math.PI*hz*i/rate)*12000);
            out[i*2+1]=(short)(Math.sin(2*Math.PI*hz*i/rate+phaseOffsetRight)*12000);
        }
        return out;
    }
    private static short[] convertAll(PcmAdapter adapter,short[] input,int chunkShorts){
        List<Short> collected=new ArrayList<>();
        short[] out=new short[adapter.outputCapacity(chunkShorts)+8];
        for(int at=0;at<input.length;at+=chunkShorts){
            int length=Math.min(chunkShorts,input.length-at);
            short[] chunk=new short[length];
            System.arraycopy(input,at,chunk,0,length);
            int produced=adapter.convert(chunk,length,out);
            for(int i=0;i<produced;i++)collected.add(out[i]);
        }
        short[] result=new short[collected.size()];
        for(int i=0;i<result.length;i++)result[i]=collected.get(i);
        return result;
    }
    private static double dominantHz(short[] mono16k){
        int n=2048;
        double[] re=new double[n],im=new double[n];
        for(int i=0;i<n;i++)re[i]=mono16k[1000+i]*(0.5-0.5*Math.cos(2*Math.PI*i/(n-1)));
        Fingerprint.transform(re,im);
        int best=1;double bestMag=0;
        for(int i=1;i<n/2;i++){
            double mag=re[i]*re[i]+im[i]*im[i];
            if(mag>bestMag){bestMag=mag;best=i;}
        }
        return best*16000.0/n;
    }

    @Test public void stereo48kSineBecomesCorrectPitch16kMono(){
        short[] input=stereoSine(48000,440,48000,0);
        PcmAdapter adapter=new PcmAdapter(48000,2);
        short[] mono=convertAll(adapter,input,9600);
        assertTrue("length "+mono.length,Math.abs(mono.length-16000)<=4);
        assertEquals(440,dominantHz(mono),16000.0/2048+1);
    }

    @Test public void chunkingDoesNotChangeTheOutput(){
        short[] input=stereoSine(44100,523,44100,0.4);
        short[] single=convertAll(new PcmAdapter(44100,2),input,input.length);
        short[] chunked=convertAll(new PcmAdapter(44100,2),input,1234);
        assertEquals(single.length,chunked.length);
        // Rebasing the fractional position at chunk edges may shift rounding by one LSB; that is inaudible and irrelevant to fingerprinting.
        for(int i=0;i<single.length;i++)
            assertTrue("sample "+i+": "+single[i]+" vs "+chunked[i],Math.abs(single[i]-chunked[i])<=1);
    }

    @Test public void monoUpsamplingAndFormatBoundsAreHandled(){
        short[] input=new short[8000];
        for(int i=0;i<input.length;i++)input[i]=(short)(Math.sin(2*Math.PI*300*i/8000)*9000);
        short[] mono=convertAll(new PcmAdapter(8000,1),input,800);
        assertTrue(Math.abs(mono.length-16000)<=4);
        assertEquals(300,dominantHz(mono),16000.0/2048+1);
        for(int[] bad:new int[][]{{1000,2},{48000,0},{48000,9},{400000,2}}){
            try{new PcmAdapter(bad[0],bad[1]);fail("accepted "+bad[0]+"/"+bad[1]);}catch(IllegalArgumentException expected){}
        }
    }
}
