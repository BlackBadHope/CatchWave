package app.catchwave;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class RecognitionClient {
    public enum Kind { FOUND, NO_MATCH, TIMEOUT, RATE_LIMITED, UNAVAILABLE, BAD_RESPONSE, CANCELLED }

    public static final class Resolution {
        public final Kind kind;
        public final String url, message;
        public Resolution(Kind kind, String url, String message) {
            this.kind=kind; this.url=url; this.message=message;
        }
    }

    /** One playable catalog entry offered to the user when the automatic pick is wrong. */
    public static final class Candidate {
        public final String title,artist,url;
        public Candidate(String title,String artist,String url){this.title=title;this.artist=artist;this.url=url;}
    }
    static final int CANDIDATE_LIMIT=6;

    public static final class RequestException extends IOException {
        public final Kind kind;
        RequestException(Kind kind, String message) { super(message); this.kind=kind; }
        RequestException(Kind kind, String message, Throwable cause) { super(message,cause); this.kind=kind; }
    }

    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    static final long CACHE_TTL_MS=6*60*60*1000L;
    private static final long RECOGNITION_TIMEOUT_MS=12_000, CATALOG_TIMEOUT_MS=6_000;
    private static final int CACHE_LIMIT=100, MAX_RESPONSE_BYTES=2_000_000;
    private static final ScheduledThreadPoolExecutor deadlines;
    static {
        deadlines=new ScheduledThreadPoolExecutor(1,r -> {
            Thread thread=new Thread(r,"CatchWave-request-deadline"); thread.setDaemon(true); return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
    }

    private static final class CatalogEntry {
        final String url,title,artist; final long storedAt;
        CatalogEntry(String url,Track t,long storedAt) {
            this.url=url; title=t.playbackTitle; artist=t.playbackArtist; this.storedAt=storedAt;
        }
    }
    private static final class CandidateEntry {
        final java.util.List<Candidate> list; final long storedAt;
        CandidateEntry(java.util.List<Candidate> list,long storedAt){this.list=list;this.storedAt=storedAt;}
    }
    static final class CacheState {
        final Map<String,CatalogEntry> links=new LinkedHashMap<>(100,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,CatalogEntry> entry) { return size()>CACHE_LIMIT; }
        };
        final Map<String,CandidateEntry> candidates=new LinkedHashMap<>(100,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,CandidateEntry> entry) { return size()>CACHE_LIMIT; }
        };
        long revision;
    }
    private static final CacheState sharedCache=new CacheState();
    private final CacheState cache;
    private final ConnectionFactory connections;
    private final LongSupplier cacheClock;
    private final long recognitionTimeoutMs,catalogTimeoutMs;

    public RecognitionClient() {
        this(url -> (HttpURLConnection)url.openConnection(),RECOGNITION_TIMEOUT_MS,CATALOG_TIMEOUT_MS,
            () -> System.nanoTime()/1_000_000,sharedCache);
    }
    // The transport seam keeps deadline, cancellation and error tests entirely offline.
    RecognitionClient(ConnectionFactory connections,long recognitionTimeoutMs,long catalogTimeoutMs,LongSupplier cacheClock) {
        this(connections,recognitionTimeoutMs,catalogTimeoutMs,cacheClock,new CacheState());
    }
    RecognitionClient(ConnectionFactory connections,long recognitionTimeoutMs,long catalogTimeoutMs,LongSupplier cacheClock,CacheState cache) {
        this.connections=connections; this.cacheClock=cacheClock;
        this.cache=cache;
        this.recognitionTimeoutMs=Math.max(1,Math.min(RECOGNITION_TIMEOUT_MS,recognitionTimeoutMs));
        this.catalogTimeoutMs=Math.max(1,Math.min(CATALOG_TIMEOUT_MS,catalogTimeoutMs));
    }

    public String cached(Track track) {
        synchronized (cache) {
            CatalogEntry entry=cache.links.get(track.key);
            if(entry==null)return "";
            long age=cacheClock.getAsLong()-entry.storedAt;
            if(age<0||age>=CACHE_TTL_MS) { cache.links.remove(track.key); return ""; }
            track.playbackTitle=entry.title; track.playbackArtist=entry.artist; return entry.url;
        }
    }

    public void invalidate(Track track) {
        if(track==null)return;
        synchronized (cache) {
            cache.links.remove(track.key); cache.revision++;
            track.youtubeUrl=""; track.playbackTitle=track.title; track.playbackArtist=track.artist;
        }
    }

    public Track recognize(short[] audio,long anchor) throws Exception {
        return recognize(audio,anchor,new RequestScope());
    }
    public Track recognize(short[] audio,long anchor,RequestScope scope) throws Exception {
        try(CallBudget budget=new CallBudget(recognitionTimeoutMs)) {
            try {
                check(scope,budget);
                String signature=Fingerprint.generate(audio);
                check(scope,budget);
                long timestamp=System.currentTimeMillis();
                JSONObject request=new JSONObject().put("timestamp",timestamp).put("timezone","UTC")
                    .put("signature",new JSONObject().put("samplems",audio.length/16).put("timestamp",timestamp).put("uri",signature));
                Track track=parse(fetchScoped(recognitionUrl(),request,scope,budget),anchor);
                check(scope,budget);
                if(track!=null)track.sampleDurationMs=audio.length/16;
                return track;
            } catch(Exception error) { throw classified(error,scope,budget); }
        }
    }

    public static Track parse(JSONObject response,long anchor) throws Exception {
        BoundedJson.validate(response,()->{});
        JSONObject data=response.optJSONObject("track"); JSONArray matches=response.optJSONArray("matches");
        if(data==null||matches==null||matches.length()==0)return null;
        String key=requiredText(data,"key"), title=requiredText(data,"title"), artist=requiredText(data,"subtitle");
        if(key.isEmpty()||title.isEmpty()||artist.isEmpty())return null;
        JSONObject match=matches.optJSONObject(0); if(match==null)return null;
        double offset=match.optDouble("offset",Double.NaN);
        String apple=""; JSONObject hub=data.optJSONObject("hub");
        JSONArray actions=hub==null?null:hub.optJSONArray("actions");
        if(actions!=null)for(int i=0;i<actions.length();i++) {
            JSONObject action=actions.optJSONObject(i); if(action==null)continue;
            String id=action.optString("id",""); if(id.matches("[0-9]+"))apple=id;
        }
        return new Track(key,title,artist,apple,Double.isFinite(offset)?Math.round(offset*1000):Long.MIN_VALUE,
            anchor,match.optDouble("timeskew",0));
    }

    private static String requiredText(JSONObject object,String key) {
        Object value=object.opt(key); return value instanceof String ? ((String)value).trim() : "";
    }

    public String resolve(Track track) throws Exception {
        Resolution result=resolveResult(track,new RequestScope());
        if(result.kind==Kind.FOUND||result.kind==Kind.NO_MATCH)return result.url;
        if(result.kind==Kind.CANCELLED)throw new RequestScope.CancelledException();
        throw new RequestException(result.kind,result.message);
    }

    public Resolution resolveResult(Track track,RequestScope scope) {
        try(CallBudget budget=new CallBudget(catalogTimeoutMs)) {
            try {
                check(scope,budget);
                if(track==null||track.key.trim().isEmpty()||track.title.trim().isEmpty()||track.artist.trim().isEmpty())
                    throw new RequestException(Kind.BAD_RESPONSE,L.t("The recognizer returned an incomplete title or artist."));
                String existing=cached(track);
                if(!existing.isEmpty()) { check(scope,budget); return new Resolution(Kind.FOUND,existing,""); }
                long revision; synchronized (cache) { revision=cache.revision; }
                // Parse into a private copy: cancelled lookup must not change the live target.
                Track candidate=new Track(track.key,track.title,track.artist,track.appleId,track.offsetMs,track.anchorMs,track.timeSkew);
                String direct=searchYoutube(candidate,scope,budget);
                check(scope,budget);
                if(!validMusicUrl(direct))return new Resolution(Kind.NO_MATCH,"",L.t("The exact recording was not found in the catalog."));
                synchronized (cache) {
                    check(scope,budget);
                    if(revision!=cache.revision)return new Resolution(Kind.CANCELLED,"",L.t("Match reset; a new search is needed."));
                    track.playbackTitle=candidate.playbackTitle; track.playbackArtist=candidate.playbackArtist;
                    cache.links.put(track.key,new CatalogEntry(direct,candidate,cacheClock.getAsLong()));
                }
                return new Resolution(Kind.FOUND,direct,"");
            } catch(Exception error) {
                IOException failure=classified(error,scope,budget);
                Kind kind=failure instanceof RequestScope.CancelledException ? Kind.CANCELLED : ((RequestException)failure).kind;
                return new Resolution(kind,"",failure.getMessage());
            }
        }
    }

    public String searchYoutube(Track track) throws Exception {
        RequestScope scope=new RequestScope();
        try(CallBudget budget=new CallBudget(catalogTimeoutMs)) {
            try { return searchYoutube(track,scope,budget); }
            catch(Exception error) { throw classified(error,scope,budget); }
        }
    }

    private String searchYoutube(Track track,RequestScope scope,CallBudget budget) throws Exception {
        JSONObject client=new JSONObject().put("clientName","WEB_REMIX").put("clientVersion","1.20260121.03.00").put("hl","en").put("gl","US");
        JSONObject body=new JSONObject().put("context",new JSONObject().put("client",client))
            .put("query",track.artist+" "+track.title).put("params","Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D");
        JSONObject response=fetchScoped("https://music.youtube.com/youtubei/v1/search?prettyPrint=false",body,scope,budget);
        // An error page or changed protocol is not evidence that a recording does not exist.
        if(response.has("error")||!(response.opt("contents") instanceof JSONObject
            ||response.opt("continuationContents") instanceof JSONObject||response.opt("onResponseReceivedCommands") instanceof JSONArray))
            throw new RequestException(Kind.BAD_RESPONSE,L.t("The catalog returned a response without search results."));
        CatalogScan scan=new CatalogScan();
        parseYoutubeSearch(response,track,scan,()->check(scope,budget)); check(scope,budget);
        storeCandidates(track.key,scan.candidates);
        if(scan.found.isEmpty()&&scan.songItems>0&&scan.readableItems==0)
            throw new RequestException(Kind.BAD_RESPONSE,L.t("Could not read the entries in the catalog response."));
        return scan.found;
    }

    private void storeCandidates(String key,List<Candidate> list){
        if(key==null||key.trim().isEmpty()||list.isEmpty())return;
        synchronized (cache) { cache.candidates.put(key,new CandidateEntry(new ArrayList<>(list),cacheClock.getAsLong())); }
    }
    /** Alternatives remembered from the last search for this recording; empty when none or expired. */
    public List<Candidate> cachedCandidates(Track track) {
        if(track==null)return java.util.Collections.emptyList();
        synchronized (cache) {
            CandidateEntry entry=cache.candidates.get(track.key);
            if(entry==null)return java.util.Collections.emptyList();
            long age=cacheClock.getAsLong()-entry.storedAt;
            if(age<0||age>=CACHE_TTL_MS) { cache.candidates.remove(track.key); return java.util.Collections.emptyList(); }
            return new ArrayList<>(entry.list);
        }
    }
    /** Blocking: returns remembered alternatives, or searches once to collect them. Failure means an empty list. */
    public List<Candidate> fetchCandidates(Track track,RequestScope scope) {
        List<Candidate> known=cachedCandidates(track);
        if(!known.isEmpty()||track==null||track.key.trim().isEmpty()||track.title.trim().isEmpty()||track.artist.trim().isEmpty())return known;
        try(CallBudget budget=new CallBudget(catalogTimeoutMs)) {
            Track copy=new Track(track.key,track.title,track.artist,track.appleId,track.offsetMs,track.anchorMs,track.timeSkew);
            searchYoutube(copy,scope,budget);
        } catch(Exception ignored) { /* The chooser falls back to a plain rejection. */ }
        return cachedCandidates(track);
    }
    /** The user's explicit recording choice replaces the automatic pick and cancels in-flight lookups. */
    public void pin(Track track,Candidate chosen) {
        if(track==null||chosen==null||!validMusicUrl(chosen.url))return;
        synchronized (cache) {
            track.youtubeUrl=chosen.url;track.playbackTitle=chosen.title;track.playbackArtist=chosen.artist;
            cache.links.put(track.key,new CatalogEntry(chosen.url,track,cacheClock.getAsLong()));
            cache.revision++;
        }
    }

    public static String parseYoutubeSearch(Object node,Track target) throws Exception {
        BoundedJson.validate(node,()->{});
        CatalogScan scan=new CatalogScan();
        parseYoutubeSearch(node,target,scan,()->{});
        return scan.found;
    }
    private static final class CatalogScan {
        int songItems,readableItems,steps;
        String found="";
        final java.util.List<Candidate> candidates=new ArrayList<>();
        boolean complete(){return !found.isEmpty()&&candidates.size()>=CANDIDATE_LIMIT;}
        boolean knownId(String id){
            for(Candidate c:candidates)if(c.url.endsWith(id))return true;
            return false;
        }
    }
    /** Full traversal: the automatic pick stays the first strict match, while playable
     *  alternatives keep collecting so a wrong pick can be corrected without a new search. */
    private static void parseYoutubeSearch(Object node,Track target,CatalogScan scan,BoundedJson.Check check) throws Exception {
        if(scan.complete())return;
        if((++scan.steps&127)==0)check.run();
        if(node instanceof JSONArray array)for(int i=0;i<array.length()&&!scan.complete();i++)
            parseYoutubeSearch(array.opt(i),target,scan,check);
        if(node instanceof JSONObject object) {
            JSONObject item=object.optJSONObject("musicResponsiveListItemRenderer");
            if(item!=null) {
                JSONObject data=item.optJSONObject("playlistItemData"); String id=data==null?"":data.optString("videoId","");
                if(item.has("playlistItemData"))scan.songItems++;
                JSONArray columns=item.optJSONArray("flexColumns");
                if(id.matches("[A-Za-z0-9_-]{11}")&&columns!=null&&columns.length()>=2) {
                    check.run();String title=columnText(columns,0,check), artist=columnText(columns,1,check).split("[•·]",2)[0].trim();
                    if(!title.isEmpty()&&!artist.isEmpty())scan.readableItems++;
                    boolean playable=!title.isEmpty()&&!artist.isEmpty()
                        &&!"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT".equals(item.optString("musicItemRendererDisplayPolicy"));
                    if(playable&&scan.candidates.size()<CANDIDATE_LIMIT&&!scan.knownId(id))
                        scan.candidates.add(new Candidate(title,artist,"https://music.youtube.com/watch?v="+id));
                    if(playable&&scan.found.isEmpty()&&target.matches(title,artist)) {
                        check.run();
                        target.playbackTitle=title; target.playbackArtist=artist; scan.found="https://music.youtube.com/watch?v="+id;
                    }
                    check.run();
                }
            }
            java.util.Iterator<String> keys=object.keys();
            while(keys.hasNext()&&!scan.complete())parseYoutubeSearch(object.opt(keys.next()),target,scan,check);
        }
    }

    private static String columnText(JSONArray columns,int index,BoundedJson.Check check)throws java.io.IOException {
        JSONObject column=columns.optJSONObject(index);
        JSONObject renderer=column==null?null:column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text=renderer==null?null:renderer.optJSONObject("text");
        if(text==null)return "";
        JSONArray runs=text.optJSONArray("runs"); if(runs==null){String value=requiredText(text,"simpleText");if(value.length()>Track.MATCH_TEXT_LIMIT)throw new java.io.IOException("Catalog text bounds");return value;}
        StringBuilder out=new StringBuilder();
        for(int i=0;i<runs.length();i++) {
            if((i&127)==0)check.run();
            JSONObject run=runs.optJSONObject(i); if(run==null)return "";
            Object value=run.opt("text"); if(!(value instanceof String))return "";
            if(((String)value).length()>Track.MATCH_TEXT_LIMIT-out.length())throw new java.io.IOException("Catalog text bounds");out.append((String)value);
        }
        return out.toString();
    }

    public static String parseSongLink(String html) throws Exception {
        Matcher script=Pattern.compile("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>",Pattern.DOTALL).matcher(html);
        if(!script.find())return "";
        JSONObject root=new JSONObject(script.group(1));
        JSONObject data=root.getJSONObject("props").getJSONObject("pageProps").getJSONObject("pageData");
        JSONArray sections=data.optJSONArray("sections");if(sections==null)return "";
        for(int i=0;i<sections.length();i++) {
            JSONArray links=sections.getJSONObject(i).optJSONArray("links");if(links==null)continue;
            for(int j=0;j<links.length();j++) {
                JSONObject link=links.getJSONObject(j);String url=link.optString("url","");
                if("youtubeMusic".equalsIgnoreCase(link.optString("platform"))&&validMusicUrl(url))return url;
            }
        }
        return "";
    }
    static String recognitionUrl(){
        return "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/"
            +UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT)+"/"+UUID.randomUUID()
            +"?sync=true&webv3=true&shazamapiversion=v3";
    }
    static boolean allowedHost(String host){
        return "amp.shazam.com".equals(host)||"music.youtube.com".equals(host);
    }
    public static boolean validMusicUrl(String link) {
        try { URI u=URI.create(link); return "https".equals(u.getScheme())&&"music.youtube.com".equals(u.getHost())&&"/watch".equals(u.getPath())&&u.getQuery()!=null&&u.getQuery().matches(".*(?:^|&)v=[A-Za-z0-9_-]{11}(?:&.*)?"); }
        catch(Exception e) { return false; }
    }

    // Kept for the existing bounded diagnostic probes that reflect this method.
    private static JSONObject fetch(String url,JSONObject body) throws Exception {
        RequestScope scope=new RequestScope();
        long timeout="music.youtube.com".equals(new URL(url).getHost())?CATALOG_TIMEOUT_MS:RECOGNITION_TIMEOUT_MS;
        try(CallBudget budget=new CallBudget(timeout)){return new RecognitionClient().fetchScoped(url,body,scope,budget);}
    }
    private static String fetchText(String url,JSONObject body) throws Exception {
        RequestScope scope=new RequestScope();
        long timeout="music.youtube.com".equals(new URL(url).getHost())?CATALOG_TIMEOUT_MS:RECOGNITION_TIMEOUT_MS;
        try(CallBudget budget=new CallBudget(timeout)) {
            try { return new RecognitionClient().fetchTextScoped(url,body,scope,budget); }
            catch(Exception error) { throw classified(error,scope,budget); }
        }
    }
    private JSONObject fetchScoped(String url,JSONObject body,RequestScope scope,CallBudget budget) throws Exception {
        return BoundedJson.parse(fetchTextScoped(url,body,scope,budget),()->check(scope,budget));
    }
    private String fetchTextScoped(String url,JSONObject body,RequestScope scope,CallBudget budget) throws Exception {
        check(scope,budget);
        URL target=new URL(url);
        if(!allowedHost(target.getHost()))throw new RequestException(Kind.UNAVAILABLE,L.t("Request to an unrelated server blocked."));
        HttpURLConnection connection=connections.open(target);
        try {
            scope.register(connection); budget.attach(connection); check(scope,budget);
            boolean catalog="music.youtube.com".equals(target.getHost());
            connection.setConnectTimeout(Math.min(catalog?2500:4000,budget.remainingMillis()));
            connection.setReadTimeout(Math.min(catalog?3500:8000,budget.remainingMillis()));
            connection.setRequestProperty("User-Agent",AppIdentity.userAgent());
            connection.setRequestProperty("Accept",body==null?"text/html,application/json":"application/json");
            if(catalog) {
                connection.setRequestProperty("Origin","https://music.youtube.com");
                connection.setRequestProperty("Referer","https://music.youtube.com/");
                connection.setRequestProperty("X-YouTube-Client-Name","67");
                connection.setRequestProperty("X-YouTube-Client-Version","1.20260121.03.00");
            }
            if(body!=null) {
                byte[] request=body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(request.length);
                connection.setRequestProperty("Content-Type","application/json");
                try(OutputStream output=connection.getOutputStream()) { check(scope,budget); output.write(request); }
            }
            check(scope,budget);
            int status=connection.getResponseCode(); check(scope,budget);
            if(status==429)throw new RequestException(Kind.RATE_LIMITED,L.t("The service rate-limited requests. Try again later (HTTP 429)."));
            if(status!=200)throw new RequestException(Kind.UNAVAILABLE,L.t("Service unavailable (HTTP ")+status+").");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int count;
                while(true) {
                    check(scope,budget); connection.setReadTimeout(Math.min(catalog?3500:8000,budget.remainingMillis()));
                    count=input.read(buffer); check(scope,budget); if(count==-1)break;
                    if(out.size()+count>MAX_RESPONSE_BYTES)throw new RequestException(Kind.BAD_RESPONSE,L.t("The service response is too large."));
                    out.write(buffer,0,count);
                }
                return out.toString("UTF-8");
            }
        } finally {
            budget.detach(connection); scope.unregister(connection); RequestScope.disconnect(connection);
        }
    }

    private static void check(RequestScope scope,CallBudget budget) throws IOException {
        scope.throwIfCancelled(); budget.check();
    }
    private static IOException classified(Exception error,RequestScope scope,CallBudget budget) {
        if(scope.isCancelled()||Thread.currentThread().isInterrupted()||error instanceof RequestScope.CancelledException)
            return new RequestScope.CancelledException();
        if(budget.expired()||error instanceof SocketTimeoutException)
            return new RequestException(Kind.TIMEOUT,L.t("The service did not respond in time. Try catching again."),error);
        if(error instanceof RequestException)return (RequestException)error;
        if(error instanceof JSONException)return new RequestException(Kind.BAD_RESPONSE,L.t("The service returned a corrupted response."),error);
        return new RequestException(Kind.UNAVAILABLE,L.t("Could not reach the service. Check the connection."),error);
    }

    /** One elapsed-time limit covers connect, upload and every read, including trickled responses. */
    private static final class CallBudget implements AutoCloseable {
        private final long deadlineNanos;
        private final ScheduledFuture<?> alarm;
        private HttpURLConnection connection;
        private boolean timedOut;
        CallBudget(long timeoutMs) {
            deadlineNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            alarm=deadlines.schedule(this::expire,timeoutMs,TimeUnit.MILLISECONDS);
        }
        synchronized boolean expired() { return timedOut||System.nanoTime()>=deadlineNanos; }
        void check() throws RequestException {
            if(expired())throw new RequestException(Kind.TIMEOUT,L.t("The service did not respond in time. Try catching again."));
        }
        int remainingMillis() throws RequestException {
            check(); return (int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadlineNanos-System.nanoTime()));
        }
        void attach(HttpURLConnection value) throws RequestException {
            boolean reject;
            synchronized(this) { reject=expired(); if(!reject)connection=value; }
            if(reject) { RequestScope.disconnect(value); check(); }
        }
        synchronized void detach(HttpURLConnection value) { if(connection==value)connection=null; }
        private void expire() {
            HttpURLConnection pending;
            synchronized(this) { timedOut=true; pending=connection; }
            if(pending!=null)RequestScope.disconnectAsync(pending);
        }
        @Override public void close() { alarm.cancel(false); }
    }
}
