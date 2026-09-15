package app.catchwave;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
import java.nio.*;
import java.util.Base64;
import java.util.zip.CRC32;

public class CoreTest {
    @Test public void microphoneAnchorUsesFrameClockNotReadCompletion(){assertEquals(97000,SyncMath.captureAnchor(100_000_000_000L,49600,49600,48000));assertEquals(96900,SyncMath.captureAnchor(100_000_000_000L,51200,49600,48000));}
    @Test public void liveUsesThreeSecondCadenceAndComparesTimelines(){assertEquals(3000,SyncMath.recognitionInterval(true));Track a=new Track("1","x","y","",10000,1000,0),b=new Track("1","x","y","",13500,4000,0);assertEquals(500,SyncMath.timelineDifference(b,a));}
    @Test public void extendedMixIsNotTheOriginalRecording(){Track t=new Track("1","Grass Skirt Chase","Scatta","",1,0,0);assertFalse(t.matches("Grass Skirt Chase Extended 5 minutes Mix","Scatta"));}
    @Test public void firstCaptureUsesThreeSecondsAndRetryUsesSix(){assertEquals(0,SyncMath.captureWindow(47999,true));assertEquals(48000,SyncMath.captureWindow(48000,true));assertEquals(0,SyncMath.captureWindow(95999,false));assertEquals(96000,SyncMath.captureWindow(96000,false));}
    @Test public void residualThirdSecondNeedsAnotherCorrection(){assertTrue(SyncMath.shouldSeek(300,1500,1));assertFalse(SyncMath.shouldSeek(100,1500,1));assertFalse(SyncMath.shouldSeek(300,1000,1));}
    @Test public void seekCommandIsTimelinePositionNotCallbackLag(){
        assertEquals(10000,SeekClock.command(10000,-1));
        assertEquals(10000,SeekClock.command(10000,248));
        assertEquals(10000,SeekClock.command(10000,180));
    }
    @Test public void uncompensatedSeekLagEqualsDeltaAfterSettle(){
        long tEstSend=10000,lag=180;
        assertEquals(10000,SeekClock.command(tEstSend,-1));
        assertEquals(180,SeekClock.delta(tEstSend+lag,tEstSend));
        assertEquals(180,SeekClock.lag(1000,1180));
        assertEquals(180,SeekClock.blend(-1,180));
        assertEquals(120,SeekClock.blend(180,60));
        assertTrue(SeekClock.settled(1400,1000,1180,10000+220,10000));
        assertFalse(SeekClock.settled(1100,1000,1180,10000,10000));
    }
    @Test public void sixSecondWindowsDoNotOverlap(){
        assertEquals(6000,SyncMath.recognitionInterval(false,96000));
        assertEquals(3000,SyncMath.recognitionInterval(true,48000));
    }
    @Test public void recognitionTimeAndLaunchTimeAreIncluded(){Track t=new Track("1","Song","Artist","",83400,10000,0);assertEquals(88600,t.positionAt(15200,0));assertEquals(88800,t.positionAt(15200,200));}
    @Test public void sourceBeginningCannotSeekBeforeZero(){Track t=new Track("1","Song","Artist","",-1000,10000,0);assertEquals(0,t.positionAt(10200,0));}
    @Test public void metadataMustMatchArtistAndRecordingVersion(){Track t=new Track("1","Hello","Adele","",1,0,0);assertTrue(t.matches("Hello (Official Audio)","Adele - Topic"));assertFalse(t.matches("Hello","Lionel Richie"));assertFalse(t.matches("Hello (Live)","Adele"));assertFalse(t.matches("Hello (Slowed)","Adele"));assertFalse(t.matches("",""));}
    @Test public void unknownOffsetAndSpeedChangesAreNotSynchronizable(){Track unknown=new Track("1","x","y","",Long.MIN_VALUE,0,0);assertFalse(unknown.hasPosition());assertFalse(unknown.hasOffset());assertTrue(new Track("1","x","y","",1000,0,.1).hasOffset());assertTrue(new Track("1","x","y","",1000,0,.1).hasPosition());}
    @Test public void quietThirdOfWindowIsNotAStableFragment(){
        short[] loud=new short[48000];for(int i=0;i<loud.length;i++)loud[i]=(short)(8000*Math.sin(2*Math.PI*440*i/16000));
        assertTrue(SyncMath.stableFragment(loud));
        short[] spliced=loud.clone();for(int i=0;i<16000;i++)spliced[i]=0;
        assertFalse(SyncMath.stableFragment(spliced));
        assertFalse(SyncMath.stableFragment(new short[47999]));
    }
    @Test public void playerClockAccountsForTimestampAndRate(){assertEquals(7000,SyncMath.playerPosition(3000,1000,1,5000,true));assertEquals(3000,SyncMath.playerPosition(3000,1000,1,5000,false));assertEquals(5000,SyncMath.playerPosition(3000,1000,.5f,5000,true));assertEquals(-1,SyncMath.playerPosition(-1,1000,1,5000,true));}
    @Test public void correctionsAreBoundedAndHaveDeadband(){assertFalse(SyncMath.shouldSeek(120,6000,1));assertFalse(SyncMath.shouldSeek(900,1000,1));assertFalse(SyncMath.shouldSeek(900,6000,3));assertTrue(SyncMath.shouldSeek(-900,6000,1));}
    @Test public void responseCarriesOffsetAndCatalogId() throws Exception {Track t=RecognitionClient.parse(new JSONObject("{\"matches\":[{\"offset\":83.25}],\"track\":{\"key\":\"123\",\"title\":\"Hello\",\"subtitle\":\"Adele\",\"hub\":{\"actions\":[{\"id\":\"123456\"}]}}}"),9000);assertNotNull(t);assertEquals(83250,t.offsetMs);assertEquals("123456",t.appleId);assertEquals(9000,t.anchorMs);}
    @Test public void noMatchIsNotAnInventedTrack() throws Exception {assertNull(RecognitionClient.parse(new JSONObject("{\"matches\":[]}"),0));Track t=RecognitionClient.parse(new JSONObject("{\"matches\":[{}],\"track\":{\"key\":\"1\"}}"),0);assertNull(t);}
    @Test public void onlyYoutubeMusicWatchLinksAreOpened(){assertTrue(RecognitionClient.validMusicUrl("https://music.youtube.com/watch?v=4ujBQOzs6Lw"));assertFalse(RecognitionClient.validMusicUrl("https://music.youtube.com.evil.test/watch?v=4ujBQOzs6Lw"));assertFalse(RecognitionClient.validMusicUrl("javascript:alert(1)"));assertFalse(RecognitionClient.validMusicUrl("https://music.youtube.com/watch?v=short"));}
    @Test public void shazamAppIsNotRequiredYoutubeMusicIsOnlyThePlayer(){
        assertFalse(RecognitionPath.requiresShazamApp());
        assertTrue(RecognitionPath.requiresYoutubeMusicApp());
        assertEquals("com.google.android.apps.youtube.music",MediaBridge.PACKAGE);
        assertFalse(MediaBridge.PACKAGE.equals(RecognitionPath.SHAZAM_APP));
        assertTrue(RecognitionPath.explanation().contains("Приложение Shazam не нужно"));
        assertTrue(RecognitionPath.explanation().contains("YouTube Music — выбранный плеер"));
        assertTrue(Privacy.notice().contains("не приложение Shazam"));
    }
    @Test public void signatureHasCorrectLengthChecksumAndSampleRate(){byte[] data=Base64.getDecoder().decode(Fingerprint.generate(new short[96000]).split(",",2)[1]);ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(0xcafe2580,b.getInt());assertEquals(data.length-48,b.getInt(8));assertEquals(3<<27,b.getInt(28));assertEquals(99840,b.getInt(40));CRC32 crc=new CRC32();crc.update(data,8,data.length-8);assertEquals((int)crc.getValue(),b.getInt(4));}
}
