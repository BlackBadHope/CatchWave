package app.catchwave;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class RecognitionClientTest {
    private static final String URL="https://music.youtube.com/watch?v=ccccccccccc";
    private Track track() { return new Track("1","Hello","Adele","",1000,100,0); }
    private RecognitionClient client(RecognitionClient.ConnectionFactory factory,long timeoutMs) {
        return new RecognitionClient(factory,12_000,timeoutMs,() -> 0);
    }
    private static JSONObject item(String title,String artist,String id) throws Exception {
        JSONArray columns=new JSONArray();
        for(String value:new String[]{title,artist+" • Album • 2:30"})
            columns.put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                new JSONObject().put("text",new JSONObject().put("runs",new JSONArray().put(new JSONObject().put("text",value))))));
        return new JSONObject().put("musicResponsiveListItemRenderer",new JSONObject()
            .put("playlistItemData",new JSONObject().put("videoId",id)).put("flexColumns",columns));
    }
    private static String catalog() throws Exception {
        return new JSONObject().put("contents",new JSONObject().put("items",
            new JSONArray().put(item("Hello","Adele","ccccccccccc")))).toString();
    }

    @Test public void malformedCatalogItemDoesNotDiscardLaterExactMatch() throws Exception {
        JSONObject malformed=item("Hello","Adele","aaaaaaaaaaa");
        malformed.getJSONObject("musicResponsiveListItemRenderer").getJSONArray("flexColumns")
            .getJSONObject(0).getJSONObject("musicResponsiveListItemFlexColumnRenderer").remove("text");
        JSONObject badRun=item("Hello","Adele","bbbbbbbbbbb");
        badRun.getJSONObject("musicResponsiveListItemRenderer").getJSONArray("flexColumns")
            .getJSONObject(0).getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text")
            .getJSONArray("runs").put(0,JSONObject.NULL);
        assertEquals(URL,RecognitionClient.parseYoutubeSearch(new JSONArray().put(malformed).put(badRun)
            .put(item("Hello","Adele","ccccccccccc")),track()));
    }

    @Test public void partialArtistRunCannotBecomeDifferentExactArtist() throws Exception {
        JSONObject malformed=item("Hello","Adele","aaaaaaaaaaa");
        malformed.getJSONObject("musicResponsiveListItemRenderer").getJSONArray("flexColumns")
            .getJSONObject(1).getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text")
            .put("runs",new JSONArray().put(new JSONObject().put("text","Adele")).put(new JSONObject()));
        assertEquals("",RecognitionClient.parseYoutubeSearch(malformed,track()));
    }

    @Test public void recognizedTrackRequiresKeyTitleAndArtist() throws Exception {
        for(String required:new String[]{"key","title","subtitle"})for(Object missing:new Object[]{"", "  ", JSONObject.NULL, 42}) {
            JSONObject data=new JSONObject().put("key","1").put("title","Hello").put("subtitle","Adele").put(required,missing);
            JSONObject result=new JSONObject().put("track",data).put("matches",new JSONArray().put(new JSONObject().put("offset",1)));
            assertNull("invalid "+required+"="+missing,RecognitionClient.parse(result,1));
        }
    }

    @Test public void validRecognitionSkipsMalformedOptionalActions() throws Exception {
        JSONObject data=new JSONObject().put("key","1").put("title","Hello").put("subtitle","Adele")
            .put("hub",new JSONObject().put("actions",new JSONArray().put(JSONObject.NULL).put(new JSONObject().put("id","123"))));
        Track result=RecognitionClient.parse(new JSONObject().put("track",data)
            .put("matches",new JSONArray().put(new JSONObject().put("offset",1.25))),50);
        assertNotNull(result); assertEquals("123",result.appleId); assertEquals(1250,result.offsetMs);
    }

    @Test public void providerFailuresStayDistinctFromNoMatch() throws Exception {
        for(int status:new int[]{429,503,403}) {
            RecognitionClient c=client(url -> new ResponseConnection(status,"{}"),6000);
            RecognitionClient.Resolution result=c.resolveResult(track(),new RequestScope());
            assertEquals(status==429?RecognitionClient.Kind.RATE_LIMITED:RecognitionClient.Kind.UNAVAILABLE,result.kind);
            assertEquals("",result.url); assertFalse(result.message.isEmpty());
        }
        RecognitionClient c=client(url -> { throw new UnknownHostException("offline fixture"); },6000);
        assertEquals(RecognitionClient.Kind.UNAVAILABLE,c.resolveResult(track(),new RequestScope()).kind);
    }

    @Test public void malformedJsonAndUnknownCatalogShapeAreBadResponse() throws Exception {
        for(String response:new String[]{"not JSON","{}","{\"error\":{\"message\":\"backend failure\"}}","{\"contents\":null}"}) {
            RecognitionClient c=client(url -> new ResponseConnection(200,response),6000);
            assertEquals(response,RecognitionClient.Kind.BAD_RESPONSE,c.resolveResult(track(),new RequestScope()).kind);
        }
    }

    @Test public void wellFormedEmptyCatalogIsNoMatch() {
        RecognitionClient c=client(url -> new ResponseConnection(200,"{\"contents\":{\"items\":[]}}"),6000);
        assertEquals(RecognitionClient.Kind.NO_MATCH,c.resolveResult(track(),new RequestScope()).kind);
    }

    @Test public void whollyMalformedCatalogIsBadResponseInsteadOfNoMatch() throws Exception {
        JSONObject broken=item("Hello","Adele","ccccccccccc");
        broken.getJSONObject("musicResponsiveListItemRenderer").getJSONArray("flexColumns")
            .getJSONObject(0).getJSONObject("musicResponsiveListItemFlexColumnRenderer").remove("text");
        String response=new JSONObject().put("contents",new JSONObject().put("items",new JSONArray().put(broken))).toString();
        RecognitionClient c=client(url -> new ResponseConnection(200,response),6000);
        assertEquals(RecognitionClient.Kind.BAD_RESPONSE,c.resolveResult(track(),new RequestScope()).kind);
    }

    @Test public void legacyResolveDoesNotHideRateLimit() throws Exception {
        RecognitionClient c=client(url -> new ResponseConnection(429,"{}"),6000);
        try { c.resolve(track()); fail("rate limit must be observable"); }
        catch(RecognitionClient.RequestException error) { assertEquals(RecognitionClient.Kind.RATE_LIMITED,error.kind); }
    }

    @Test(timeout=5000) public void cancellingBlockedRequestFreesSingleNetworkQueue() throws Exception {
        BlockingConnection blocked=new BlockingConnection();
        String response=catalog(); AtomicInteger opened=new AtomicInteger();
        RecognitionClient c=client(url -> opened.getAndIncrement()==0?blocked:new ResponseConnection(200,response),6000);
        RequestScope stale=new RequestScope(); ExecutorService queue=Executors.newSingleThreadExecutor();
        try {
            Future<RecognitionClient.Resolution> old=queue.submit(() -> c.resolveResult(track(),stale));
            assertTrue("first request reached read",blocked.reading.await(1,TimeUnit.SECONDS));
            Future<RecognitionClient.Resolution> fresh=queue.submit(() -> c.resolveResult(track(),new RequestScope()));
            stale.cancel();
            assertEquals(RecognitionClient.Kind.CANCELLED,old.get(1,TimeUnit.SECONDS).kind);
            assertEquals(RecognitionClient.Kind.FOUND,fresh.get(1,TimeUnit.SECONDS).kind);
            assertEquals(2,opened.get());
        } finally { stale.cancel(); queue.shutdownNow(); }
    }

    @Test(timeout=5000) public void totalDeadlineClosesBlockedReadAndReportsTimeout() throws Exception {
        BlockingConnection blocked=new BlockingConnection();
        RecognitionClient c=client(url -> blocked,200);
        RequestScope scope=new RequestScope(); ExecutorService queue=Executors.newSingleThreadExecutor();
        try {
            Future<RecognitionClient.Resolution> result=queue.submit(() -> c.resolveResult(track(),scope));
            assertTrue(blocked.reading.await(1,TimeUnit.SECONDS));
            assertEquals(RecognitionClient.Kind.TIMEOUT,result.get(1,TimeUnit.SECONDS).kind);
            assertTrue(blocked.closed.await(1,TimeUnit.SECONDS));
            assertFalse("deadline is local to this call",scope.isCancelled());
        } finally { scope.cancel(); queue.shutdownNow(); }
    }

    @Test public void socketReadTimeoutIsNotNoMatch() {
        RecognitionClient c=client(url -> new ResponseConnection(200,"") {
            @Override public int getResponseCode() throws IOException { throw new SocketTimeoutException("fixture"); }
        },6000);
        assertEquals(RecognitionClient.Kind.TIMEOUT,c.resolveResult(track(),new RequestScope()).kind);
    }

    @Test public void recognitionRequestOmitsSamplingSharehubAndVideo(){
        String url=RecognitionClient.recognitionUrl();
        assertTrue(url.startsWith("https://amp.shazam.com/discovery/v5/"));
        assertTrue(url.contains("sync=true"));
        assertFalse(url.contains("sampling"));
        assertFalse(url.contains("sharehub"));
        assertFalse(url.contains("video="));
        assertFalse(url.contains("connected="));
        assertTrue(RecognitionClient.allowedHost("amp.shazam.com"));
        assertTrue(RecognitionClient.allowedHost("music.youtube.com"));
        assertFalse(RecognitionClient.allowedHost("googleads.g.doubleclick.net"));
        assertFalse(RecognitionClient.allowedHost("app-measurement.com"));
    }
    @Test public void cancelledScopeNeverStartsAnotherRequestOrUsesCachedResult() throws Exception {
        String response=catalog(); AtomicInteger opened=new AtomicInteger();
        RecognitionClient c=client(url -> { opened.incrementAndGet(); return new ResponseConnection(200,response); },6000);
        assertEquals(RecognitionClient.Kind.FOUND,c.resolveResult(track(),new RequestScope()).kind);
        RequestScope cancelled=new RequestScope(); cancelled.cancel();
        assertEquals(RecognitionClient.Kind.CANCELLED,c.resolveResult(track(),cancelled).kind);
        try { c.recognize(new short[0],0,cancelled); fail("cancelled recognition must stop before fingerprint"); }
        catch(RequestScope.CancelledException expected) { }
        assertEquals(1,opened.get());
    }

    @Test public void expiredMappingIsLookedUpAgainAndExplicitInvalidationClearsTarget() throws Exception {
        AtomicLong clock=new AtomicLong(100); AtomicInteger opened=new AtomicInteger(); String response=catalog();
        RecognitionClient c=new RecognitionClient(url -> { opened.incrementAndGet(); return new ResponseConnection(200,response); },12_000,6000,clock::get);
        Track t=track();
        assertEquals(RecognitionClient.Kind.FOUND,c.resolveResult(t,new RequestScope()).kind);
        t.youtubeUrl=URL; t.playbackTitle="catalog title"; t.playbackArtist="catalog artist";
        clock.addAndGet(RecognitionClient.CACHE_TTL_MS-1);
        assertEquals(URL,c.cached(track())); assertEquals(1,opened.get());
        clock.incrementAndGet();
        assertEquals("",c.cached(track()));
        assertEquals(RecognitionClient.Kind.FOUND,c.resolveResult(t,new RequestScope()).kind);
        assertEquals(2,opened.get());
        c.invalidate(t);
        assertEquals("",c.cached(track())); assertEquals("",t.youtubeUrl);
        assertEquals(t.title,t.playbackTitle); assertEquals(t.artist,t.playbackArtist);
    }

    @Test public void cacheIsBoundedAndKeepsRecentlyUsedMapping() throws Exception {
        String response=catalog(); RecognitionClient c=client(url -> new ResponseConnection(200,response),6000);
        for(int i=0;i<100;i++) {
            Track t=new Track(String.valueOf(i),"Hello","Adele","",0,0,0);
            assertEquals(RecognitionClient.Kind.FOUND,c.resolveResult(t,new RequestScope()).kind);
        }
        Track first=new Track("0","Hello","Adele","",0,0,0);
        assertEquals(URL,c.cached(first));
        assertEquals(RecognitionClient.Kind.FOUND,c.resolveResult(new Track("100","Hello","Adele","",0,0,0),new RequestScope()).kind);
        assertEquals(URL,c.cached(first));
        assertEquals("",c.cached(new Track("1","Hello","Adele","",0,0,0)));
    }

    @Test public void sharedCacheSurvivesClientRecreationAndInvalidationIsVisibleToBoth() throws Exception {
        RecognitionClient.CacheState shared=new RecognitionClient.CacheState();
        String response=catalog(); AtomicInteger opened=new AtomicInteger();
        RecognitionClient.ConnectionFactory factory=url -> { opened.incrementAndGet(); return new ResponseConnection(200,response); };
        RecognitionClient first=new RecognitionClient(factory,12_000,6000,() -> 0,shared);
        RecognitionClient second=new RecognitionClient(factory,12_000,6000,() -> 0,shared);
        assertEquals(RecognitionClient.Kind.FOUND,first.resolveResult(track(),new RequestScope()).kind);
        assertEquals(RecognitionClient.Kind.FOUND,second.resolveResult(track(),new RequestScope()).kind);
        assertEquals(1,opened.get());
        first.invalidate(track());
        assertEquals("",second.cached(track()));
        assertEquals(RecognitionClient.Kind.FOUND,second.resolveResult(track(),new RequestScope()).kind);
        assertEquals(2,opened.get());
    }

    @Test(timeout=5000) public void invalidationDuringLookupCannotRepopulateMapping() throws Exception {
        CountDownLatch reading=new CountDownLatch(1), release=new CountDownLatch(1);
        byte[] response=catalog().getBytes(StandardCharsets.UTF_8);
        RecognitionClient.CacheState shared=new RecognitionClient.CacheState();
        RecognitionClient c=new RecognitionClient(url -> new ResponseConnection(200,"") {
            @Override public InputStream getInputStream() throws IOException {
                reading.countDown();
                try { if(!release.await(1,TimeUnit.SECONDS))throw new IOException("fixture deadline"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                return new ByteArrayInputStream(response);
            }
        },12_000,6000,() -> 0,shared);
        RecognitionClient otherClient=new RecognitionClient(url -> { throw new IOException("unexpected network access"); },12_000,6000,() -> 0,shared);
        ExecutorService queue=Executors.newSingleThreadExecutor(); Track t=track();
        try {
            Future<RecognitionClient.Resolution> result=queue.submit(() -> c.resolveResult(t,new RequestScope()));
            assertTrue(reading.await(1,TimeUnit.SECONDS));
            otherClient.invalidate(t); release.countDown();
            assertEquals(RecognitionClient.Kind.CANCELLED,result.get(1,TimeUnit.SECONDS).kind);
            assertEquals("",c.cached(t)); assertEquals("",t.youtubeUrl);
        } finally { release.countDown(); queue.shutdownNow(); }
    }

    private static class ResponseConnection extends HttpURLConnection {
        final int status; final byte[] body;
        ResponseConnection(int status,String body) throws MalformedURLException {
            super(new java.net.URL("https://fixture.invalid/")); this.status=status; this.body=body.getBytes(StandardCharsets.UTF_8);
        }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int getResponseCode() throws IOException { return status; }
        @Override public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(body); }
    }
    private static final class BlockingConnection extends ResponseConnection {
        final CountDownLatch reading=new CountDownLatch(1), closed=new CountDownLatch(1);
        BlockingConnection() throws MalformedURLException { super(200,""); }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    reading.countDown();
                    try { if(!closed.await(2,TimeUnit.SECONDS))throw new IOException("fixture failed to close"); }
                    catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    throw new IOException("connection closed");
                }
            };
        }
        @Override public void disconnect() { closed.countDown(); }
    }
}
