package app.catchwave;

import android.media.AudioManager;

/** Restore only the mute owned by this measurement. A user volume change wins. */
final class CaptureVolume {
    private final AudioManager manager;
    private final int previous;
    private boolean owned;
    CaptureVolume(AudioManager manager){this.manager=manager;previous=manager.getStreamVolume(AudioManager.STREAM_MUSIC);}
    boolean mute(){
        if(previous>0){owned=true;manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);}
        return muted();
    }
    boolean muted(){return manager.getStreamVolume(AudioManager.STREAM_MUSIC)==0;}
    void restore(){if(owned){owned=false;if(muted())manager.setStreamVolume(AudioManager.STREAM_MUSIC,previous,0);}}
}
