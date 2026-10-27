// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * On-phone fingerprint index of the user's own music. Entirely offline user data:
 * built from local files with the owner's media permission, stored privately,
 * never uploaded, and removable by clearing it. Not a world catalog.
 */
final class LibraryIndex {
    static final int MAX_SONGS=2000;
    static final int MAX_SONG_FRAMES=0xFFFF;          // ~35 minutes per track at 32 ms hops.
    static final long MAX_FILE_BYTES=96L*1024*1024;
    private static final int MAGIC=0x43574C49,VERSION=1; // "CWLI"

    static final class Song {
        final int id;final String mediaId,title,artist;final long durationMs;
        Song(int id,String mediaId,String title,String artist,long durationMs){
            this.id=id;this.mediaId=mediaId==null?"":mediaId;this.title=title==null?"":title;this.artist=artist==null?"":artist;this.durationMs=Math.max(0,durationMs);
        }
    }
    static final class Match {
        final Song song;final long offsetMs;final int score,runnerUp;
        Match(Song song,long offsetMs,int score,int runnerUp){this.song=song;this.offsetMs=offsetMs;this.score=score;this.runnerUp=runnerUp;}
    }

    private final Map<Integer,int[]> postings=new HashMap<>();
    private final Map<Integer,Song> songs=new HashMap<>();
    private final Map<String,Integer> byMediaId=new HashMap<>();
    private int nextId=1;
    private long landmarkCount;

    synchronized int songCount(){return songs.size();}
    synchronized long landmarkCount(){return landmarkCount;}
    synchronized boolean has(String mediaId){return byMediaId.containsKey(mediaId);}
    synchronized List<Song> all(){return new ArrayList<>(songs.values());}

    synchronized Song add(String mediaId,String title,String artist,long durationMs,List<LocalFingerprint.Landmark> landmarks){
        if(songs.size()>=MAX_SONGS||mediaId==null||mediaId.isEmpty()||landmarks==null||landmarks.isEmpty())return null;
        Integer existing=byMediaId.get(mediaId);
        if(existing!=null)remove(existing);
        Song song=new Song(nextId++,mediaId,title,artist,durationMs);
        Map<Integer,List<Integer>> grouped=new HashMap<>();
        for(LocalFingerprint.Landmark mark:landmarks){
            if(mark.time<0||mark.time>MAX_SONG_FRAMES)continue;
            grouped.computeIfAbsent(mark.hash,k->new ArrayList<>()).add(song.id<<16|mark.time);
        }
        for(Map.Entry<Integer,List<Integer>> entry:grouped.entrySet()){
            int[] old=postings.get(entry.getKey());
            List<Integer> added=entry.getValue();
            int[] merged=new int[(old==null?0:old.length)+added.size()];
            int at=0;
            if(old!=null){System.arraycopy(old,0,merged,0,old.length);at=old.length;}
            for(int value:added)merged[at++]=value;
            postings.put(entry.getKey(),merged);
            landmarkCount+=added.size();
        }
        songs.put(song.id,song);byMediaId.put(mediaId,song.id);
        return song;
    }
    synchronized boolean remove(int songId){
        Song song=songs.remove(songId);
        if(song==null)return false;
        byMediaId.remove(song.mediaId);
        Iterator<Map.Entry<Integer,int[]>> entries=postings.entrySet().iterator();
        while(entries.hasNext()){
            Map.Entry<Integer,int[]> entry=entries.next();
            int kept=0;
            for(int value:entry.getValue())if(value>>>16!=songId)kept++;
            if(kept==entry.getValue().length)continue;
            landmarkCount-=entry.getValue().length-kept;
            if(kept==0){entries.remove();continue;}
            int[] smaller=new int[kept];int at=0;
            for(int value:entry.getValue())if(value>>>16!=songId)smaller[at++]=value;
            entry.setValue(smaller);
        }
        return true;
    }
    synchronized void clear(){postings.clear();songs.clear();byMediaId.clear();landmarkCount=0;nextId=1;}

    /** Votes on (song, time shift); a confident winner names the song and where the sample starts in it. */
    synchronized Match query(short[] audio){
        List<LocalFingerprint.Landmark> marks=LocalFingerprint.landmarks(audio);
        if(marks.isEmpty()||songs.isEmpty())return null;
        Map<Long,Integer> votes=new HashMap<>();
        for(LocalFingerprint.Landmark mark:marks){
            int[] entries=postings.get(mark.hash);
            if(entries==null)continue;
            for(int value:entries){
                int songId=value>>>16,time=value&0xFFFF;
                int delta=time-mark.time;
                if(delta<0)continue;
                votes.merge((long)songId<<32|delta,1,Integer::sum);
            }
        }
        if(votes.isEmpty())return null;
        long bestKey=0;int best=0,runnerUp=0;
        for(Map.Entry<Long,Integer> vote:votes.entrySet()){
            long key=vote.getKey();
            int combined=vote.getValue()+votes.getOrDefault(key+1,0)+((key&0xFFFFFFFFL)>0?votes.getOrDefault(key-1,0):0);
            int songId=(int)(key>>>32);
            if(combined>best){
                if((int)(bestKey>>>32)!=songId)runnerUp=best;
                best=combined;bestKey=key;
            }else if(combined>runnerUp&&(int)(bestKey>>>32)!=songId)runnerUp=combined;
        }
        Song song=songs.get((int)(bestKey>>>32));
        if(song==null)return null;
        long delta=bestKey&0xFFFFFFFFL;
        // Weighted sub-bin refinement over the winning neighborhood.
        long weighted=0;int weight=0;
        for(long d=Math.max(0,delta-1);d<=delta+1;d++){
            int v=votes.getOrDefault((long)song.id<<32|d,0);
            weighted+=d*v;weight+=v;
        }
        double refined=weight==0?delta:(double)weighted/weight;
        return new Match(song,Math.round(refined*LocalFingerprint.HOP_MS),best,runnerUp);
    }

    synchronized void save(Path file)throws IOException{
        Path tmp=file.resolveSibling(file.getFileName()+".tmp");
        Files.createDirectories(file.getParent());
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(nextId);out.writeInt(songs.size());
            for(Song song:songs.values()){
                out.writeInt(song.id);writeText(out,song.mediaId);writeText(out,song.title);writeText(out,song.artist);out.writeLong(song.durationMs);
            }
            out.writeInt(postings.size());
            for(Map.Entry<Integer,int[]> entry:postings.entrySet()){
                out.writeInt(entry.getKey());out.writeInt(entry.getValue().length);
                for(int value:entry.getValue())out.writeInt(value);
            }
        }
        Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
    }
    static LibraryIndex load(Path file){
        LibraryIndex index=new LibraryIndex();
        try{
            if(!Files.exists(file)||Files.size(file)>MAX_FILE_BYTES)return index;
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION)return index;
                index.nextId=Math.max(1,in.readInt());
                int songCount=in.readInt();
                if(songCount<0||songCount>MAX_SONGS)return new LibraryIndex();
                for(int i=0;i<songCount;i++){
                    Song song=new Song(in.readInt(),readText(in),readText(in),readText(in),in.readLong());
                    index.songs.put(song.id,song);index.byMediaId.put(song.mediaId,song.id);
                }
                int hashCount=in.readInt();
                if(hashCount<0)return new LibraryIndex();
                for(int i=0;i<hashCount;i++){
                    int hash=in.readInt(),length=in.readInt();
                    if(length<0||length>4_000_000)return new LibraryIndex();
                    int[] values=new int[length];
                    for(int j=0;j<length;j++)values[j]=in.readInt();
                    index.postings.put(hash,values);
                    index.landmarkCount+=length;
                }
            }
        }catch(Exception broken){return new LibraryIndex();}
        return index;
    }
    private static void writeText(DataOutputStream out,String value)throws IOException{
        byte[] bytes=(value.length()>Moment.TEXT_LIMIT?value.substring(0,Moment.TEXT_LIMIT):value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);out.write(bytes);
    }
    private static String readText(DataInputStream in)throws IOException{
        int length=in.readInt();
        if(length<0||length>4*Moment.TEXT_LIMIT)throw new IOException("text bounds");
        byte[] bytes=new byte[length];in.readFully(bytes);
        return new String(bytes,StandardCharsets.UTF_8);
    }
}
