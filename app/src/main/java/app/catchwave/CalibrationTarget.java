package app.catchwave;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import java.util.Objects;

/** A measurement cannot outlive the selected track or a user's transport action. */
final class CalibrationTarget {
    private final Track track;
    private final String mediaId;
    CalibrationTarget(MediaController controller,Track track){this.track=track;mediaId=id(controller);}
    boolean valid(MediaController controller,SessionModel model){
        PlaybackState p=controller==null?null:controller.getPlaybackState();
        return model.running&&model.guardingTrack&&!model.live&&!model.manualHold&&model.track==track
            &&MediaBridge.isTrack(controller,track)&&Objects.equals(mediaId,id(controller))&&p!=null
            &&p.getState()==PlaybackState.STATE_PLAYING&&Math.abs(p.getPlaybackSpeed()-1f)<.001f
            &&MediaBridge.supports(controller,PlaybackState.ACTION_SEEK_TO);
    }
    static String id(MediaController controller){MediaMetadata m=controller==null?null:controller.getMetadata();return m==null?null:m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);}
    static long position(MediaController controller){PlaybackState p=controller.getPlaybackState();return p==null?-1:SyncMath.playerPosition(p.getPosition(),p.getLastPositionUpdateTime(),p.getPlaybackSpeed(),SystemClock.elapsedRealtime(),p.getState()==PlaybackState.STATE_PLAYING);}
    static long duration(MediaController controller){MediaMetadata m=controller.getMetadata();return m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);}
}
