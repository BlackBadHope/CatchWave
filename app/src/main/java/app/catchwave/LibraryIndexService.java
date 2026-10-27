// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.app.*;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the offline library index from the phone's own music files.
 * Explicit start, visible notification, cancellable; audio is decoded in RAM and
 * only landmark hashes are kept. Nothing is uploaded anywhere.
 */
public final class LibraryIndexService extends Service {
    public static final String START="app.catchwave.library.START",STOP="app.catchwave.library.STOP";
    private static final String CHANNEL="catchwave_library";
    private static final int NOTIFICATION=30;
    private static final long MIN_DURATION_MS=30_000,MAX_DURATION_MS=35*60_000;
    /** Snapshot for the settings screen; written only by the worker. */
    public static volatile String state="";
    public static volatile boolean running;
    private volatile boolean cancelled;
    private Thread worker;

    @Override public void onCreate(){L.init(this);
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,L.t("CatchWave · Library index"),NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||STOP.equals(intent.getAction())){cancelled=true;return START_NOT_STICKY;}
        if(!START.equals(intent.getAction())||running)return START_NOT_STICKY;
        running=true;cancelled=false;state=L.t("Reading the music list…");
        startForeground(NOTIFICATION,notification(state),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        worker=new Thread(this::scan,"CatchWave-library");
        worker.start();
        return START_NOT_STICKY;
    }
    private Notification notification(String text){
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,LibraryIndexService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_wave)
            .setContentTitle(L.t("Indexing your music for offline catching")).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,L.t("Stop"),stop).build()).build();
    }
    private void notifyProgress(String text){
        state=text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text));
    }
    private static final class Item {
        final Uri uri;final String key,title,artist;final long durationMs;
        Item(Uri uri,String key,String title,String artist,long durationMs){this.uri=uri;this.key=key;this.title=title;this.artist=artist;this.durationMs=durationMs;}
    }
    private void scan(){
        LibraryStore store=LibraryStore.get(this);
        int added=0,failed=0;
        try{
            List<Item> items=mediaItems();
            Set<String> valid=new HashSet<>();
            for(Item item:items)valid.add(item.key);
            // Files that changed or disappeared leave the index first.
            for(LibraryIndex.Song song:store.index().all())
                if(!valid.contains(song.mediaId))store.index().remove(song.id);
            int total=items.size(),done=0;
            for(Item item:items){
                if(cancelled)break;
                done++;
                if(store.index().has(item.key))continue;
                if(store.index().songCount()>=LibraryIndex.MAX_SONGS)break;
                notifyProgress(done+" / "+total);
                StreamingLandmarks stream=new StreamingLandmarks();
                boolean decoded=AudioDecode.decode(this,item.uri,(pcm,length)->{
                    if(cancelled||stream.overflowed())return false;
                    stream.feed(pcm,length);return true;
                });
                if(cancelled)break;
                if(!decoded&&stream.frames()==0){failed++;continue;}
                if(store.index().add(item.key,item.title,item.artist,item.durationMs,stream.finish())!=null)added++;
                if(added%10==0)store.save();
            }
            store.save();
            state=cancelled?L.t("Indexing stopped"):String.format(java.util.Locale.ROOT,L.t("Indexed %d songs (%d new, %d unreadable)"),store.songCount(),added,failed);
        }catch(Exception broken){
            store.save();
            state=L.t("Indexing failed; the collected part is saved");
        }finally{
            running=false;
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    private List<Item> mediaItems(){
        List<Item> items=new ArrayList<>();
        String[] projection={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.DATE_MODIFIED,MediaStore.Audio.Media.IS_MUSIC};
        try(Cursor cursor=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,projection,
            MediaStore.Audio.Media.IS_MUSIC+"!=0",null,MediaStore.Audio.Media.DATE_MODIFIED+" DESC")){
            if(cursor==null)return items;
            while(cursor.moveToNext()&&items.size()<LibraryIndex.MAX_SONGS){
                long id=cursor.getLong(0),duration=cursor.getLong(3),modified=cursor.getLong(4);
                if(duration<MIN_DURATION_MS||duration>MAX_DURATION_MS)continue;
                String title=cursor.getString(1),artist=cursor.getString(2);
                items.add(new Item(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id),
                    "audio:"+id+":"+modified,title==null?"":title,artist==null?"":artist,duration));
            }
        }catch(RuntimeException ignored){}
        return items;
    }
    @Override public void onDestroy(){cancelled=true;running=false;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
