package app.catchwave;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Includes the service's arming-to-engine reset boundary, not only isolated geometry. */
public class GestureTransitionTest {
    @Test public void flatToHeldRollMustNotCalibrateTheVolumeGestureAway() {
        List<GestureEngine.Action> actions=new ArrayList<>();
        GestureEngine engine=new GestureEngine(actions::add);
        GestureArming arming=new GestureArming();
        arming.permit(true,0);arming.sample(0,1,0,0,0,0,9.81);
        assertTrue(arming.activate(0));engine.setEnabled(true,0);
        long revision=arming.revision();
        for(long t=10;t<=4000;t+=10) {
            double angle=t<=1800?0:t<2300?.55*(t-1800)/500:.55;
            double gyro=t>1800&&t<2300?1.1:0;
            double w=Math.cos(angle/2),y=Math.sin(angle/2);
            arming.sample(t,w,0,y,0,gyro,9.81);
            if(revision!=arming.revision()){revision=arming.revision();engine.reset(t,"ARMING_"+arming.reason());}
            engine.gyro(0,gyro,0);engine.orientation(t,w,0,y,0);
            engine.acceleration(t,-9.81*Math.sin(angle),0,9.81*Math.cos(angle));
            if(t>=1800)assertTrue("ordinary roll restarted neutral at "+t,engine.neutralReady(t));
        }
        assertTrue(arming.active(4000));
        assertTrue(actions.size()>=3);
        assertTrue(actions.stream().allMatch(a->a==GestureEngine.Action.VOLUME_UP));
    }
}
