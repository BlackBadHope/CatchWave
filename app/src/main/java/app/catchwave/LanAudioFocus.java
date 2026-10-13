// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.SystemClock;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Keeps the owner-started connection while phone audio has priority. Main-looper only. */
final class LanAudioFocus implements AutoCloseable {
    interface Platform {
        int request(IntConsumer listener);
        boolean phoneAudioActive();
        void watch(Runnable changed);
        void abandon();
    }
    private final Handler main;
    private final Platform platform;
    private final BooleanSupplier alive, fresh;
    private final IntConsumer output;
    private boolean closed, paused, permanent, watching, phoneWasActive;
    private long quietSince=-1;
    private final Runnable check=this::checkReturn;

    LanAudioFocus(Handler main,AudioManager manager,BooleanSupplier alive,BooleanSupplier fresh,IntConsumer output){
        this(main,new AndroidPlatform(main,manager),alive,fresh,output);
    }
    LanAudioFocus(Handler main,Platform platform,BooleanSupplier alive,BooleanSupplier fresh,IntConsumer output){
        this.main=main;this.platform=platform;this.alive=alive;this.fresh=fresh;this.output=output;
    }
    boolean start(){
        if(closed||!alive.getAsBoolean())return false;
        // Starting/switching a cable route must not take focus away from phone media.
        // No CatchWave track exists yet; wait for real phone playback to become quiet.
        boolean busy;
        try{busy=platform.phoneAudioActive();}catch(RuntimeException unavailable){busy=true;}
        if(busy){changed(AudioManager.AUDIOFOCUS_LOSS);return true;}
        int result=platform.request(this::changed);
        if(result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){changed(AudioManager.AUDIOFOCUS_GAIN);return true;}
        if(result==AudioManager.AUDIOFOCUS_REQUEST_DELAYED){changed(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);return true;}
        return false;
    }
    private void changed(int change){
        if(closed||!alive.getAsBoolean())return;
        if(change==AudioManager.AUDIOFOCUS_GAIN){
            paused=permanent=false;phoneWasActive=false;quietSince=-1;main.removeCallbacks(check);
        }else if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
            paused=true;permanent=change==AudioManager.AUDIOFOCUS_LOSS;phoneWasActive=false;quietSince=-1;
        }else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
            // Duck is not permission to resume a stream already paused for phone media.
            if(paused)return;
        }else return;
        output.accept(change);
        if(permanent){
            observePhoneAudio();
            if(!watching){watching=true;try{platform.watch(()->{observePhoneAudio();schedule(100);});}catch(RuntimeException ignored){DiagnosticStore.LOG.event(DiagnosticLog.Event.FOCUS,"WATCH_UNAVAILABLE");}}
            schedule(1000);
        }
    }
    private void observePhoneAudio(){
        if(closed||!paused||!permanent)return;
        try{if(platform.phoneAudioActive()){phoneWasActive=true;quietSince=-1;}}catch(RuntimeException ignored){}
    }
    private void schedule(long delay){
        if(closed||!paused||!permanent)return;
        main.removeCallbacks(check);main.postDelayed(check,delay);
    }
    private void checkReturn(){
        if(closed||!paused||!permanent||!alive.getAsBoolean())return;
        boolean busy;
        try{busy=platform.phoneAudioActive();}catch(RuntimeException ignored){busy=true;}
        long now=SystemClock.elapsedRealtime();
        if(busy)phoneWasActive=true;
        // Do not preempt a new focus owner that is still loading and has not played audio yet.
        if(busy||!phoneWasActive||!fresh.getAsBoolean()){quietSince=-1;schedule(1000);return;}
        if(quietSince<0){quietSince=now;schedule(750);return;}
        if(now-quietSince<750){schedule(750-(now-quietSince));return;}
        DiagnosticStore.LOG.event(DiagnosticLog.Event.FOCUS,"RETURN_REQUESTED");
        int result;
        try{result=platform.request(this::changed);}catch(RuntimeException ignored){result=AudioManager.AUDIOFOCUS_REQUEST_FAILED;}
        if(result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)changed(AudioManager.AUDIOFOCUS_GAIN);
        else if(result==AudioManager.AUDIOFOCUS_REQUEST_DELAYED){permanent=false;quietSince=-1;DiagnosticStore.LOG.event(DiagnosticLog.Event.FOCUS,"RETURN_DELAYED");}
        else{quietSince=-1;schedule(2000);}
    }
    @Override public void close(){
        if(closed)return;closed=true;main.removeCallbacks(check);platform.abandon();
    }
    private static final class AndroidPlatform implements Platform {
        private final Handler main;private final AudioManager manager;
        private AudioFocusRequest request;private AudioManager.AudioPlaybackCallback callback;
        AndroidPlatform(Handler main,AudioManager manager){this.main=main;this.manager=manager;}
        public int request(IntConsumer listener){
            if(request==null)request=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener::accept,main).build();
            return manager.requestAudioFocus(request);
        }
        public boolean phoneAudioActive(){
            // The CatchWave AudioTrack is paused before this query. No app identities are read.
            return manager.getMode()!=AudioManager.MODE_NORMAL||manager.isMusicActive()||!manager.getActivePlaybackConfigurations().isEmpty();
        }
        public void watch(Runnable changed){
            callback=new AudioManager.AudioPlaybackCallback(){
                @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> ignored){changed.run();}
            };
            manager.registerAudioPlaybackCallback(callback,main);
        }
        public void abandon(){
            if(callback!=null){try{manager.unregisterAudioPlaybackCallback(callback);}catch(RuntimeException ignored){DiagnosticStore.LOG.event(DiagnosticLog.Event.FOCUS,"WATCH_CLOSE_FAILED");}callback=null;}
            if(request!=null){try{manager.abandonAudioFocusRequest(request);}catch(RuntimeException ignored){DiagnosticStore.LOG.event(DiagnosticLog.Event.FOCUS,"ABANDON_FAILED");}request=null;}
        }
    }
}
