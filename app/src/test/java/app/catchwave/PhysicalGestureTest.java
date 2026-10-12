package app.catchwave;
import org.junit.Test;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;

/** Gravity and orientation agree; these fixtures cover geometry, not device reliability. */
public class PhysicalGestureTest {
    static double[] multiply(double[] a,double[] b){return new double[]{
        a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],
        a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],
        a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],
        a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0]};}
    static double[] gravity(double[] q){return new double[]{2*(q[1]*q[3]-q[0]*q[2]),2*(q[2]*q[3]+q[0]*q[1]),1-2*(q[1]*q[1]+q[2]*q[2])};}
    static double[] neutral(int rotation,double elevation){
        double x=rotation==0?1:rotation==2?-1:0,y=rotation==1?1:rotation==3?-1:0;
        return new double[]{Math.cos(elevation/2),x*Math.sin(elevation/2),y*Math.sin(elevation/2),0};
    }
    static double[] axis(double[] q,int rotation){
        double[] g=gravity(q);double x=rotation==0?1:rotation==2?-1:0,y=rotation==1?1:rotation==3?-1:0;
        double[] a={-g[2]*y,g[2]*x,g[0]*y-g[1]*x};double n=Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);
        for(int i=0;i<3;i++)a[i]/=n;return a;
    }
    static double[] turn(double[] q,double[] a,double angle){double s=Math.sin(angle/2);return multiply(q,new double[]{Math.cos(angle/2),a[0]*s,a[1]*s,a[2]*s});}
    static void orientation(RelativeOrientation o,long t,double[] q,boolean calibrate){assertTrue(o.sample(t,q[0],q[1],q[2],q[3],calibrate));}
    @Test public void flatUprightAndSlantedAllRotationsUseConsistentSign(){
        for(int rot=0;rot<4;rot++)for(double elevation:new double[]{0,Math.PI/4,Math.PI/2}){
            double[] q=neutral(rot,elevation),a=axis(q,rot);RelativeOrientation o=new RelativeOrientation();o.display(rot);orientation(o,10,q,true);assertTrue(o.tiltAvailable());
            orientation(o,20,turn(q,a,.6),false);assertEquals(.6,o.tilt(),1e-9);
            orientation(o,30,turn(q,a,-.6),false);assertEquals(-.6,o.tilt(),1e-9);
            orientation(o,40,turn(q,gravity(q),.8),false);assertEquals(0,o.tilt(),1e-9);
        }
    }
    @Test public void sidewaysNeutralDeclinesTiltUntilRecalibrated(){
        RelativeOrientation o=new RelativeOrientation();double[] sideways={Math.sqrt(.5),0,Math.sqrt(.5),0};orientation(o,10,sideways,true);
        assertTrue(o.fresh(10));assertFalse(o.tiltAvailable());assertTrue(Double.isNaN(o.tilt()));
        orientation(o,20,new double[]{1,0,0,0},false);assertFalse(o.tiltAvailable());
        orientation(o,30,new double[]{1,0,0,0},true);assertTrue(o.tiltAvailable());
    }
    static final class Rig {
        final List<GestureEngine.Action> actions=new ArrayList<>();final GestureEngine e=new GestureEngine(actions::add);long now;
        final double[] neutral,axis;double[] pose;double gyro;
        Rig(int rot,double elevation){neutral=neutral(rot,elevation);axis=axis(neutral,rot);pose=neutral;e.setEnabled(true,0);e.displayRotation(rot,0);quiet(1800);}
        void sample(double impulse){now+=10;e.gyro(gyro,0,0);e.orientation(now,pose[0],pose[1],pose[2],pose[3]);double[] g=gravity(pose);e.acceleration(now,9.81*g[0],9.81*g[1],9.81*g[2]+impulse);}
        void quiet(int ms){for(int i=0;i<ms/10;i++)sample(0);}
        void move(double from,double to,int ms){gyro=Math.abs(to-from)*1000/ms;for(int i=1;i<=ms/10;i++){pose=turn(neutral,axis,from+(to-from)*i/(ms/10));sample(0);}gyro=0;}
        void taps(int n,int gap){for(int i=0;i<n;i++){sample(3.2);sample(3.2);sample(3.2);quiet(gap-30);}}
        String dump(){StringWriter s=new StringWriter();e.dump(new PrintWriter(s),now);return s.toString();}
    }
    @Test public void physicalTiltsDwellRepeatAndStopAtAllPoses(){
        for(int rot=0;rot<4;rot++)for(double elevation:new double[]{0,Math.PI/4,Math.PI/2}){
            Rig r=new Rig(rot,elevation);assertTrue(r.e.tiltAvailable(r.now));r.move(0,.55,500);r.quiet(200);assertTrue(r.actions.isEmpty());
            r.quiet(700);assertEquals(2,r.actions.size());assertTrue(r.actions.stream().allMatch(a->a==GestureEngine.Action.VOLUME_UP));
            r.quiet(1200);assertEquals(5,r.actions.size());r.move(.55,0,500);r.quiet(400);assertEquals(5,r.actions.size());
            r.move(0,-.55,500);r.quiet(900);assertEquals(7,r.actions.size());assertEquals(GestureEngine.Action.VOLUME_DOWN,r.actions.get(6));
        }
    }
    @Test public void gentleSeriesWithAngularJitterFireOnlyExactCount(){
        GestureEngine.Action[] expected={GestureEngine.Action.PLAY_PAUSE,GestureEngine.Action.NEXT,GestureEngine.Action.PREVIOUS,GestureEngine.Action.MUTE};
        for(int n=3;n<=6;n++)for(int gap:new int[]{200,500}){
            Rig r=new Rig(0,Math.PI/2);r.gyro=.7;r.taps(n,gap);assertTrue(r.actions.isEmpty());r.quiet(700);
            assertEquals(List.of(expected[n-3]),r.actions);assertTrue(r.dump().contains("lastTrigger=TAP_SERIES"));assertTrue(r.dump().contains("pulseCompleted="+n));
        }
    }
    @Test public void shortOverlongWeakAndSeparatedSeriesDoNotFire(){
        for(int n:new int[]{1,2,7,8,10}){Rig r=new Rig(0,Math.PI/2);r.taps(n,200);r.quiet(700);assertTrue(r.actions.isEmpty());}
        Rig r=new Rig(0,0);for(int i=0;i<6;i++){r.sample(1);r.quiet(200);}r.quiet(700);assertTrue(r.actions.isEmpty());
        r=new Rig(0,0);r.taps(2,200);r.quiet(450);r.taps(1,200);r.quiet(700);assertTrue(r.actions.isEmpty());assertTrue(r.dump().contains("trainGaps=1"));
    }
    @Test public void pulseRotationAndDurationRejectionsAreObservable(){
        Rig r=new Rig(0,0);r.sample(3.2);r.gyro=.95;r.pose=turn(r.neutral,r.axis,.25);r.sample(3.2);r.quiet(800);assertTrue(r.actions.isEmpty());assertTrue(r.dump().contains("rejectedRotation=1"));
        r=new Rig(0,0);for(int i=0;i<20;i++)r.sample(3.2+i);r.quiet(800);assertTrue(r.actions.isEmpty());assertTrue(r.dump().contains("rejectedDuration=1"));
    }
    @Test public void freshnessResetDropsPendingAndNeutral(){
        Rig r=new Rig(0,Math.PI/2);r.taps(2,200);r.now+=300;r.sample(0);assertFalse(r.e.neutralReady(r.now));r.quiet(1800);r.taps(1,200);r.quiet(700);assertTrue(r.actions.isEmpty());
    }
}
