package app.catchwave;

import android.app.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Explicit, bounded device probe. Installed separately; not included in the shipping APK. */
public final class TransportProbe extends Instrumentation {
    private Bundle args;
    private final StringBuilder events=new StringBuilder();
    private final CountDownLatch playing=new CountDownLatch(1),seeked=new CountDownLatch(1);
    private volatile boolean seeking,armed;
    private String title,artist;
    private long requestAt,seekAt;
    private MediaController controller;
    @Override public void onCreate(Bundle arguments){args=arguments;start();}
    private boolean matches(){MediaMetadata m=controller.getMetadata();return m!=null&&title.equals(m.getString(MediaMetadata.METADATA_KEY_TITLE))&&artist.equals(m.getString(MediaMetadata.METADATA_KEY_ARTIST));}
    private synchronized void event(String s){events.append(SystemClock.elapsedRealtime()-requestAt).append("ms ").append(s).append('\n');}
    private final MediaController.Callback callback=new MediaController.Callback(){
        @Override public void onPlaybackStateChanged(PlaybackState state){check();}
        @Override public void onMetadataChanged(MediaMetadata metadata){check();}
    };
    private void check(){
        PlaybackState p=controller.getPlaybackState();MediaMetadata m=controller.getMetadata();
        event("state="+(p==null?"null":p.toString())+" metadata="+(m==null?"null":m.getDescription().toString())+" mediaId="+(m==null?"null":m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)));
        if(armed&&p!=null&&p.getState()==PlaybackState.STATE_PLAYING&&matches()){
            playing.countDown();
            if(seeking&&p.getLastPositionUpdateTime()>=seekAt&&Math.abs(p.getPosition()-60000)<3000)seeked.countDown();
        }
    }
    @Override public void onStart(){
        Bundle result=new Bundle();requestAt=SystemClock.elapsedRealtime();
        try {
            JSONObject input=new JSONObject(new String(android.util.Base64.decode(args.getString("payload"),android.util.Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8));
            if("browse".equals(input.optString("mode"))){
                CountDownLatch done=new CountDownLatch(1);android.media.browse.MediaBrowser[] browser=new android.media.browse.MediaBrowser[1];
                new Handler(Looper.getMainLooper()).post(()->{
                    browser[0]=new android.media.browse.MediaBrowser(getTargetContext(),new android.content.ComponentName(MediaBridge.PACKAGE,MediaBridge.PACKAGE+".mediabrowser.MusicBrowserService"),new android.media.browse.MediaBrowser.ConnectionCallback(){
                        @Override public void onConnectionFailed(){result.putString("browse","connection-failed");done.countDown();}
                        @Override public void onConnectionSuspended(){result.putString("browse","connection-suspended");done.countDown();}
                        @Override public void onConnected(){
                            result.putString("root",browser[0].getRoot());
                            browser[0].subscribe(input.optString("parent",browser[0].getRoot()),new android.media.browse.MediaBrowser.SubscriptionCallback(){
                                @Override public void onError(String id){result.putString("browse","load-failed: "+id);done.countDown();}
                                @Override public void onChildrenLoaded(String id,java.util.List<android.media.browse.MediaBrowser.MediaItem> items){StringBuilder out=new StringBuilder();int count=0;for(var item:items){if(count++>=8)break;out.append(item.getDescription().getTitle()).append(" id=").append(item.getMediaId()).append(" playable=").append(item.isPlayable()).append('\n');}result.putString("browse",out.toString());done.countDown();}
                            });
                        }
                    },Bundle.EMPTY);browser[0].connect();
                });
                result.putBoolean("completed",done.await(6,TimeUnit.SECONDS));new Handler(Looper.getMainLooper()).post(()->{if(browser[0]!=null)browser[0].disconnect();});finish(Activity.RESULT_OK,result);return;
            }
            if("diagnose".equals(input.optString("mode"))){
                result.putString("before",new MediaBridge(getTargetContext()).diagnosticSnapshot());
                MediaController current=new MediaBridge(getTargetContext()).controller();
                if(current!=null&&current.getQueue()!=null){StringBuilder ids=new StringBuilder();int count=0;for(MediaSession.QueueItem item:current.getQueue()){if(count++>=3)break;ids.append(item.getDescription().getTitle()).append(" id=").append(item.getDescription().getMediaId()).append(" uri=").append(item.getDescription().getMediaUri()).append('\n');}result.putString("queueIds",ids.toString());}
                if(input.optBoolean("resetAdjustment"))getTargetContext().getSharedPreferences("settings",0).edit().putInt("adjustment",0).commit();
                result.putString("after",new MediaBridge(getTargetContext()).diagnosticSnapshot());
                finish(Activity.RESULT_OK,result);return;
            }
            title=input.getString("title");artist=input.getString("artist");String mode=input.getString("mode");
            controller=new MediaBridge(getTargetContext()).controller();if(controller==null)throw new IllegalStateException("No authorized YouTube Music session");
            controller.registerCallback(callback,new Handler(Looper.getMainLooper()));check();
            // Only a new playback event after the request is accepted as proof.
            if(matches()&&controller.getPlaybackState()!=null&&controller.getPlaybackState().getState()==PlaybackState.STATE_PLAYING)throw new IllegalStateException("Precondition: target must not already be playing");
            armed=true;event("REQUEST mode="+mode);MediaController.TransportControls controls=controller.getTransportControls();
            if("uri".equals(mode))controls.playFromUri(Uri.parse(input.getString("uri")),Bundle.EMPTY);
            else if("id".equals(mode))controls.playFromMediaId(input.getString("mediaId"),Bundle.EMPTY);
            else if("search".equals(mode)){
                Bundle extras=new Bundle();extras.putString(MediaStore.EXTRA_MEDIA_TITLE,title);extras.putString(MediaStore.EXTRA_MEDIA_ARTIST,artist);extras.putString(MediaStore.EXTRA_MEDIA_FOCUS,"vnd.android.cursor.item/audio");
                controls.playFromSearch(artist+" "+title,extras);
            }else throw new IllegalArgumentException("Unsupported probe mode");
            boolean started=playing.await(12,TimeUnit.SECONDS);result.putBoolean("sawPlaying",started);result.putString("mode",mode);
            if(started){seeking=true;seekAt=SystemClock.elapsedRealtime();controls.seekTo(60000);result.putBoolean("seekConfirmed",seeked.await(3,TimeUnit.SECONDS));}
            check();result.putString("events",events.toString());
        }catch(Exception e){result.putString("failure",e.toString());}
        finally {if(controller!=null){controller.unregisterCallback(callback);if(matches())controller.getTransportControls().pause();}}
        finish(Activity.RESULT_OK,result);
    }
}
