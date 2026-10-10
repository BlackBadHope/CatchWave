// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Relative, short-lived intent gate. No compass heading, disk state or sensor history survives reset. */
final class GestureArming {
    static final long BASE_DWELL=850, TURN_DWELL=250, STOP_DWELL=150, WINDOW=10000, BASE_LIFETIME=20000;
    private static final double FLAT=Math.cos(Math.toRadians(15)), EXIT_FLAT=Math.cos(Math.toRadians(22));
    private boolean allowed,baseline,active,flatPosture;
    private long last=-1,baseSince=-1,turnSince=-1,stopSince=-1,flatSince=-1,baseAt,until,revision;
    private double bw,bx,by,bz,cw,cx,cy,cz;
    private String reason="OFF";
    synchronized void permit(boolean value,long now){if(value!=allowed){allowed=value;clear(value?"LOCKED":"OFF");}tick(now);}
    synchronized void reset(String why){clear(why);}
    synchronized void displayChange(){if(active){reason="DISPLAY_NEUTRAL";revision++;}}
    private void clear(String why){baseline=active=flatPosture=false;last=baseSince=turnSince=stopSince=flatSince=-1;until=0;reason=allowed?why:"OFF";revision++;}
    synchronized void tick(long now){if(active&&now>=until)clear("EXPIRED");if(last>=0&&(now<last||now-last>150))clear("SENSOR_GAP");}
    synchronized boolean active(long now){tick(now);return allowed&&active;}
    synchronized boolean commands(long now){return active(now)&&stopSince<0;}
    synchronized long remaining(long now){return active(now)?Math.max(0,until-now):0;}
    synchronized long revision(){return revision;}
    synchronized String reason(){return reason;}
    synchronized boolean baseline(){return baseline;}
    // Inputs must be contemporaneous. Acceleration only establishes stability; never a recorded vector.
    synchronized void sample(long now,double w,double x,double y,double z,double gyro,double accelNorm){
        tick(now);if(!allowed)return;
        double norm=Math.sqrt(w*w+x*x+y*y+z*z);
        if(!Double.isFinite(norm)||norm<.5||norm>1.5||!Double.isFinite(gyro)||!Double.isFinite(accelNorm)||now<=last){clear("INVALID_SENSOR");return;}
        w/=norm;x/=norm;y/=norm;z/=norm;last=now;
        double gravityZ=1-2*(x*x+y*y);
        boolean down=gravityZ<=-FLAT,up=gravityZ>=FLAT,quiet=gyro<=.18&&Math.abs(accelNorm-9.81)<=1.2;
        if(active){
            double angle=relativeYaw(w,x,y,z);
            boolean stop=quiet&&(up||down&&Math.abs(angle)<=Math.toRadians(15));
            if(stop){if(stopSince<0){stopSince=now;reason="STOP_PENDING";revision++;}if(now-stopSince>=STOP_DWELL){clear(up?"FACE_UP":"RETURNED");return;}}else stopSince=-1;
            // Leaving the table posture resets the gesture engine once, without extending the window.
            if(flatPosture&&gravityZ>-EXIT_FLAT){flatPosture=false;reason="PICKUP_NEUTRAL";revision++;}
            if(!flatPosture&&down&&quiet&&!stop){if(flatSince<0)flatSince=now;if(now-flatSince>=STOP_DWELL){flatPosture=true;flatSince=-1;reason="TABLE_NEUTRAL";revision++;}}else flatSince=-1;
            return;
        }
        if(baseline&&now-baseAt>BASE_LIFETIME){clear("BASE_EXPIRED");last=now;}
        if(!baseline){
            if(!down||!quiet){baseSince=-1;return;}
            if(baseSince<0){baseSince=now;cw=w;cx=x;cy=y;cz=z;}
            if(distance(cw,cx,cy,cz,w,x,y,z)>Math.toRadians(5)){baseSince=now;cw=w;cx=x;cy=y;cz=z;}
            if(now-baseSince>=BASE_DWELL){bw=w;bx=x;by=y;bz=z;baseline=true;baseAt=now;reason="TURN_90";revision++;}
            return;
        }
        if(!down){clear("LEFT_TABLE");last=now;return;}
        double angle=Math.abs(relativeYaw(w,x,y,z));
        if(quiet&&angle>=Math.toRadians(75)&&angle<=Math.toRadians(105)){
            if(turnSince<0)turnSince=now;
            if(now-turnSince>=TURN_DWELL){active=true;flatPosture=true;until=now+WINDOW;stopSince=-1;reason="ARMED";revision++;}
        }else turnSince=-1;
    }
    private double relativeYaw(double w,double x,double y,double z){
        double rw=bw*w+bx*x+by*y+bz*z,rz=bw*z-bx*y+by*x-bz*w;
        return Math.atan2(Math.sin(2*Math.atan2(rz,rw)),Math.cos(2*Math.atan2(rz,rw)));
    }
    private static double distance(double a,double b,double c,double d,double w,double x,double y,double z){return 2*Math.acos(Math.min(1,Math.abs(a*w+b*x+c*y+d*z)));}
    synchronized String label(long now){return !allowed?"Жестовое окно выключено":active(now)?"Жестовое окно: "+((remaining(now)+999)/1000)+" с":baseline?"Заблокировано — поверните экраном вниз на 90°":"Заблокировано — положите экраном вниз на 0,85 с";}
    synchronized String explanation(){return switch(reason){
        case "FACE_UP" -> "Окно закрыто: телефон устойчиво экраном вверх";
        case "RETURNED" -> "Окно закрыто: возврат к исходному углу экраном вниз";
        case "EXPIRED" -> "Окно закрыто: истекли 10 секунд";
        case "STOP_PENDING" -> "Команды приостановлены: проверка положения выключения";
        case "PICKUP_NEUTRAL","TABLE_NEUTRAL","DISPLAY_NEUTRAL" -> "Положение изменилось: держите спокойно для новой нейтрали";
        case "MANUAL_NEUTRAL" -> "Активация сброшена кнопкой; повторите поворот на столе";
        case "SENSOR_GAP","SENSOR_STALE","GYRO_STALE","ACCEL_STALE","DEPENDENCY_STALE","SENSOR_ACCURACY","INVALID_SENSOR" -> "Окно закрыто: датчики недостоверны или измерения задержались ("+reason+")";
        case "LEFT_TABLE" -> "Активация сброшена: телефон поднят до поворота на 90°";
        case "BASE_EXPIRED" -> "Исходное положение устарело: снова положите экраном вниз";
        case "PERMISSION_CHANGE" -> "Разрешение изменилось: нужна новая активация";
        default -> "Состояние активации: "+reason;
    };}
}
