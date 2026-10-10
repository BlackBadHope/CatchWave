package app.catchwave;
import org.junit.Test;
import static org.junit.Assert.*;

public class GestureArmingTest {
    final GestureArming gate=new GestureArming();long now;
    void sample(double degrees,boolean up,double gyro){double a=Math.toRadians(degrees)/2;gate.sample(now,up?Math.cos(a):0,up?0:Math.cos(a),up?0:-Math.sin(a),up?Math.sin(a):0,gyro,9.81);now+=50;}
    void hold(double angle,boolean up,int n){for(int i=0;i<n;i++)sample(angle,up,0);}
    void arm(double turn){gate.permit(true,now);hold(0,false,19);assertTrue(gate.baseline());hold(turn,false,7);assertTrue(gate.active(now));}
    @Test public void defaultLockedAndPermissionOff(){hold(0,false,30);assertFalse(gate.baseline());gate.permit(true,now);hold(0,false,19);assertFalse(gate.active(now));gate.permit(false,now);assertFalse(gate.baseline());assertFalse(gate.commands(now));}
    @Test public void eitherQuarterTurnArmsFixedTenSeconds(){for(int direction:new int[]{-1,1}){gate.reset("TEST");arm(direction*90);long left=gate.remaining(now);hold(direction*95,false,20);assertEquals(left-1000,gate.remaining(now));while(gate.remaining(now)>0)sample(direction*90,false,0);assertFalse(gate.active(now));assertFalse(gate.baseline());}}
    @Test public void rotationWithoutStableFaceDownCannotArm(){gate.permit(true,now);for(int i=0;i<60;i++)sample(i*3,false,1);assertFalse(gate.baseline());hold(90,true,30);assertFalse(gate.active(now));}
    @Test public void wrongAngleAndMotionNeverArm(){gate.permit(true,now);hold(0,false,19);hold(50,false,20);hold(90,false,1);for(int i=0;i<10;i++)sample(90,false,.3);assertFalse(gate.active(now));}
    @Test public void returnToOriginalAndFaceUpLockAndSuppressBeforeDwell(){arm(90);sample(0,false,0);assertFalse(gate.commands(now));hold(0,false,4);assertFalse(gate.active(now));arm(-90);sample(-90,true,0);assertFalse(gate.commands(now));hold(-90,true,4);assertFalse(gate.active(now));}
    @Test public void baselineAndActiveCannotSurviveSensorGapOrPermissionCycle(){gate.permit(true,now);hold(0,false,19);now+=200;sample(90,false,0);assertFalse(gate.baseline());assertFalse(gate.active(now));arm(90);now+=151;assertFalse(gate.commands(now));arm(90);gate.permit(false,now);gate.permit(true,now);assertFalse(gate.baseline());hold(90,false,6);assertFalse(gate.active(now));}
    @Test public void pickupResetsNeutralRevisionWithoutExtendingOrClosingWindow(){arm(90);long remaining=gate.remaining(now),revision=gate.revision();double a=Math.toRadians(50)/2;gate.sample(now,Math.sin(a),Math.cos(a),0,0,.4,9.81);now+=50;assertTrue(gate.active(now));assertTrue(gate.revision()>revision);assertEquals("PICKUP_NEUTRAL",gate.reason());assertEquals(remaining-50,gate.remaining(now));}
    @Test public void repeatedPickupNeedsAnotherFreshNeutralInSameWindow(){arm(90);double a=Math.toRadians(50)/2;gate.sample(now,Math.sin(a),Math.cos(a),0,0,.4,9.81);now+=50;hold(90,false,5);long revision=gate.revision(),remaining=gate.remaining(now);gate.sample(now,Math.sin(a),Math.cos(a),0,0,.4,9.81);now+=50;assertTrue(gate.revision()>revision);assertEquals("PICKUP_NEUTRAL",gate.reason());assertTrue(gate.active(now));assertEquals(remaining-50,gate.remaining(now));}
    @Test public void arbitraryWorldHeadingAndQuaternionSignDoNotAffectRelativeTurn(){for(double heading:new double[]{0,75,170,-150}){gate.reset("TEST");gate.permit(true,now);for(int i=0;i<19;i++){double h=Math.toRadians(heading)/2;gate.sample(now,0,Math.cos(h),Math.sin(h),0,0,9.81);now+=50;}for(int i=0;i<7;i++){double h=Math.toRadians(heading-90)/2;gate.sample(now,0,-Math.cos(h),-Math.sin(h),0,0,9.81);now+=50;}assertTrue(gate.active(now));}}
    @Test public void invalidSensorAndStaleBaselineFailClosed(){arm(90);gate.sample(now,Double.NaN,0,0,0,0,9.81);assertFalse(gate.active(now));gate.permit(true,now);hold(0,false,19);hold(0,false,410);assertFalse(gate.active(now));}
}
