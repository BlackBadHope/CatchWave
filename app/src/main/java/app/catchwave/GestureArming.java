// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Button-only mode, with no pose prerequisite or timer. Sensor/permission faults still fail closed. */
final class GestureArming {
    private boolean allowed,active;
    private long last=-1,revision;
    private String reason="OFF";
    synchronized void permit(boolean value,long now){if(value!=allowed){allowed=value;clear(value?"LOCKED":"OFF");}tick(now);}
    synchronized void reset(String why){clear(why);}
    synchronized void displayChange(){if(active){reason="DISPLAY_NEUTRAL";revision++;}}
    private void clear(String why){active=false;last=-1;reason=allowed?why:"OFF";revision++;}
    synchronized void tick(long now){if(last>=0&&(now<last||now-last>150))clear("SENSOR_GAP");}
    synchronized boolean active(long now){tick(now);return allowed&&active;}
    synchronized boolean commands(long now){return active(now);}
    synchronized long revision(){return revision;}
    synchronized String reason(){return reason;}
    synchronized boolean activationReady(){return allowed&&last>=0;}
    synchronized boolean activate(long now){
        tick(now);if(!activationReady()||active)return false;
        active=true;reason="ARMED";revision++;return true;
    }
    // Crossing a flat-pose threshold can be the intended 30-degree volume gesture.
    // Keep the mode revision stable; the engine handles genuine off-axis posture changes.
    synchronized void sample(long now,double w,double x,double y,double z,double gyro,double accelNorm){
        tick(now);if(!allowed)return;
        double norm=Math.sqrt(w*w+x*x+y*y+z*z);
        if(!Double.isFinite(norm)||norm<.5||norm>1.5||!Double.isFinite(gyro)||!Double.isFinite(accelNorm)||now<=last){clear("INVALID_SENSOR");return;}
        last=now;
    }
    synchronized String label(long now){return !allowed?L.t("Control is off"):active(now)?L.t("Control activated — no timer"):L.t("Mode is off — press «Activate control»");}
    synchronized String explanation(){return switch(reason){
        case "ARMED" -> L.t("Mode turned on by the button; phone position does not turn it off");
        case "DISPLAY_NEUTRAL" -> L.t("Activation kept; tilt direction follows the screen");
        case "MANUAL_NEUTRAL" -> L.t("Activation reset by the button; press «Activate control»");
        case "MANUAL_OFF" -> L.t("Mode turned off by the button; PC audio continues");
        case "SENSOR_GAP","SENSOR_STALE","GYRO_STALE","ACCEL_STALE","DEPENDENCY_STALE","SENSOR_ACCURACY","INVALID_SENSOR" -> L.t("Mode is off: sensors are unreliable or readings are delayed (")+reason+")";
        case "PERMISSION_CHANGE" -> L.t("Permission changed: activate again with the button");
        default -> L.t("To control, press «Activate control»");
    };}
}
