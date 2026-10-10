package app.catchwave;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TiltRepeaterTest {
    private static List<Long> hold(TiltRepeater t,long start,long end,double angle){
        List<Long> steps=new ArrayList<>();for(long now=start;now<=end;now+=10)if(t.update(now,angle,true)!=0)steps.add(now);return steps;
    }
    @Test public void dwellThenRepeatsAtMostEvery400msWithoutReturningToNeutral(){
        TiltRepeater t=new TiltRepeater();t.reset(0);
        assertEquals(List.of(250L,650L,1050L,1450L),hold(t,0,1500,.55));assertTrue(t.active());
    }
    @Test public void hysteresisSurvivesJitterAndStopsInsideReleaseThreshold(){
        TiltRepeater t=new TiltRepeater();t.reset(0);hold(t,0,250,.55);
        for(long now=260;now<650;now+=10)assertEquals(0,t.update(now,now%20==0?.41:.43,true));
        assertEquals(1,t.update(650,.30,true));assertTrue(t.active());
        assertEquals(0,t.update(660,.25,true));assertFalse(t.active());
        assertTrue(hold(t,670,1400,.40).isEmpty());assertFalse(t.active());
    }
    @Test public void neutralAndUnavailableStopImmediatelyAndReentryNeedsDwell(){
        for(double angle:new double[]{0,.1,Double.NaN}){
            TiltRepeater t=new TiltRepeater();t.reset(0);hold(t,0,250,.55);assertEquals(0,t.update(260,angle,true));assertFalse(t.active());
        }
        TiltRepeater t=new TiltRepeater();t.reset(0);hold(t,0,250,.55);assertEquals(0,t.update(260,.55,false));assertFalse(t.active());
        assertTrue(hold(t,270,640,.55).isEmpty());assertEquals(1,t.update(650,.55,true));
    }
    @Test public void directDirectionChangeMustDwellAndCannotBypassGlobalRateLimit(){
        TiltRepeater t=new TiltRepeater();t.reset(0);hold(t,0,250,.55);
        assertTrue(hold(t,260,640,-.55).isEmpty());assertEquals(-1,t.update(650,-.55,true));
        assertTrue(hold(t,660,1040,.55).isEmpty());assertEquals(1,t.update(1050,.55,true));
    }
    @Test public void lateBackwardsAndSparseSamplesNeverCatchUpWithABurst(){
        TiltRepeater t=new TiltRepeater();t.reset(0);hold(t,0,250,.55);
        assertEquals(0,t.update(1000,.55,true));assertTrue(hold(t,1010,1240,.55).isEmpty());assertEquals(1,t.update(1250,.55,true));
        assertEquals(0,t.update(1250,.55,true));assertEquals(0,t.update(1250,.55,true));
        assertEquals(0,t.update(1200,.55,true));assertTrue(hold(t,1210,1440,.55).isEmpty());
        assertEquals(0,t.update(1449,.55,true));assertEquals(0,t.update(1500,.55,true));
    }
    @Test public void samplesAroundEntryThresholdCannotAccumulateDwell(){
        TiltRepeater t=new TiltRepeater();t.reset(0);
        for(long now=0;now<=2000;now+=10)assertEquals(0,t.update(now,now%20==0?.43:.41,true));
    }
}
