// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Generated motion traces cover rejection boundaries, not physical-phone acceptance. */
public class TapMotionTest {
    private void tapped(PhysicalGestureTest.Rig r,double excursion){
        r.gyro=0;r.sample(4);
        r.pose=PhysicalGestureTest.turn(r.neutral,r.axis,excursion);
        r.gyro=1.4;r.sample(4);
        r.pose=r.neutral;r.sample(0);
        r.gyro=0;r.quiet(280);
    }
    @Test public void threeShortBouncesWithSmallRotationStillFormOneSeries(){
        PhysicalGestureTest.Rig r=new PhysicalGestureTest.Rig(0,Math.PI/2);
        for(int i=0;i<3;i++)tapped(r,.025);
        r.quiet(700);
        assertEquals(List.of(GestureEngine.Action.PLAY_PAUSE),r.actions);
        assertTrue(r.dump().contains("lastTrigger=TAP_SERIES"));
    }
    @Test public void actualLargeRotationDuringAnImpulseClearsTheSeries(){
        PhysicalGestureTest.Rig r=new PhysicalGestureTest.Rig(0,Math.PI/2);
        for(int i=0;i<3;i++)tapped(r,.25);
        r.quiet(700);
        assertTrue(r.actions.isEmpty());
        assertTrue(r.dump().contains("rejectedRotation=3"));
    }
    @Test public void oneAndTwoSmallBouncesNeverProduceACommand(){
        for(int n=1;n<=2;n++){
            PhysicalGestureTest.Rig r=new PhysicalGestureTest.Rig(0,Math.PI/2);
            for(int i=0;i<n;i++)tapped(r,.025);
            r.quiet(700);assertTrue(r.actions.isEmpty());
        }
    }
}
