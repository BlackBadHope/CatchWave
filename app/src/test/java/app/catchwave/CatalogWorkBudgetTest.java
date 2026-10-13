// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.io.IOException;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogWorkBudgetTest {
    @Test public void joinedRunsCannotExpandOneMatchingFieldPastItsBound()throws Exception {
        JSONArray runs=new JSONArray().put(new JSONObject().put("text","X".repeat(16384))).put(new JSONObject().put("text","X".repeat(16384)));
        JSONObject text=new JSONObject().put("runs",runs);
        JSONObject title=new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",new JSONObject().put("text",text));
        JSONObject artist=new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",new JSONObject().put("text",new JSONObject().put("simpleText","Artist")));
        JSONObject item=new JSONObject().put("playlistItemData",new JSONObject().put("videoId","aaaaaaaaaaa")).put("flexColumns",new JSONArray().put(title).put(artist));
        JSONObject response=new JSONObject().put("musicResponsiveListItemRenderer",item);
        BoundedJson.validate(response,()->{});
        try{RecognitionClient.parseYoutubeSearch(response,new Track("k","X","Artist","",0,0,0));fail("Expanded text reached matching without a field bound");}
        catch(IOException expected){}
    }
    @Test public void longWhitespaceDoesNotExceedTheMatchingWorkUnit() {
        Track track=new Track("k","X","Artist","",0,0,0);
        assertFalse(track.matches(" ".repeat(20000)+"X","Artist"));
        assertTrue(new Track("k","X (feat. Guest)","Artist","",0,0,0).matches("X","Artist & Guest"));
    }
}
