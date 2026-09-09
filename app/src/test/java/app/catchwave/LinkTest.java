package app.catchwave;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class LinkTest {
    @Test public void featuredCreditFormattingDoesNotHideDeathBed() throws Exception {
        Track t=new Track("1","death bed (coffee for your head) [feat. beabadoobee]","Powfu & beabadoobee","",1,0,0);
        assertEquals("https://music.youtube.com/watch?v=JApegyYlvyY",RecognitionClient.parseYoutubeSearch(item("death bed (coffee for your head)","Powfu & beabadoobee","JApegyYlvyY"),t));
        assertEquals("death bed (coffee for your head)",t.playbackTitle);assertEquals("Powfu & beabadoobee",t.playbackArtist);
        assertTrue(t.matches("death bed (coffee for your head) (feat. beabadoobee)","Powfu"));
        assertFalse(t.matches("death bed (coffee for your head)","Powfu"));
        assertFalse(t.matches("death bed (coffee for your head)","Sydekic"));
        assertFalse(t.matches("death bed (coffee for your head) (Remix)","Powfu & beabadoobee"));
    }
    @Test public void missingFeaturedPerformerDoesNotBecomeSameRecording(){Track t=new Track("1","Hello (feat. Guest)","Singer","",1,0,0);assertFalse(t.matches("Hello","Singer"));assertTrue(t.matches("Hello","Singer & Guest"));assertFalse(t.matches("Hello (feat. Other)","Singer"));}
    @Test public void guestNamedLiveIsNotALiveRecording(){
        Track t=new Track("1","Hello (feat. Live)","Singer","",1,0,0);
        assertTrue(t.matches("Hello","Singer & Live"));
        assertFalse(t.matches("Hello (Live)","Singer & Live"));
        assertFalse(t.matches("Hello (Remix)","Singer & Live"));
        assertFalse(t.matches("Hello (Cover)","Singer & Live"));
    }
    @Test public void presentationLabelsDoNotEraseGenuineTitleWords(){
        Track t=new Track("1","No More Lyrics","Singer","",1,0,0);
        assertFalse(t.matches("No More","Singer"));
        assertTrue(t.matches("No More Lyrics (Official Video)","Singer"));
        assertTrue(t.matches("No More Lyrics | Lyrics","Singer"));
        assertFalse(new Track("2","No More","Singer","",1,0,0).matches("No More Lyrics","Singer"));
        assertEquals("no more lyrics",Track.normalize("No More Lyrics"));
    }
    @Test public void explicitPrimaryAndFeaturedPerformersCannotSwapRoles(){
        Track t=new Track("1","Hello (feat. Guest)","Singer","",1,0,0);
        assertFalse(t.matches("Hello (feat. Singer)","Guest"));
        assertFalse(t.matches("Hello","Guest feat. Singer"));
        assertFalse(t.matches("Hello","Guest (feat. Singer)"));
        assertTrue(t.matches("Hello","Singer feat. Guest"));
        assertTrue(t.matches("Hello","Singer (feat. Guest)"));
        assertTrue(t.matches("Hello","Singer & Guest"));
    }
    @Test public void bandNameContainingAndRemainsOnePerformer(){
        Track t=new Track("1","Hello (feat. Guest)","The Head and the Heart","",1,0,0);
        assertTrue(t.matches("Hello","The Head and the Heart & Guest"));
        assertFalse(t.matches("Hello","The Head & the Heart & Guest"));
    }
    @Test public void featuredCreditCannotSubstituteMissingMainArtist(){Track t=new Track("1","Hello (feat. Guest)","","",1,0,0);assertFalse(t.matches("Hello","Guest"));}
    @Test public void localizedFeaturedCreditsStillRequireSamePerformers(){
        Track t=new Track("1","Party Rock Anthem (feat. Lauren Bennett & GoonRock)","LMFAO","",1,0,0);
        assertTrue(t.matches("Party Rock Anthem (за участю виконавців Lauren Bennett і GoonRock)","LMFAO"));
        assertFalse(t.matches("Party Rock Anthem (за участю виконавців Other)","LMFAO"));
    }
    @Test public void transliteratedArtistResolvesToCyrillicCatalogWithoutLooseningTitle() throws Exception {
        Track t=new Track("1","Я удалю тебя из друзей","Papin Olimpos","",1,0,0);
        JSONArray response=new JSONArray().put(item("Я удаляю тебя из друзей","Benson","aaaaaaaaaaa")).put(item("Я удалю тебя из друзей","Папин Олимпос","XSu7wWyrN0E"));
        assertEquals("https://music.youtube.com/watch?v=XSu7wWyrN0E",RecognitionClient.parseYoutubeSearch(response,t));
    }
    private JSONObject item(String title,String artist,String video) throws Exception {
        JSONArray columns=new JSONArray();
        for(String s:new String[]{title,artist+" • Album • 2:30"})columns.put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",new JSONObject().put("text",new JSONObject().put("runs",new JSONArray().put(new JSONObject().put("text",s))))));
        return new JSONObject().put("musicResponsiveListItemRenderer",new JSONObject().put("playlistItemData",new JSONObject().put("videoId",video)).put("flexColumns",columns));
    }
    @Test public void youtubeSearchSkipsOtherArtistsAndVersions() throws Exception {
        Track wanted=new Track("1","Hello","Adele","",10,10,0);
        JSONArray response=new JSONArray().put(item("Hello","Lionel Richie","aaaaaaaaaaa")).put(item("Hello (Live)","Adele","bbbbbbbbbbb")).put(item("Hello","Adele","ccccccccccc"));
        assertEquals("https://music.youtube.com/watch?v=ccccccccccc",RecognitionClient.parseYoutubeSearch(response,wanted));
    }
    @Test public void noExactResultLeavesSelectionToUser() throws Exception {assertEquals("",RecognitionClient.parseYoutubeSearch(item("Hello","Other artist","aaaaaaaaaaa"),new Track("1","Hello","Adele","",1,0,0)));}
    @Test public void songLinkIgnoresPlaceholdersAndForeignHosts() throws Exception {
        String html="<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"pageData\":{\"sections\":[{\"links\":[{\"platform\":\"youtubeMusic\"},{\"platform\":\"youtubeMusic\",\"url\":\"https://bad.test/\"},{\"platform\":\"youtubeMusic\",\"url\":\"https://music.youtube.com/watch?v=Pk9vPMurHVo\"}]}]}}}}</script>";
        assertEquals("https://music.youtube.com/watch?v=Pk9vPMurHVo",RecognitionClient.parseSongLink(html));
    }
    @Test public void unavailablePlayerTimestampIsNotExtrapolated(){assertEquals(-1,SyncMath.playerPosition(0,0,1,900000,true));}
}
