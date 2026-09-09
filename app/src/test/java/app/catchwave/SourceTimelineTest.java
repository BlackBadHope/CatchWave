package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceTimelineTest {
    private static Track sample(long anchor,long error){return sample("song",anchor,error);}
    private static Track sample(String key,long anchor,long error){
        Track t=new Track(key,"Song","Artist","",10000+anchor+error,anchor,0);t.sampleDurationMs=3000;return t;
    }
    @Test public void requiresThreeIndependentAdvancingMeasurements(){
        SourceTimeline clock=new SourceTimeline();
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(0,30)).state);
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(3000,-20)).state);
        var result=clock.offer(sample(6000,10));
        assertEquals(SourceTimeline.State.CONFIRMED,result.state);assertEquals(50,result.spreadMs);
        assertEquals(22010,result.track.positionAt(12000,0));
    }
    @Test public void repeatedAndOverlappingAudioCannotConfirm(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,0));
        assertEquals(SourceTimeline.State.IGNORED,clock.offer(sample(0,0)).state);
        assertEquals(SourceTimeline.State.IGNORED,clock.offer(sample(1000,0)).state);
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(3000,0)).state);
    }
    @Test public void longWindowMustAlsoBeIndependent(){
        SourceTimeline clock=new SourceTimeline();Track first=sample(0,0);first.sampleDurationMs=6000;clock.offer(first);
        assertEquals(SourceTimeline.State.IGNORED,clock.offer(sample(3000,0)).state);
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(6000,0)).state);
        assertEquals(SourceTimeline.State.CONFIRMED,clock.offer(sample(9000,0)).state);
    }
    @Test public void rejectsFirstHalfSecondOutlierAndUsesMedianOfLaterWindows(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,500));clock.offer(sample(3000,20));
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(6000,-10)).state);
        var result=clock.offer(sample(9000,10));assertEquals(SourceTimeline.State.CONFIRMED,result.state);
        assertEquals(19010,result.track.offsetMs);
    }
    @Test public void oldAgreementCannotApproveNewestHalfSecondOutlier(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,0));clock.offer(sample(3000,10));clock.offer(sample(6000,-10));
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(9000,500)).state);
        assertEquals(SourceTimeline.State.CONFIRMED,clock.offer(sample(12000,20)).state);
    }
    @Test public void repeatedJumpRequiresThreeNewMeasurements(){
        SourceTimeline clock=new SourceTimeline();for(int i=0;i<8;i++)clock.offer(sample(i*3000,0));
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(24000,500)).state);
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(27000,510)).state);
        var result=clock.offer(sample(30000,505));assertEquals(SourceTimeline.State.CONFIRMED,result.state);assertEquals(40505,result.track.offsetMs);
    }
    @Test public void differentTracksCannotShareCorroboration(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,0));clock.offer(sample(3000,0));
        var changed=clock.offer(sample("new",6000,0));assertEquals(1,changed.count);assertNull(changed.track);
        clock.offer(sample("new",9000,0));assertEquals(SourceTimeline.State.CONFIRMED,clock.offer(sample("new",12000,0)).state);
    }
    @Test public void resetAfterSilenceRequiresFreshEvidence(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,0));clock.offer(sample(3000,0));clock.offer(sample(6000,0));clock.reset();
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(9000,-2000)).state);
    }
    @Test public void pausedSourceIsNotAnAdvancingClock(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,0));clock.offer(sample(3000,-3000));
        assertEquals(SourceTimeline.State.WAITING,clock.offer(sample(6000,-6000)).state);
    }
    @Test public void measured590msDiscrepancyDoesNotCorroborate(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,0));
        var result=clock.offer(sample(90347,-590));assertEquals(SourceTimeline.State.WAITING,result.state);assertEquals(1,result.count);
    }
    @Test public void gradualSlowerSourceIsDetectedAcrossLiveHistory(){
        SourceTimeline clock=new SourceTimeline();SourceTimeline.Result result=null;
        for(int i=0;i<16;i++)result=clock.offer(sample(i*3000,Math.round(-.00653*i*3000)));
        assertEquals(SourceTimeline.State.RATE_MISMATCH,result.state);assertEquals(.99347,result.rate,.0001);
    }
    @Test public void gradualFasterSourceIsDetectedAcrossLiveHistory(){
        SourceTimeline clock=new SourceTimeline();SourceTimeline.Result result=null;
        for(int i=0;i<16;i++)result=clock.offer(sample(i*3000,Math.round(.00653*i*3000)));
        assertEquals(SourceTimeline.State.RATE_MISMATCH,result.state);
    }
    @Test public void boundedJitterAndOneOutlierDoNotInventSpeedChange(){
        SourceTimeline clock=new SourceTimeline();SourceTimeline.Result result=null;
        for(int i=0;i<16;i++)result=clock.offer(sample(i*3000,i==7?500:(i%3-1)*40));
        assertEquals(SourceTimeline.State.CONFIRMED,result.state);assertEquals(1,result.rate,.002);
    }
    @Test public void confirmationPreservesCatalogIdentityAndUsesNewAnchor(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample(0,0));clock.offer(sample(3000,0));Track last=sample(6000,0);
        last.youtubeUrl="https://music.youtube.com/watch?v=ccccccccccc";last.playbackTitle="Catalog title";
        Track result=clock.offer(last).track;assertEquals(last.youtubeUrl,result.youtubeUrl);assertEquals(last.playbackTitle,result.playbackTitle);assertEquals(6000,result.anchorMs);assertEquals(3000,result.sampleDurationMs);
    }
    @Test public void differentTrackInDelayedOldWindowCannotResetNewClock(){
        SourceTimeline clock=new SourceTimeline();clock.offer(sample("new",6000,0));
        assertEquals(SourceTimeline.State.IGNORED,clock.offer(sample("old",0,0)).state);assertTrue(clock.isTracking("new"));
    }
}
