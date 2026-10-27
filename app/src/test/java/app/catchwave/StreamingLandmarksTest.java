// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import org.junit.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.*;

public class StreamingLandmarksTest {
    private static short[] tones(long seed,int seconds){
        Random random=new Random(seed);
        short[] out=new short[seconds*LocalFingerprint.SAMPLE_RATE];
        int block=LocalFingerprint.SAMPLE_RATE/4;
        double[] phases=new double[3],freqs=new double[3];
        for(int at=0;at<out.length;at+=block){
            for(int voice=0;voice<3;voice++)freqs[voice]=300+random.nextInt(4200);
            for(int i=at;i<Math.min(out.length,at+block);i++){
                double sample=0;
                for(int voice=0;voice<3;voice++){phases[voice]+=2*Math.PI*freqs[voice]/LocalFingerprint.SAMPLE_RATE;sample+=Math.sin(phases[voice]);}
                out[i]=(short)(sample/3*12000);
            }
        }
        return out;
    }
    private static Map<Long,Integer> multiset(List<LocalFingerprint.Landmark> marks){
        Map<Long,Integer> out=new HashMap<>();
        for(LocalFingerprint.Landmark mark:marks)out.merge((long)mark.time<<32|mark.hash,1,Integer::sum);
        return out;
    }

    @Test public void chunkedExtractionMatchesWholeFileExtraction(){
        short[] audio=tones(21,20);
        List<LocalFingerprint.Landmark> direct=LocalFingerprint.landmarks(audio);
        StreamingLandmarks stream=new StreamingLandmarks();
        Random random=new Random(5);
        int at=0;
        while(at<audio.length){
            int chunk=Math.min(audio.length-at,1000+random.nextInt(40000));
            short[] part=new short[chunk];
            System.arraycopy(audio,at,part,0,chunk);
            stream.feed(part,chunk);
            at+=chunk;
        }
        List<LocalFingerprint.Landmark> streamed=stream.finish();
        assertFalse(direct.isEmpty());
        assertEquals(multiset(direct),multiset(streamed));
        assertFalse(stream.overflowed());
    }

    @Test public void overflowStopsAtTheFrameCapInsteadOfGrowingForever(){
        StreamingLandmarks stream=new StreamingLandmarks();
        short[] block=tones(3,10);
        // Pretend an absurdly long file by feeding far past the cap; extraction must stop, not OOM.
        int cap=(LibraryIndex.MAX_SONG_FRAMES+100)*LocalFingerprint.HOP;
        int fed=0;
        while(fed<cap+LocalFingerprint.SAMPLE_RATE*30&&!stream.overflowed()){stream.feed(block,block.length);fed+=block.length;}
        assertTrue(stream.overflowed());
        List<LocalFingerprint.Landmark> marks=stream.finish();
        for(LocalFingerprint.Landmark mark:marks)assertTrue(mark.time<=LibraryIndex.MAX_SONG_FRAMES);
    }

    @Test public void threeSecondSampleStillMatchesIndexedSong(){
        LibraryIndex index=new LibraryIndex();
        index.add("media:1","Song","Artist",60_000,LocalFingerprint.landmarks(tones(1,60)));
        index.add("media:2","Other","Artist",60_000,LocalFingerprint.landmarks(tones(2,60)));
        short[] source=tones(1,60);
        short[] sample=new short[3*LocalFingerprint.SAMPLE_RATE];
        Random random=new Random(9);
        int from=37_000*LocalFingerprint.SAMPLE_RATE/1000;
        for(int i=0;i<sample.length;i++){
            double value=source[from+i]*0.7+random.nextGaussian()*0.05*32768;
            sample[i]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,value));
        }
        Track track=new LocalRecognizer(index).recognize(sample,0);
        assertNotNull(track);
        assertEquals("local:media:1",track.key);
        assertTrue("offset "+track.offsetMs,Math.abs(track.offsetMs-37_000)<=120);
    }
}
