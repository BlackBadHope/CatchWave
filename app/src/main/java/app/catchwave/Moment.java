// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/** One caught song: user content separate from diagnostics, stored only on this phone. */
public final class Moment {
    static final int TEXT_LIMIT=300,NOTE_LIMIT=500,URL_LIMIT=2048;
    public final long id;
    public final String key,title,artist;
    public final long positionMs,caughtAtMs;
    public volatile String youtubeUrl;
    public volatile boolean favorite;
    public volatile String note;
    Moment(long id,String key,String title,String artist,String youtubeUrl,long positionMs,long caughtAtMs,boolean favorite,String note){
        this.id=id;this.key=bounded(key,TEXT_LIMIT);this.title=bounded(title,TEXT_LIMIT);this.artist=bounded(artist,TEXT_LIMIT);
        this.youtubeUrl=bounded(youtubeUrl,URL_LIMIT);this.positionMs=Math.max(0,positionMs);this.caughtAtMs=Math.max(0,caughtAtMs);
        this.favorite=favorite;this.note=bounded(note,NOTE_LIMIT);
    }
    private static String bounded(String value,int limit){
        if(value==null)return "";
        return value.length()<=limit?value:value.substring(0,limit);
    }
    /** The direct recording when the catalog confirmed one, otherwise a search for the same title. */
    public String link(){
        if(RecognitionClient.validMusicUrl(youtubeUrl))return youtubeUrl;
        try{return "https://music.youtube.com/search?q="+URLEncoder.encode(artist+" "+title,"UTF-8");}
        catch(UnsupportedEncodingException impossible){return "https://music.youtube.com";}
    }
    public String positionLabel(){
        long seconds=positionMs/1000;
        return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
    public String shareText(){
        String line=title+" — "+artist+" · "+positionLabel()+" · "+link();
        return note.isEmpty()?line:line+"\n"+note;
    }
    JSONObject toJson()throws JSONException{
        return new JSONObject().put("id",id).put("key",key).put("title",title).put("artist",artist)
            .put("url",youtubeUrl).put("positionMs",positionMs).put("caughtAtMs",caughtAtMs)
            .put("favorite",favorite).put("note",note);
    }
    /** Returns null for an entry this build cannot trust; the store skips it and keeps the rest. */
    static Moment fromJson(JSONObject value){
        if(value==null)return null;
        long id=value.optLong("id",Long.MIN_VALUE);
        String key=value.optString("key",""),title=value.optString("title",""),artist=value.optString("artist","");
        if(id<=0||key.isEmpty()||title.isEmpty()||artist.isEmpty())return null;
        return new Moment(id,key,title,artist,value.optString("url",""),
            value.optLong("positionMs",0),value.optLong("caughtAtMs",0),
            value.optBoolean("favorite",false),value.optString("note",""));
    }
}
