// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class LocalRecognizerTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static final int RATE=LocalFingerprint.SAMPLE_RATE;

    /** Tonal pseudo-music: chords changing every 250 ms, deterministic per seed. */
    private static short[] song(long seed,int seconds){
        Random random=new Random(seed);
        short[] out=new short[seconds*RATE];
        int block=RATE/4;
        double[] phases=new double[3],freqs=new double[3];
        for(int at=0;at<out.length;at+=block){
            for(int voice=0;voice<3;voice++)freqs[voice]=300+random.nextInt(4200);
            int end=Math.min(out.length,at+block);
            for(int i=at;i<end;i++){
                double sample=0;
                for(int voice=0;voice<3;voice++){
                    phases[voice]+=2*Math.PI*freqs[voice]/RATE;
                    sample+=Math.sin(phases[voice]);
                }
                out[i]=(short)(sample/3*12000);
            }
        }
        return out;
    }
    private static short[] slice(short[] source,int fromMs,int ms,long noiseSeed,double noise){
        Random random=new Random(noiseSeed);
        int from=fromMs*RATE/1000,length=ms*RATE/1000;
        short[] out=new short[length];
        for(int i=0;i<length;i++){
            double value=source[from+i]*0.7+random.nextGaussian()*noise*32768;
            out[i]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,value));
        }
        return out;
    }
    private static LibraryIndex library(){
        LibraryIndex index=new LibraryIndex();
        index.add("media:1","Song One","Artist A",60_000,LocalFingerprint.landmarks(song(1,60)));
        index.add("media:2","Song Two","Artist B",60_000,LocalFingerprint.landmarks(song(2,60)));
        index.add("media:3","Song Three","Artist C",60_000,LocalFingerprint.landmarks(song(3,60)));
        return index;
    }

    @Test public void noisySliceFindsTheRightSongAndMoment(){
        LibraryIndex index=library();
        LocalRecognizer recognizer=new LocalRecognizer(index);
        short[] sample=slice(song(2,60),23_500,4_000,7,0.05);
        Track track=recognizer.recognize(sample,100_000);
        assertNotNull(track);
        assertEquals("local:media:2",track.key);
        assertEquals("Song Two",track.title);
        assertEquals("Artist B",track.artist);
        assertTrue("offset "+track.offsetMs,Math.abs(track.offsetMs-23_500)<=120);
        assertEquals(4_000,track.sampleDurationMs);
        assertTrue(track.hasPosition());
    }

    @Test public void unknownAudioAndSilenceMatchNothing(){
        LocalRecognizer recognizer=new LocalRecognizer(library());
        assertNull(recognizer.recognize(slice(song(99,30),5_000,4_000,8,0.05),0));
        assertNull(recognizer.recognize(new short[4*RATE],0));
        assertNull(recognizer.recognize(new short[100],0));
        assertNull(new LocalRecognizer(new LibraryIndex()).recognize(slice(song(1,30),1_000,3_000,9,0.01),0));
        assertNull(new LocalRecognizer(null).recognize(new short[RATE],0));
    }

    @Test public void indexSurvivesSaveLoadAndSongReplacement()throws Exception{
        LibraryIndex index=library();
        Path file=folder.getRoot().toPath().resolve("library.idx");
        index.save(file);
        LibraryIndex loaded=LibraryIndex.load(file);
        assertEquals(3,loaded.songCount());
        assertEquals(index.landmarkCount(),loaded.landmarkCount());
        assertTrue(loaded.has("media:2"));
        Track track=new LocalRecognizer(loaded).recognize(slice(song(3,60),41_000,4_000,11,0.04),0);
        assertNotNull(track);
        assertEquals("local:media:3",track.key);
        assertTrue(Math.abs(track.offsetMs-41_000)<=120);
        // Re-adding the same media id replaces the old entry instead of duplicating votes.
        loaded.add("media:3","Song Three v2","Artist C",60_000,LocalFingerprint.landmarks(song(3,60)));
        assertEquals(3,loaded.songCount());
        Track replaced=new LocalRecognizer(loaded).recognize(slice(song(3,60),41_000,4_000,11,0.04),0);
        assertNotNull(replaced);
        assertEquals("Song Three v2",replaced.title);
    }

    @Test public void corruptedOrOversizedIndexFileLoadsEmpty()throws Exception{
        Path file=folder.getRoot().toPath().resolve("library.idx");
        java.nio.file.Files.write(file,new byte[]{1,2,3,4,5});
        assertEquals(0,LibraryIndex.load(file).songCount());
        assertEquals(0,LibraryIndex.load(folder.getRoot().toPath().resolve("missing.idx")).songCount());
    }

    @Test public void removingASongRemovesItsVotes(){
        LibraryIndex index=library();
        int songTwo=-1;
        for(LibraryIndex.Song song:index.all())if(song.mediaId.equals("media:2"))songTwo=song.id;
        assertTrue(index.remove(songTwo));
        assertFalse(index.has("media:2"));
        assertNull(new LocalRecognizer(index).recognize(slice(song(2,60),23_500,4_000,7,0.05),0));
        assertNotNull(new LocalRecognizer(index).recognize(slice(song(1,60),10_000,4_000,7,0.05),0));
    }

    @Test public void landmarksAreDeterministicAndBounded(){
        short[] audio=song(5,10);
        List<LocalFingerprint.Landmark> first=LocalFingerprint.landmarks(audio);
        List<LocalFingerprint.Landmark> second=LocalFingerprint.landmarks(audio);
        assertEquals(first.size(),second.size());
        assertTrue("landmarks for 10 s: "+first.size(),first.size()>100&&first.size()<20_000);
        for(LocalFingerprint.Landmark mark:first){
            assertTrue(mark.time>=0);
            assertTrue(mark.hash>=0&&mark.hash<1<<24);
        }
    }
}
