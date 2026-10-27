// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class CandidateTest {
    private Track track() { return new Track("1","Hello","Adele","",1000,100,0); }
    private static JSONObject item(String title,String artist,String id) throws Exception {
        JSONArray columns=new JSONArray();
        for(String value:new String[]{title,artist+" • Album • 2:30"})
            columns.put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                new JSONObject().put("text",new JSONObject().put("runs",new JSONArray().put(new JSONObject().put("text",value))))));
        return new JSONObject().put("musicResponsiveListItemRenderer",new JSONObject()
            .put("playlistItemData",new JSONObject().put("videoId",id)).put("flexColumns",columns));
    }
    private static String response(JSONArray items) throws Exception {
        return new JSONObject().put("contents",new JSONObject().put("items",items)).toString();
    }

    @Test public void alternativesKeepCollectingPastTheStrictMatchWithoutGreyedOrDuplicates() throws Exception {
        JSONObject greyed=item("Hello (Live)","Adele","ggggggggggg");
        greyed.getJSONObject("musicResponsiveListItemRenderer").put("musicItemRendererDisplayPolicy","MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT");
        JSONArray items=new JSONArray()
            .put(item("Hello Cover","Tribute Band","aaaaaaaaaaa"))
            .put(item("Hello","Adele","ccccccccccc"))
            .put(greyed)
            .put(item("Hello","Adele","ccccccccccc"))
            .put(item("Hello (Remix)","Adele","ddddddddddd"));
        String body=response(items);
        RecognitionClient c=new RecognitionClient(url -> new Response(200,body),12_000,6_000,()->0);
        Track t=track();
        RecognitionClient.Resolution result=c.resolveResult(t,new RequestScope());
        assertEquals(RecognitionClient.Kind.FOUND,result.kind);
        assertEquals("https://music.youtube.com/watch?v=ccccccccccc",result.url);
        List<RecognitionClient.Candidate> candidates=c.cachedCandidates(t);
        assertEquals(3,candidates.size());
        assertEquals("https://music.youtube.com/watch?v=aaaaaaaaaaa",candidates.get(0).url);
        assertEquals("https://music.youtube.com/watch?v=ccccccccccc",candidates.get(1).url);
        assertEquals("https://music.youtube.com/watch?v=ddddddddddd",candidates.get(2).url);
        assertEquals("Hello (Remix)",candidates.get(2).title);
    }

    @Test public void noStrictMatchStillLeavesAlternativesForTheUser() throws Exception {
        JSONArray items=new JSONArray()
            .put(item("Hello (Slowed)","Adele","aaaaaaaaaaa"))
            .put(item("Hello","Somebody Else","bbbbbbbbbbb"));
        String body=response(items);
        RecognitionClient c=new RecognitionClient(url -> new Response(200,body),12_000,6_000,()->0);
        Track t=track();
        assertEquals(RecognitionClient.Kind.NO_MATCH,c.resolveResult(t,new RequestScope()).kind);
        assertEquals(2,c.cachedCandidates(t).size());
    }

    @Test public void candidateListIsCappedAndExpiresWithTheCacheClock() throws Exception {
        JSONArray items=new JSONArray();
        for(int i=0;i<10;i++)items.put(item("Hello version "+i,"Adele","aaaaaaaaaa"+(char)('a'+i)));
        AtomicLong clock=new AtomicLong(100);
        RecognitionClient c=new RecognitionClient(url -> {
            try{return new Response(200,response(items));}catch(Exception e){throw new RuntimeException(e);}
        },12_000,6_000,clock::get);
        Track t=track();
        c.resolveResult(t,new RequestScope());
        assertEquals(RecognitionClient.CANDIDATE_LIMIT,c.cachedCandidates(t).size());
        clock.addAndGet(RecognitionClient.CACHE_TTL_MS);
        assertTrue(c.cachedCandidates(t).isEmpty());
    }

    @Test public void fetchCandidatesSearchesOnceAndFailureMeansEmptyNotError() throws Exception {
        AtomicInteger opened=new AtomicInteger();
        String body=response(new JSONArray().put(item("Hello Cover","Tribute Band","aaaaaaaaaaa")));
        RecognitionClient c=new RecognitionClient(url -> { opened.incrementAndGet(); return new Response(200,body); },12_000,6_000,()->0);
        Track t=track();
        assertEquals(1,c.fetchCandidates(t,new RequestScope()).size());
        assertEquals(1,c.fetchCandidates(t,new RequestScope()).size());
        assertEquals(1,opened.get());
        RecognitionClient failing=new RecognitionClient(url -> new Response(503,"{}"),12_000,6_000,()->0);
        assertTrue(failing.fetchCandidates(track(),new RequestScope()).isEmpty());
        RequestScope cancelled=new RequestScope();cancelled.cancel();
        assertTrue(failing.fetchCandidates(track(),cancelled).isEmpty());
    }

    @Test public void pinnedChoiceReplacesTheAutomaticPickForLaterLookups() throws Exception {
        String body=response(new JSONArray().put(item("Hello","Adele","ccccccccccc")).put(item("Hello (Remix)","Adele","ddddddddddd")));
        AtomicInteger opened=new AtomicInteger();
        RecognitionClient c=new RecognitionClient(url -> { opened.incrementAndGet(); return new Response(200,body); },12_000,6_000,()->0);
        Track t=track();
        assertEquals("https://music.youtube.com/watch?v=ccccccccccc",c.resolveResult(t,new RequestScope()).url);
        RecognitionClient.Candidate remix=c.cachedCandidates(t).get(1);
        c.pin(t,remix);
        assertEquals("https://music.youtube.com/watch?v=ddddddddddd",t.youtubeUrl);
        assertEquals("Hello (Remix)",t.playbackTitle);
        Track again=track();
        assertEquals("https://music.youtube.com/watch?v=ddddddddddd",c.cached(again));
        assertEquals("Hello (Remix)",again.playbackTitle);
        assertEquals(1,opened.get());
        c.pin(t,new RecognitionClient.Candidate("x","y","javascript:alert(1)"));
        assertEquals("https://music.youtube.com/watch?v=ddddddddddd",c.cached(track()));
    }

    private static final class Response extends HttpURLConnection {
        private final int status; private final byte[] body;
        Response(int status,String body){super(null);this.status=status;this.body=body.getBytes(StandardCharsets.UTF_8);}
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public int getResponseCode(){return status;}
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
    }
}
