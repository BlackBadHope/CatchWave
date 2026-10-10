package app.catchwave;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GestureEngineTest {
    private static final class Rig {
        final List<GestureEngine.Action> actions=new ArrayList<>();final GestureEngine engine=new GestureEngine(actions::add);long now;
        Rig(){engine.setEnabled(true,0);quiet(1800);}
        double tilt;
        void sample(double x,double y,double z){now+=10;engine.orientation(now,Math.cos(tilt/2),0,Math.sin(tilt/2),0);engine.acceleration(now,x,y,z);}
        void quiet(int ms){for(int i=0;i<ms/10;i++)sample(0,0,9.81);}
        void taps(int n){for(int i=0;i<n;i++){sample(0,0,19.81);sample(0,0,19.81);quiet(200);}}
    }
    @Test public void exactSeriesDoNotFirePrefixes(){
        GestureEngine.Action[] expected={GestureEngine.Action.PLAY_PAUSE,GestureEngine.Action.NEXT,GestureEngine.Action.PREVIOUS,GestureEngine.Action.MUTE};
        for(int n=3;n<=6;n++){Rig r=new Rig();r.taps(n);assertTrue("no prefix for "+n,r.actions.isEmpty());r.quiet(750);assertEquals(List.of(expected[n-3]),r.actions);}
    }
    @Test public void ShortAndOverlongSeriesIgnored(){for(int n:new int[]{1,2,7,8}){Rig r=new Rig();r.taps(n);r.quiet(750);assertTrue(r.actions.isEmpty());}}
    @Test public void disableOrReconnectDropsUnfinishedSeries(){
        Rig r=new Rig();r.taps(3);r.engine.setEnabled(false,r.now);r.quiet(1000);assertTrue(r.actions.isEmpty());
        r.engine.setEnabled(true,r.now);r.quiet(1800);r.taps(2);r.engine.reset(r.now);r.quiet(1800);assertTrue(r.actions.isEmpty());
    }
    @Test public void tiltRepeatsUntilNeutral(){
        Rig r=new Rig();r.tilt=.55;for(int i=0;i<120;i++)r.sample(0,0,9.81);assertEquals(3,r.actions.size());
        for(int i=0;i<200;i++)r.sample(0,0,9.81);assertEquals(8,r.actions.size());
        assertTrue(r.actions.stream().allMatch(a->a==GestureEngine.Action.VOLUME_UP));r.tilt=0;r.quiet(1300);assertEquals(8,r.actions.size());
        r.tilt=-.55;for(int i=0;i<120;i++)r.sample(0,0,9.81);assertEquals(11,r.actions.size());assertEquals(GestureEngine.Action.VOLUME_DOWN,r.actions.get(10));
    }
    @Test public void rotatingLongImpulseIsNotTap(){Rig r=new Rig();r.engine.gyro(2,0,0);r.taps(5);r.quiet(750);assertTrue(r.actions.isEmpty());}
    @Test public void uprightNeutralDoesNotAmplifyTinyNoise(){
        List<GestureEngine.Action> actions=new ArrayList<>();GestureEngine engine=new GestureEngine(actions::add);engine.setEnabled(true,0);
        for(long t=10;t<=1800;t+=10){engine.orientation(t,Math.sqrt(.5),Math.sqrt(.5),0,0);engine.acceleration(t,0,9.81,0);}
        for(long t=1810;t<=4000;t+=10){engine.orientation(t,Math.sqrt(.5),Math.sqrt(.5),0,0);engine.acceleration(t,.1,9.81,.01);}
        assertTrue(actions.isEmpty());
    }
    @Test public void shakeNeedsAlternatingLateralPeaksAndRotation(){
        Rig r=new Rig();r.engine.gyro(2,0,0);
        for(int sign:new int[]{1,-1,1}){r.sample(sign*15,0,9.81);r.quiet(120);}
        assertEquals(List.of(GestureEngine.Action.PLAY_PAUSE),r.actions);r.quiet(600);assertEquals(1,r.actions.size());
    }
}
