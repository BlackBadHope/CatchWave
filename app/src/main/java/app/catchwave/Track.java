package app.catchwave;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class Track {
    private static final Pattern FEATURED=Pattern.compile("(?i)\\s*[\\[(]\\s*(?:feat\\.?|ft\\.?|featuring|за участю(?: виконавців)?|при участии)\\s+([^\\]\\)]+)[\\])]\\s*");
    private static final Pattern ARTIST_FEATURED=Pattern.compile("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|за участю(?: виконавців)?|при участии)\\s+");
    private static final Pattern PRESENTATION=Pattern.compile("(?i)(?:\\s*[\\[(]\\s*(?:official audio|official video|official music video|lyrics|lyric video)\\s*(?:\\]|\\))|\\s+[|–—-]\\s*(?:official audio|official video|official music video|lyrics|lyric video))\\s*$");
    public final String key,title,artist,appleId;
    public final long offsetMs,anchorMs;
    public final double timeSkew;
    public String youtubeUrl="";
    public String playbackTitle,playbackArtist;
    public long sampleDurationMs=6000;
    public Track(String key,String title,String artist,String appleId,long offsetMs,long anchorMs,double skew) {
        this.key=key;this.title=title;this.artist=artist;this.appleId=appleId;this.offsetMs=offsetMs;this.anchorMs=anchorMs;this.timeSkew=skew;
        this.playbackTitle=title;this.playbackArtist=artist;
    }
    public long positionAt(long now,long adjustment) { return Math.max(0,offsetMs+Math.max(0,now-anchorMs)+adjustment); }
    public boolean hasPosition() { return offsetMs!=Long.MIN_VALUE && Double.isFinite(timeSkew) && Math.abs(timeSkew)<0.005; }
    public boolean matches(String actualTitle,String actualArtist) {
        if(normalize(artist).isEmpty()||normalize(actualArtist).isEmpty())return false;
        String expectedTitle=recordingTitle(title),candidateTitle=recordingTitle(actualTitle);
        // Do not equate remixes, live recordings, covers, slowed or sped-up versions.
        if(!variants(expectedTitle).equals(variants(candidateTitle))) return false;
        String expected=normalize(expectedTitle),actual=normalize(candidateTitle);
        boolean sameTitle=!expected.isEmpty()&&!actual.isEmpty() && (expected.equals(actual)||actual.equals(normalize(artist+" "+expectedTitle)));
        Credits expectedCredits=credits(artist,title),actualCredits=credits(actualArtist,actualTitle);
        if(expectedCredits.primary.isEmpty()||actualCredits.primary.isEmpty()||!expectedCredits.all().equals(actualCredits.all()))return false;
        // A flat catalog credit may relocate guests, but two explicit credits must retain their roles.
        boolean sameRoles=!expectedCredits.explicitFeatured||!actualCredits.explicitFeatured
            ||(expectedCredits.primary.equals(actualCredits.primary)&&expectedCredits.featured.equals(actualCredits.featured));
        return sameTitle && sameRoles;
    }
    private static String recordingTitle(String title){
        return PRESENTATION.matcher(FEATURED.matcher(title==null?"":title).replaceAll(" ")).replaceAll(" ");
    }
    private static final class Credits {
        final Set<String> primary=new HashSet<>(),featured=new HashSet<>();
        boolean explicitFeatured;
        Set<String> all(){Set<String> names=new HashSet<>(primary);names.addAll(featured);return names;}
    }
    private static Credits credits(String artist,String title){
        Credits result=new Credits();
        String value=artist==null?"":artist;
        Matcher bracketedArtistFeatured=FEATURED.matcher(value);
        while(bracketedArtistFeatured.find()){addCredits(result.featured,bracketedArtistFeatured.group(1));result.explicitFeatured=true;}
        value=FEATURED.matcher(value).replaceAll(" ");
        Matcher artistFeatured=ARTIST_FEATURED.matcher(value);
        if(artistFeatured.find()){
            addCredits(result.primary,value.substring(0,artistFeatured.start()));
            addCredits(result.featured,value.substring(artistFeatured.end()));result.explicitFeatured=true;
        }else addCredits(result.primary,value);
        Matcher featured=FEATURED.matcher(title==null?"":title);
        while(featured.find()){addCredits(result.featured,featured.group(1));result.explicitFeatured=true;}
        result.primary.removeAll(result.featured);
        return result;
    }
    private static void addCredits(Set<String> names,String value){
        for(String part:(value==null?"":value).split("(?i)\\s*(?:,|&|;|\\s[іи]\\s|\\bfeat\\.?\\s|\\bfeaturing\\s|\\bft\\.?\\s)\\s*")){
            String name=artistKey(part).replaceFirst(" (?:topic|vevo)$","");if(!name.isEmpty())names.add(name);
        }
    }
    // Catalogs can spell the same Cyrillic artist in Latin script; titles stay strict.
    private static String artistKey(String value) {
        String from="абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ";
        String[] to={"a","b","v","g","d","e","e","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","kh","ts","ch","sh","shch","","y","","e","yu","ya","i","yi","ye","g"};
        StringBuilder out=new StringBuilder();
        for(char c:(value==null?"":value).toLowerCase(Locale.ROOT).toCharArray()){int i=from.indexOf(c);out.append(i<0?String.valueOf(c):to[i]);}
        return normalize(out.toString());
    }
    public static String normalize(String s) {
        return Normalizer.normalize(s==null?"":s,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
            .replaceAll("\\p{M}","")
            .replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll(" +"," ");
    }
    private static Set<String> variants(String title) {
        Set<String> result=new HashSet<>();String n=" "+normalize(title)+" ";
        for(String word:new String[]{"remix","mix","extended","loop","live","cover","slowed","sped","acoustic","instrumental","remaster","remastered","edit"}) if(n.contains(" "+word+" ")) result.add(word);
        return result;
    }
}
