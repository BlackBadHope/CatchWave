// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Bounded local history of caught songs. User data, not diagnostics: titles stay on this phone,
 * are never exported automatically and never enter the diagnostic log. Disk failures never stop a session.
 */
final class MomentStore {
    static final int LIMIT=200;
    static final long MAX_FILE_BYTES=512*1024;
    private static volatile MomentStore shared;
    private final Path file;
    private final LongSupplier wallClock;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"CatchWave-moments");t.setDaemon(true);return t;
    });
    private final ArrayList<Moment> moments=new ArrayList<>(); // newest first
    private boolean loaded;
    private long failures;

    static MomentStore get(android.content.Context context){
        MomentStore current=shared;
        if(current==null)synchronized(MomentStore.class){
            current=shared;
            if(current==null)shared=current=new MomentStore(context.getFilesDir().toPath().resolve("moments.json"),System::currentTimeMillis);
        }
        return current;
    }
    MomentStore(Path file,LongSupplier wallClock){this.file=file;this.wallClock=wallClock;}

    synchronized List<Moment> all(){load();return new ArrayList<>(moments);}
    synchronized int size(){load();return moments.size();}
    synchronized long failures(){return failures;}

    synchronized Moment add(String key,String title,String artist,String youtubeUrl,long positionMs){
        load();
        long now=wallClock.getAsLong();
        // Re-syncing the song still playing refreshes one entry; a later return to it is a new moment.
        if(!moments.isEmpty()&&moments.get(0).key.equals(key)){
            Moment previous=moments.remove(0);
            String url=RecognitionClient.validMusicUrl(youtubeUrl)?youtubeUrl:previous.youtubeUrl;
            Moment refreshed=new Moment(previous.id,key,title,artist,url,positionMs,now,previous.favorite,previous.note);
            moments.add(0,refreshed);persist();return refreshed;
        }
        long id=now;
        for(Moment m:moments)if(m.id>=id)id=m.id+1;
        Moment moment=new Moment(id,key,title,artist,youtubeUrl,positionMs,now,false,"");
        moments.add(0,moment);
        while(moments.size()>LIMIT)removeOldestDisposable();
        persist();return moment;
    }
    /** Favorites are what the user chose to keep: evict the oldest ordinary entry first. */
    private void removeOldestDisposable(){
        for(int i=moments.size()-1;i>=0;i--)if(!moments.get(i).favorite){moments.remove(i);return;}
        moments.remove(moments.size()-1);
    }
    synchronized boolean setFavorite(long id,boolean value){
        load();Moment m=find(id);if(m==null||m.favorite==value)return m!=null;
        m.favorite=value;persist();return true;
    }
    synchronized boolean setNote(long id,String note){
        load();Moment m=find(id);if(m==null)return false;
        String bounded=note==null?"":note.length()<=Moment.NOTE_LIMIT?note:note.substring(0,Moment.NOTE_LIMIT);
        if(bounded.equals(m.note))return true;
        m.note=bounded;persist();return true;
    }
    synchronized boolean delete(long id){
        load();Moment m=find(id);if(m==null)return false;
        moments.remove(m);persist();return true;
    }
    synchronized void clear(){
        load();if(moments.isEmpty())return;
        moments.clear();persist();
    }
    /** A rejected match must not stay in history: drop the newest entry of that recording. */
    synchronized boolean removeNewest(String key){
        load();
        for(int i=0;i<moments.size();i++)if(moments.get(i).key.equals(key)){moments.remove(i);persist();return true;}
        return false;
    }
    /** The user picked the right recording: every entry of this song now points at it. */
    synchronized int replaceUrl(String key,String url){
        load();
        if(!RecognitionClient.validMusicUrl(url))return 0;
        int updated=0;
        for(int i=0;i<moments.size();i++){
            Moment m=moments.get(i);
            if(!m.key.equals(key)||url.equals(m.youtubeUrl))continue;
            m.youtubeUrl=url;updated++;
        }
        if(updated>0)persist();
        return updated;
    }
    /** A late catalog answer fills in links for entries caught without one. */
    synchronized int updateUrl(String key,String url){
        load();
        if(!RecognitionClient.validMusicUrl(url))return 0;
        int updated=0;
        for(Moment m:moments)if(m.key.equals(key)&&!RecognitionClient.validMusicUrl(m.youtubeUrl)){m.youtubeUrl=url;updated++;}
        if(updated>0)persist();
        return updated;
    }
    synchronized String exportText(){
        load();
        StringBuilder out=new StringBuilder();
        for(Moment m:moments)out.append(m.shareText()).append('\n');
        return out.toString();
    }

    private Moment find(long id){
        for(Moment m:moments)if(m.id==id)return m;
        return null;
    }
    private void load(){
        if(loaded)return;loaded=true;
        try{
            if(!Files.exists(file)||Files.size(file)>MAX_FILE_BYTES)return;
            String text=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);
            JSONArray array=new JSONObject(text).optJSONArray("moments");
            if(array==null)return;
            for(int i=0;i<array.length()&&moments.size()<LIMIT;i++){
                Moment m=Moment.fromJson(array.optJSONObject(i));
                if(m!=null&&find(m.id)==null)moments.add(m);
            }
            moments.sort((a,b)->Long.compare(b.id,a.id));
        }catch(Exception broken){failures++;moments.clear();}
    }
    private void persist(){
        final String snapshot=snapshot();
        writer.execute(()->{
            try{
                Path tmp=file.resolveSibling(file.getFileName()+".tmp");
                Files.createDirectories(file.getParent());
                Files.write(tmp,snapshot.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
            }catch(Exception ignored){synchronized(MomentStore.this){failures++;}}
        });
    }
    private String snapshot(){
        try{
            JSONArray array=new JSONArray();
            for(Moment m:moments)array.put(m.toJson());
            return new JSONObject().put("moments",array).toString();
        }catch(Exception impossible){failures++;return "{\"moments\":[]}";}
    }
    /** Test and shutdown aid: waits until queued writes reach the disk. */
    void flush()throws Exception{writer.submit(()->{}).get();}
}
