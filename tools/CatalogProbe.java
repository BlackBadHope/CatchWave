package app.catchwave;
import org.json.*;
import java.lang.reflect.Method;
import java.nio.file.*;
public final class CatalogProbe {
    public static void main(String[] args) throws Exception {
        Track target=new Track("probe",args[0],args[1],"",1000,1000,0);
        JSONObject response;
        if(args.length>3&&args[3].equals("--replay"))response=new JSONObject(Files.readString(Path.of(args[2])));
        else {
            JSONObject client=new JSONObject().put("clientName","WEB_REMIX").put("clientVersion","1.20260121.03.00").put("hl","en").put("gl","US");
            JSONObject body=new JSONObject().put("context",new JSONObject().put("client",client)).put("query",target.artist+" "+target.title).put("params","Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D");
            Method fetch=RecognitionClient.class.getDeclaredMethod("fetch",String.class,JSONObject.class);fetch.setAccessible(true);
            response=(JSONObject)fetch.invoke(null,"https://music.youtube.com/youtubei/v1/search?prettyPrint=false",body);
            Files.writeString(Path.of(args[2]),response.toString());
        }
        walk(response,target);System.out.println("SELECTED="+RecognitionClient.parseYoutubeSearch(response,target));
    }
    private static String text(JSONArray columns,int i) throws Exception {
        JSONArray runs=columns.getJSONObject(i).getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text").optJSONArray("runs");
        StringBuilder out=new StringBuilder();if(runs!=null)for(int k=0;k<runs.length();k++)out.append(runs.getJSONObject(k).optString("text"));return out.toString();
    }
    private static void walk(Object node,Track target) throws Exception {
        if(node instanceof JSONArray a){for(int i=0;i<a.length();i++)walk(a.get(i),target);}
        if(node instanceof JSONObject o){
            JSONObject item=o.optJSONObject("musicResponsiveListItemRenderer");
            if(item!=null){JSONArray cols=item.optJSONArray("flexColumns");JSONObject data=item.optJSONObject("playlistItemData");if(cols!=null&&cols.length()>1)System.out.println("title="+text(cols,0)+" artist="+text(cols,1)+" id="+(data==null?"":data.optString("videoId"))+" matches="+target.matches(text(cols,0),text(cols,1).split("[•·]",2)[0].trim()));}
            java.util.Iterator<String> keys=o.keys();while(keys.hasNext())walk(o.get(keys.next()),target);
        }
    }
}
