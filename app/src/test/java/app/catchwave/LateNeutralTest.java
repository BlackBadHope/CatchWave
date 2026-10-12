// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class LateNeutralTest {
    @Test public void firstFreshOrientationAfterStartupWindowStillAcquiresNeutral(){
        List<GestureEngine.Action> actions=new ArrayList<>();GestureEngine e=new GestureEngine(actions::add);e.setEnabled(true,0);
        for(long t=2000;t<=3600;t+=10){
            e.gyro(0,0,0);e.orientation(t,Math.sqrt(.5),Math.sqrt(.5),0,0);e.acceleration(t,0,9.81,0);
            if(t<3500)assertFalse(e.neutralReady(t));
        }
        assertTrue("Late sensor startup must not leave NEUTRAL_WAIT forever",e.neutralReady(3600));
        assertTrue(actions.isEmpty());
    }
}
