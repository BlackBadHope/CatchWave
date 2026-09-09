package app.catchwave;

import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.provider.MediaStore;
import java.util.List;

public class MediaBridge {
    public static final String PACKAGE="com.google.android.apps.youtube.music";
    private final Context context;
    public MediaBridge(Context context){this.context=context;}
    public static boolean allowed(Context context) {
        String enabled=Settings.Secure.getString(context.getContentResolver(),"enabled_notification_listeners");
        ComponentName wanted=new ComponentName(context,MediaAccessService.class);
        if(enabled!=null) for(String s:enabled.split(":")) if(wanted.equals(ComponentName.unflattenFromString(s)))return true;
        return false;
    }
    public MediaController controller() {
        if(!allowed(context))return null;
        try {
            MediaSessionManager manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for(MediaController c:manager.getActiveSessions(new ComponentName(context,MediaAccessService.class)))
                if(PACKAGE.equals(c.getPackageName()))return c;
        }catch(SecurityException ignored){}
        return null;
    }
    public static boolean isTrack(MediaController c,Track target) {
        MediaMetadata m=c==null?null:c.getMetadata();
        return m!=null&&target!=null&&target.matches(m.getString(MediaMetadata.METADATA_KEY_TITLE),m.getString(MediaMetadata.METADATA_KEY_ARTIST));
    }
    public static boolean supports(MediaController c,long action) {
        PlaybackState s=c==null?null:c.getPlaybackState();return s!=null&&(s.getActions()&action)!=0;
    }
    public boolean requestTrack(Track target) {
        MediaController c=controller();if(c==null)return false;
        if(RecognitionClient.validMusicUrl(target.youtubeUrl)&&supports(c,PlaybackState.ACTION_PLAY_FROM_URI)) {
            c.getTransportControls().playFromUri(Uri.parse(target.youtubeUrl),Bundle.EMPTY);return true;
        }
        if(supports(c,PlaybackState.ACTION_PLAY_FROM_SEARCH)) {
            Bundle extras=new Bundle();extras.putString(MediaStore.EXTRA_MEDIA_TITLE,target.playbackTitle);
            extras.putString(MediaStore.EXTRA_MEDIA_ARTIST,target.playbackArtist);extras.putString(MediaStore.EXTRA_MEDIA_FOCUS,"vnd.android.cursor.item/audio");
            c.getTransportControls().playFromSearch(target.playbackArtist+" "+target.playbackTitle,extras);return true;
        }
        return false;
    }
    public static String launchUrl(Track target) {
        if(RecognitionClient.validMusicUrl(target.youtubeUrl))return target.youtubeUrl;
        return "https://music.youtube.com/search?q="+Uri.encode(target.artist+" "+target.title);
    }
    public static Intent openIntent(Track target){return new Intent(Intent.ACTION_VIEW,Uri.parse(launchUrl(target))).setPackage(PACKAGE);}
    public String diagnosticSnapshot(){
        MediaController c=controller();MediaMetadata m=c==null?null:c.getMetadata();PlaybackState p=c==null?null:c.getPlaybackState();
        AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        StringBuilder outputs=new StringBuilder();for(AudioDeviceInfo d:am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))outputs.append(d.getType()).append(' ');
        return "YTM: "+(m==null?"нет метаданных":m.getDescription())+"\nPlaybackState: "+p+"\nДоступные выходы (типы): "+outputs+
            "\nMedia volume: "+am.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)+
            "\nПоправка: "+context.getSharedPreferences("settings",Context.MODE_PRIVATE).getInt("adjustment",0)+" мс\nnow="+android.os.SystemClock.elapsedRealtime();
    }
    public static boolean headphones(Context context) {
        return headphonesForTypes(mediaOutputTypes(context));
    }
    public static String outputLabel(Context context) {
        return outputLabelForTypes(mediaOutputTypes(context));
    }
    // This is the system's predicted media route, not proof of another app's per-player output.
    private static int[] mediaOutputTypes(Context context) {
        if(Build.VERSION.SDK_INT<33||context==null)return new int[0];
        AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(am==null)return new int[0];
        try{
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            List<AudioDeviceInfo> devices=am.getAudioDevicesForAttributes(attributes);
            if(devices==null)return new int[0];
            int[] types=new int[devices.size()];
            for(int i=0;i<types.length;i++)types[i]=devices.get(i)==null?AudioDeviceInfo.TYPE_UNKNOWN:devices.get(i).getType();
            return types;
        }catch(RuntimeException unavailable){return new int[0];}
    }
    static boolean headphonesForTypes(int... routedTypes) {
        if(routedTypes==null||routedTypes.length==0)return false;
        for(int type:routedTypes){
            if(type!=AudioDeviceInfo.TYPE_WIRED_HEADPHONES&&type!=AudioDeviceInfo.TYPE_WIRED_HEADSET
                &&type!=AudioDeviceInfo.TYPE_USB_HEADSET&&type!=AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                &&type!=AudioDeviceInfo.TYPE_BLE_HEADSET)return false;
        }
        return true;
    }
    static String outputLabelForTypes(int... routedTypes) {
        int category=0;
        if(routedTypes!=null)for(int type:routedTypes){
            int next=outputCategory(type);
            if(next==0||(category!=0&&category!=next)){category=0;break;}
            category=next;
        }
        return "Системный выход: "+(category==1?"динамик":category==2?"проводные наушники":category==3?"Bluetooth":"неизвестен");
    }
    private static int outputCategory(int type) {
        switch(type){
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE:return 1;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:return 2;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:return 3;
            default:return 0;
        }
    }
}
