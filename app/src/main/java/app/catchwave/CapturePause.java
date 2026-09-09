package app.catchwave;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;

/** A temporary pause belongs to us only until the player or user changes it. */
final class CapturePause {
    private final MediaController controller;
    private final String identity;
    private final boolean requested;
    private PlaybackState acknowledged;
    private boolean released;
    CapturePause(MediaController controller) {
        this.controller=controller;identity=identity(controller);
        PlaybackState p=controller==null?null:controller.getPlaybackState();
        requested=p!=null&&(p.getState()==PlaybackState.STATE_PLAYING||p.getState()==PlaybackState.STATE_BUFFERING||p.getState()==PlaybackState.STATE_CONNECTING);
        if(requested&&MediaBridge.supports(controller,PlaybackState.ACTION_PAUSE))controller.getTransportControls().pause();
    }
    boolean requested(){return requested;}
    boolean ready() {
        PlaybackState p=controller==null?null:controller.getPlaybackState();
        if(!requested)return p==null||p.getState()!=PlaybackState.STATE_PLAYING;
        if(p==null||!identity.equals(identity(controller)))return false;
        if(p.getState()==PlaybackState.STATE_PAUSED){acknowledged=p;return true;}
        return false;
    }
    void release(){released=true;}
    boolean restore(MediaController current) {
        if(released||!requested||acknowledged==null||current==null)return false;
        released=true;
        PlaybackState p=current.getPlaybackState();
        if(!controller.getSessionToken().equals(current.getSessionToken())||!identity.equals(identity(current))||p==null||
            p.getState()!=PlaybackState.STATE_PAUSED||p.getLastPositionUpdateTime()!=acknowledged.getLastPositionUpdateTime()||p.getPosition()!=acknowledged.getPosition()||
            !MediaBridge.supports(current,PlaybackState.ACTION_PLAY))return false;
        current.getTransportControls().play();return true;
    }
    private static String identity(MediaController c){
        MediaMetadata m=c==null?null:c.getMetadata();
        return m==null?"":m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)+"\n"+m.getString(MediaMetadata.METADATA_KEY_TITLE)+"\n"+m.getString(MediaMetadata.METADATA_KEY_ARTIST);
    }
}
