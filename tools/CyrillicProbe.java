package app.catchwave;
import org.json.*;
import java.lang.reflect.Method;
import java.nio.file.*;
public final class CyrillicProbe {
    private static final Track target=new Track("ru-example","Я удалю тебя из друзей","Papin Olimpos","",183183,0,0);
    private static final JSONArray candidates=new JSONArray();
    public static void main(String[] args) throws Exception {
        JSONObject response;
        if(args.length>1&&args[0].equals("--replay"))response=new JSONObject(Files.readString(Path.of(args[1])));
        else {
            JSONObject client=new JSONObject().put("clientName","WEB_REMIX").put("clientVersion","1.20260121.03.00").put("hl","en").put("gl","US");
            JSONObject body=new JSONObject().put("context",new JSONObject().put("client",client)).put("query",target.artist+" "+target.title).put("params","Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D");
            Method fetch=RecognitionClient.class.getDeclaredMethod("fetch",String.class,JSONObject.class);fetch.setAccessible(true);
            response=(JSONObject)fetch.invoke(null,"https://music.youtube.com/youtubei/v1/search?prettyPrint=false",body);
        }
        walk(response);System.out.println(candidates.toString(2));
        System.out.println("SELECTED="+RecognitionClient.parseYoutubeSearch(response,target));
        if(args.length==1)Files.writeString(Path.of(args[0]),new JSONObject().put("items",candidates).toString(2));
    }
    private static String column(JSONArray columns,int index) throws Exception {
        JSONObject text=columns.getJSONObject(index).getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text");JSONArray runs=text.optJSONArray("runs");StringBuilder s=new StringBuilder();
        if(runs!=null)for(int i=0;i<runs.length();i++)s.append(runs.getJSONObject(i).optString("text"));return s.toString();
    }
    private static void walk(Object node) throws Exception {
        if(node instanceof JSONArray a){for(int i=0;i<a.length();i++)walk(a.get(i));}
        if(node instanceof JSONObject o){
            JSONObject item=o.optJSONObject("musicResponsiveListItemRenderer");
            if(item!=null&&candidates.length()<12){JSONArray cols=item.optJSONArray("flexColumns");if(cols!=null&&cols.length()>1){String title=column(cols,0),artist=column(cols,1).split("[•·]",2)[0].trim();
                JSONObject slim=new JSONObject().put("musicResponsiveListItemRenderer",new JSONObject().put("flexColumns",cols).put("playlistItemData",item.optJSONObject("playlistItemData")));
                System.out.println("CANDIDATE title="+title+" artist="+artist+" matches="+target.matches(title,artist));candidates.put(slim);}}
            java.util.Iterator<String> keys=o.keys();while(keys.hasNext())walk(o.get(keys.next()));
        }
    }
}
