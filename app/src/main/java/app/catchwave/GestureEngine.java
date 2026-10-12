// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Experimental thresholds, in SI units. Device recordings must validate these before release. */
public final class GestureEngine {
    public enum Action {VOLUME_UP,VOLUME_DOWN,PLAY_PAUSE,NEXT,PREVIOUS,MUTE}
    public enum Trigger {NONE,TILT,TAP_SERIES,SHAKE}
    public interface Listener {void action(Action action);}
    private static final double TAP_START=2.5,TAP_RELEASE=1.5,TAP_GYRO=.9;
    private static final double TAP_MAX_ROTATION=Math.toRadians(8);
    private static final long TAP_MAX_MS=150,TAP_MIN_GAP=100,TAP_MAX_GAP=600,TAP_FINISH_MS=650;
    private final Listener listener;
    private final RelativeOrientation orientation=new RelativeOrientation();private int displayRotation;
    private final TiltRepeater tilt=new TiltRepeater();
    private boolean enabled,initialized,pulse,tapArmed=true,tiltControl=true;
    private boolean postureRebasing;private long postureQuietSince=-1,postureRebases;
    private long warmUntil,lastSample,lastTap=-10000,pulseStart,trainEnd,coolUntil;
    private int taps,shakeSign,shakePeaks,lastSeries;
    private long shakeStarted,lastShakePeak;
    private double gx,gy,gz,angularSpeed,peakEnergy,peakGyro,peakTapRotation;
    private long pulseStarted,pulseCompleted,rejectedRotation,rejectedDuration,rejectedTooSoon,trainGaps,resets,calibrationMotionSamples;
    private final long[] actionCounts=new long[Action.values().length],seriesCounts=new long[9];
    private String resetCause="NONE";
    private Trigger lastTrigger=Trigger.NONE;private Action lastAction;
    public GestureEngine(Listener listener){this.listener=listener;}
    public void setEnabled(boolean value,long now){enabled=value;reset(now);}
    public void reset(long now){reset(now,"MANUAL_OR_SESSION");}
    public void reset(long now,String cause){resetCause=cause;
        resets++;orientation.reset();
        postureRebasing=false;postureQuietSince=-1;
        tilt.reset(now);initialized=false;pulse=false;tapArmed=true;taps=0;shakePeaks=0;shakeSign=0;
        // Reset uses processing time; the first delivered sample may legitimately be earlier.
        warmUntil=now+1500;lastSample=-1;coolUntil=warmUntil;lastTap=now-10000;angularSpeed=0;shakeStarted=now;lastShakePeak=now-10000;
    }
    public void tiltControl(boolean allowed){tiltControl=allowed;if(!allowed)tilt.stop();}
    public boolean neutralReady(long now){return enabled&&now>=warmUntil&&orientation.fresh(now);}
    long neutralRemaining(long now){return Math.max(0,warmUntil-now);}
    public boolean tiltAvailable(long now){return neutralReady(now)&&!postureRebasing&&orientation.tiltAvailable();}
    public void gyro(double x,double y,double z){angularSpeed=Math.sqrt(x*x+y*y+z*z);peakGyro=Math.max(peakGyro,angularSpeed);}
    public void displayRotation(int rotation,long now){if(rotation!=displayRotation){displayRotation=rotation;orientation.display(rotation);clearPendingGestures(now);}}
    void clearPendingGestures(long now){tilt.stop();taps=0;pulse=false;tapArmed=true;shakePeaks=0;shakeSign=0;coolUntil=Math.max(warmUntil,now+250);}
    public void orientation(long now,double w,double x,double y,double z){
        // Sensor registration can deliver its first fresh quaternion after the reset deadline.
        // Start a full quiet window then, rather than remaining uncalibrated forever.
        if(!orientation.hasNeutral()&&now>=warmUntil){warmUntil=now+1500;coolUntil=warmUntil;}
        boolean calibrating=now<warmUntil;
        if(calibrating&&angularSpeed>.15){calibrationMotionSamples++;warmUntil=now+1500;coolUntil=warmUntil;orientation.clearNeutral();calibrating=false;}
        if(!orientation.sample(now,w,x,y,z,calibrating)){reset(now,"ORIENTATION_INVALID_OR_GAP");return;}
        if(!calibrating&&orientation.hasNeutral()){
            if(orientation.postureChanged())postureRebasing=true;
            else if(postureRebasing){postureRebasing=false;postureQuietSince=-1;}
            if(postureRebasing){
                // A pickup changes the roll axis, not authority to tap or use other commands.
                // Stop volume immediately; choose its new baseline after a short quiet pause.
                tilt.stop();
                if(angularSpeed>.15)postureQuietSince=-1;
                else {
                    if(postureQuietSince<0)postureQuietSince=now;
                    if(now-postureQuietSince>=300){orientation.rebase();postureRebases++;postureRebasing=false;postureQuietSince=-1;}
                }
            }
        }
    }
    public void acceleration(long now,double x,double y,double z){
        if(!enabled)return;
        if(lastSample>=0&&(now<lastSample||now-lastSample>250))reset(now,"ACCELERATION_GAP");lastSample=now;
        if(!initialized){gx=x;gy=y;gz=z;initialized=true;}
        double dx=x-gx,dy=y-gy,dz=z-gz,energy=Math.sqrt(dx*dx+dy*dy+dz*dz);
        gx=.9*gx+.1*x;gy=.9*gy+.1*y;gz=.9*gz+.1*z;
        if(now<warmUntil)return;
        tick(now);
        if(now<coolUntil)return;peakEnergy=Math.max(peakEnergy,energy);
        // Hysteresis avoids restarting a rejected sustained impulse on its tail.
        if(!pulse&&energy<TAP_RELEASE)tapArmed=true;
        if(tapArmed&&energy>TAP_START&&angularSpeed<TAP_GYRO&&!pulse){
            tapArmed=false;pulse=true;pulseStart=now;pulseStarted++;orientation.beginPulse();
        }
        if(pulse){
            double rotation=orientation.pulseRotation();
            if(Double.isFinite(rotation))peakTapRotation=Math.max(peakTapRotation,rotation);
            if(!Double.isFinite(rotation)||rotation>TAP_MAX_ROTATION){pulse=false;taps=0;rejectedRotation++;}
            else if(now-pulseStart>TAP_MAX_MS){pulse=false;taps=0;rejectedDuration++;}
            else if(energy<TAP_RELEASE){
                pulse=false;tapArmed=true;
                if(now-lastTap>=TAP_MIN_GAP){
                    if(taps>0&&now-lastTap>TAP_MAX_GAP){taps=0;trainGaps++;}
                    taps=Math.min(taps+1,8);lastTap=now;trainEnd=now+TAP_FINISH_MS;pulseCompleted++;
                }else rejectedTooSoon++;
            }
        }
        // Shake needs lateral sign reversals AND rotation; taps cannot satisfy this branch.
        double lateral=Math.abs(dx)>Math.abs(dy)?dx:dy;
        if(Math.abs(lateral)>8&&angularSpeed>=1.0){
            int sign=lateral>0?1:-1;
            if(now-shakeStarted>650){shakePeaks=0;shakeSign=0;shakeStarted=now;}
            if(sign!=shakeSign&&now-lastShakePeak>=100){shakePeaks++;shakeSign=sign;lastShakePeak=now;}
            taps=0;pulse=false;tapArmed=false;
            if(shakePeaks>=3){emit(Action.PLAY_PAUSE,Trigger.SHAKE,now);return;}
        }
        boolean ready=tiltControl&&tiltAvailable(now)&&!orientation.flat()&&energy<2&&angularSpeed<.65&&taps==0&&!pulse;
        int direction=tilt.update(now,orientation.tilt(),ready);
        if(direction!=0)emit(direction>0?Action.VOLUME_UP:Action.VOLUME_DOWN,Trigger.TILT,now);
    }
    public void tick(long now){
        if(!enabled||taps==0||pulse||now<trainEnd)return;
        int count=taps;taps=0;lastSeries=count;seriesCounts[count]++;
        switch(count){case 3:emit(Action.PLAY_PAUSE,Trigger.TAP_SERIES,now);break;case 4:emit(Action.NEXT,Trigger.TAP_SERIES,now);break;
            case 5:emit(Action.PREVIOUS,Trigger.TAP_SERIES,now);break;case 6:emit(Action.MUTE,Trigger.TAP_SERIES,now);break;default:break;}
    }
    private void emit(Action action,Trigger trigger,long now){
        if(trigger!=Trigger.TILT){tilt.stop();taps=0;pulse=false;shakePeaks=0;shakeSign=0;coolUntil=now+900;}lastAction=action;lastTrigger=trigger;actionCounts[action.ordinal()]++;listener.action(action);
    }
    public String summary(long now){return "Нейтраль: "+(neutralReady(now)?"готова":"держите спокойно 1,5 с")+
        (neutralReady(now)&&!tiltAvailable(now)?"; для наклонов держите верх телефона вверх":"")+
        "\nУдержание наклона: "+(!tiltControl?"управление выключено":tilt.active()?"громкость меняется каждые 0,4 с":"готово")+"\nПостукивания: "+pulseCompleted+"; серия: "+lastSeries+"; сейчас: "+taps+
        "\nПоследний жест: "+(lastAction==null?"нет":lastAction)+" ("+lastTrigger+")";}
    public void dump(java.io.PrintWriter out,long now){
        out.println("tiltControl="+tiltControl+" tiltRepeating="+tilt.active()+" tiltRepeatMs="+TiltRepeater.REPEAT_MS);
        out.println("postureRebasing="+postureRebasing+" postureRebases="+postureRebases+" flatTiltBlocked="+orientation.flat());
        out.println("neutralReady="+neutralReady(now)+" tiltAvailable="+tiltAvailable(now)+" neutralRemainingMs="+Math.max(0,warmUntil-now)+" orientationAgeMs="+orientation.age(now)+" pendingTaps="+taps+" pulseActive="+pulse+" lastSeries="+lastSeries);
        out.println("pulseStarted="+pulseStarted+" pulseCompleted="+pulseCompleted+" rejectedRotation="+rejectedRotation+" rejectedDuration="+rejectedDuration+" rejectedTooSoon="+rejectedTooSoon+" trainGaps="+trainGaps+" gestureResets="+resets+" calibrationMotionSamples="+calibrationMotionSamples+" resetCause="+resetCause);
        out.println("peakEnergyMilli="+Math.round(peakEnergy*1000)+" peakGyroMilli="+Math.round(peakGyro*1000)+" lastTrigger="+lastTrigger+" lastAction="+(lastAction==null?"NONE":lastAction));
        out.println("peakTapRotationMilliDeg="+Math.round(Math.toDegrees(peakTapRotation)*1000)+" tapMaxRotationDeg=8 tapStartMilli="+Math.round(TAP_START*1000));
        for(Action a:Action.values())out.println("action"+a.name().replace("_","")+"="+actionCounts[a.ordinal()]);
        for(int i=1;i<=8;i++)out.println("seriesCount"+i+"="+seriesCounts[i]);
    }
}
