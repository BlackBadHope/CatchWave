package app.catchwave;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;

/** Keeps ownership of one selected recording until the player acknowledges its end pause. */
final class TrackEndGuard {
    enum State { PLAYING, PAUSING, STOPPED, FAILED }
    private final MediaController controller;
    private final Track track;
    private final String mediaId;
    private long stopAt=-1,pauseAt=-1,ackAt=-1;
    private long ackPosition;
    private String ackMediaId="";
    private int requests;
    private boolean destroyed,closed;
    private final MediaController.Callback callback;
    TrackEndGuard(MediaController controller,Track track,Handler handler,Runnable changed){
        this.controller=controller;this.track=track;mediaId=id(controller.getMetadata());
        callback=new MediaController.Callback(){
            @Override public void onMetadataChanged(MediaMetadata metadata){changed.run();}
            @Override public void onPlaybackStateChanged(PlaybackState state){changed.run();}
            @Override public void onSessionDestroyed(){destroyed=true;changed.run();}
        };
        controller.registerCallback(callback,handler);
    }
    State check(long now){
        if(closed||destroyed)return State.FAILED;
        PlaybackState state=controller.getPlaybackState();MediaMetadata metadata=controller.getMetadata();
        if(state==null)return State.PLAYING;
        long duration=metadata==null?0:metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean playing=state.getState()==PlaybackState.STATE_PLAYING;
        long position=SyncMath.playerPosition(state.getPosition(),state.getLastPositionUpdateTime(),state.getPlaybackSpeed(),now,playing);
        boolean changed=metadata!=null&&(!MediaBridge.isTrack(controller,track)||!mediaId.isEmpty()&&!id(metadata).isEmpty()&&!mediaId.equals(id(metadata)));
        if(stopAt<0&&(changed||playing&&duration>0&&position>=duration-120||state.getState()==PlaybackState.STATE_STOPPED)){
            if(!MediaBridge.supports(controller,PlaybackState.ACTION_PAUSE))return State.FAILED;
            stopAt=now;pauseAt=now;requests=1;controller.getTransportControls().pause();
            return State.PAUSING;
        }
        if(stopAt<0)return State.PLAYING;
        if(state.getState()==PlaybackState.STATE_PAUSED||state.getState()==PlaybackState.STATE_STOPPED){
            // Position-update time is not a pause acknowledgement timestamp. YouTube Music can
            // remain paused at the next item's position 0 without advancing that timestamp.
            String currentId=id(metadata);
            if(ackAt<0||ackPosition!=state.getPosition()||!ackMediaId.equals(currentId)){
                ackAt=now;ackPosition=state.getPosition();ackMediaId=currentId;
            }
            if(now-ackAt>=700)return State.STOPPED;
        }else{
            ackAt=-1;
            if(now-pauseAt>=700&&requests<2){controller.getTransportControls().pause();pauseAt=now;requests++;}
        }
        return now-stopAt>2500?State.FAILED:State.PAUSING;
    }
    void close(){if(!closed){closed=true;controller.unregisterCallback(callback);}}
    private static String id(MediaMetadata metadata){String id=metadata==null?null:metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);return id==null?"":id;}
}
