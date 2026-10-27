// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The user's explicit "this is the right recording" choices, one per recognized key.
 * Private on-phone preferences; survives restarts so a corrected match stays corrected.
 */
final class MatchPins {
    static final int LIMIT=40;
    private static final String PREFS="match_pins",KEY="pins";
    private MatchPins(){}

    static final class Pin {
        final String key,url,title,artist;final long at;
        Pin(String key,String url,String title,String artist,long at){this.key=key;this.url=url;this.title=title;this.artist=artist;this.at=at;}
    }

    static synchronized Pin get(Context context,String key){
        if(key==null||key.isEmpty())return null;
        for(Pin pin:load(context))if(pin.key.equals(key))return pin;
        return null;
    }
    static synchronized void put(Context context,String key,String url,String title,String artist){
        if(key==null||key.isEmpty()||!RecognitionClient.validMusicUrl(url))return;
        List<Pin> pins=load(context);
        long at=System.currentTimeMillis();
        for(Pin pin:pins)if(pin.at>=at)at=pin.at+1; // Keep recency strictly ordered even on a coarse clock.
        pins.removeIf(pin->pin.key.equals(key));
        pins.add(0,new Pin(key,url,bounded(title),bounded(artist),at));
        while(pins.size()>LIMIT)pins.remove(pins.size()-1);
        save(context,pins);
    }
    static synchronized void remove(Context context,String key){
        if(key==null||key.isEmpty())return;
        List<Pin> pins=load(context);
        if(pins.removeIf(pin->pin.key.equals(key)))save(context,pins);
    }

    private static String bounded(String value){
        if(value==null)return "";
        return value.length()<=Moment.TEXT_LIMIT?value:value.substring(0,Moment.TEXT_LIMIT);
    }
    private static List<Pin> load(Context context){
        List<Pin> pins=new ArrayList<>();
        try{
            String text=prefs(context).getString(KEY,"");
            if(text.isEmpty()||text.length()>64*1024)return pins;
            JSONObject root=new JSONObject(text);
            Iterator<String> keys=root.keys();
            while(keys.hasNext()&&pins.size()<LIMIT){
                String key=keys.next();JSONObject value=root.optJSONObject(key);
                if(value==null)continue;
                String url=value.optString("url","");
                if(!RecognitionClient.validMusicUrl(url))continue;
                pins.add(new Pin(key,url,value.optString("title",""),value.optString("artist",""),value.optLong("at",0)));
            }
            pins.sort((a,b)->Long.compare(b.at,a.at));
        }catch(Exception broken){pins.clear();}
        return pins;
    }
    private static void save(Context context,List<Pin> pins){
        try{
            JSONObject root=new JSONObject();
            for(Pin pin:pins)root.put(pin.key,new JSONObject().put("url",pin.url).put("title",pin.title).put("artist",pin.artist).put("at",pin.at));
            prefs(context).edit().putString(KEY,root.toString()).apply();
        }catch(Exception ignored){}
    }
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
}
