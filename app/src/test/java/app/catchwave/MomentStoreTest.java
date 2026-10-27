package app.catchwave;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class MomentStoreTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private final AtomicLong clock=new AtomicLong(1_700_000_000_000L);
    private Path file;
    private MomentStore store(){
        if(file==null)file=folder.getRoot().toPath().resolve("moments.json");
        return new MomentStore(file,clock::incrementAndGet);
    }

    @Test public void addKeepsNewestFirstAndSurvivesReload()throws Exception{
        MomentStore store=store();
        store.add("k1","First","Artist A","",61_000);
        store.add("k2","Second","Artist B","https://music.youtube.com/watch?v=abcdefghijk",30_000);
        store.flush();
        MomentStore reloaded=store();
        List<Moment> all=reloaded.all();
        assertEquals(2,all.size());
        assertEquals("Second",all.get(0).title);
        assertEquals("First",all.get(1).title);
        assertEquals("1:01",all.get(1).positionLabel());
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk",all.get(0).link());
        assertTrue(all.get(1).link().startsWith("https://music.youtube.com/search?q="));
        assertEquals(0,reloaded.failures());
    }

    @Test public void limitEvictsOldestOrdinaryEntriesButKeepsFavorites()throws Exception{
        MomentStore store=store();
        Moment kept=store.add("keep","Keep","Artist","",0);
        store.setFavorite(kept.id,true);
        for(int i=0;i<MomentStore.LIMIT+25;i++)store.add("k"+i,"Title "+i,"Artist","",0);
        assertEquals(MomentStore.LIMIT,store.size());
        List<Moment> all=store.all();
        assertEquals(kept.id,all.get(all.size()-1).id);
        assertTrue(all.get(all.size()-1).favorite);
        store.flush();
        assertEquals(MomentStore.LIMIT,store().size());
    }

    @Test public void favoriteNoteDeleteAndClearPersist()throws Exception{
        MomentStore store=store();
        Moment a=store.add("a","Song A","Artist","",5_000);
        Moment b=store.add("b","Song B","Artist","",6_000);
        assertTrue(store.setFavorite(a.id,true));
        assertTrue(store.setNote(a.id,"met at the café"));
        assertFalse(store.setNote(999_999,"nobody"));
        store.flush();
        MomentStore reloaded=store();
        List<Moment> all=reloaded.all();
        assertTrue(all.get(1).favorite);
        assertEquals("met at the café",all.get(1).note);
        assertTrue(reloaded.delete(b.id));
        reloaded.clear();
        reloaded.flush();
        assertEquals(0,store().size());
    }

    @Test public void resyncOfTheNewestRecordingRefreshesItInsteadOfDuplicating()throws Exception{
        MomentStore store=store();
        Moment first=store.add("same","Song","Artist","",10_000);
        store.setFavorite(first.id,true);
        store.setNote(first.id,"keep me");
        Moment refreshed=store.add("same","Song","Artist","https://music.youtube.com/watch?v=abcdefghijk",95_000);
        assertEquals(1,store.size());
        assertEquals(first.id,refreshed.id);
        assertEquals(95_000,refreshed.positionMs);
        assertTrue(refreshed.favorite);
        assertEquals("keep me",refreshed.note);
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk",refreshed.youtubeUrl);
        store.add("other","Another","Artist","",0);
        store.add("same","Song","Artist","",20_000);
        assertEquals(3,store.size());
    }

    @Test public void rejectRemovesOnlyTheNewestEntryOfThatRecording()throws Exception{
        MomentStore store=store();
        store.add("same","Song","Artist","",1_000);
        store.add("other","Another","Artist","",2_000);
        store.add("same","Song","Artist","",3_000);
        assertTrue(store.removeNewest("same"));
        assertFalse(store.removeNewest("missing"));
        List<Moment> all=store.all();
        assertEquals(2,all.size());
        assertEquals("other",all.get(0).key);
        assertEquals(1_000,all.get(1).positionMs);
    }

    @Test public void lateCatalogLinkFillsOnlyEmptyUrlsOfThatRecording()throws Exception{
        MomentStore store=store();
        store.add("k","Song","Artist","",0);
        store.add("z","Other","Artist","",0);
        store.add("k","Song","Artist","https://music.youtube.com/watch?v=existingurl",0);
        assertEquals(0,store.updateUrl("k","not a url"));
        assertEquals(1,store.updateUrl("k","https://music.youtube.com/watch?v=abcdefghijk"));
        List<Moment> all=store.all();
        assertEquals("https://music.youtube.com/watch?v=existingurl",all.get(0).youtubeUrl);
        assertEquals("",all.get(1).youtubeUrl);
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk",all.get(2).youtubeUrl);
    }

    @Test public void corruptedOversizedAndForeignFilesLeaveAnEmptyWorkingStore()throws Exception{
        MomentStore store=store();
        store.add("k","Song","Artist","",0);
        store.flush();
        Files.write(file,"{broken json".getBytes(StandardCharsets.UTF_8));
        MomentStore broken=store();
        assertEquals(0,broken.size());
        assertEquals(1,broken.failures());
        Moment added=broken.add("k2","Recovers","Artist","",0);
        broken.flush();
        assertEquals(added.id,store().all().get(0).id);
        Files.write(file,("{\"moments\":[{\"id\":5,\"key\":\"ok\",\"title\":\"T\",\"artist\":\"A\"},"
            +"{\"id\":-1,\"key\":\"bad\",\"title\":\"T\",\"artist\":\"A\"},{\"title\":\"no key\"}]}").getBytes(StandardCharsets.UTF_8));
        assertEquals(1,store().size());
        byte[] oversized=new byte[(int)MomentStore.MAX_FILE_BYTES+1];
        java.util.Arrays.fill(oversized,(byte)' ');
        Files.write(file,oversized);
        assertEquals(0,store().size());
    }

    @Test public void pickedRecordingReplacesLinksOfEveryEntryOfThatSong()throws Exception{
        MomentStore store=store();
        store.add("k","Song","Artist","https://music.youtube.com/watch?v=wrongwrong1",0);
        store.add("z","Other","Artist","https://music.youtube.com/watch?v=otherother1",0);
        store.add("k","Song","Artist","",0);
        assertEquals(0,store.replaceUrl("k","garbage"));
        assertEquals(2,store.replaceUrl("k","https://music.youtube.com/watch?v=abcdefghijk"));
        List<Moment> all=store.all();
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk",all.get(0).youtubeUrl);
        assertEquals("https://music.youtube.com/watch?v=otherother1",all.get(1).youtubeUrl);
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk",all.get(2).youtubeUrl);
    }

    @Test public void exportListsEveryMomentWithNoteAndLink()throws Exception{
        MomentStore store=store();
        Moment m=store.add("k","Song","Artist","https://music.youtube.com/watch?v=abcdefghijk",83_000);
        store.setNote(m.id,"for the drive home");
        String export=store.exportText();
        assertTrue(export.contains("Song — Artist · 1:23 · https://music.youtube.com/watch?v=abcdefghijk"));
        assertTrue(export.contains("for the drive home"));
    }

    @Test public void boundsProtectAgainstOversizedFields(){
        Moment moment=new Moment(1,"k","T".repeat(Moment.TEXT_LIMIT+50),"A","javascript:alert(1)",-5,-7,false,"n".repeat(Moment.NOTE_LIMIT+50));
        assertEquals(Moment.TEXT_LIMIT,moment.title.length());
        assertEquals(Moment.NOTE_LIMIT,moment.note.length());
        assertEquals(0,moment.positionMs);
        assertEquals(0,moment.caughtAtMs);
        assertTrue(moment.link().startsWith("https://music.youtube.com/search?q="));
        assertNull(Moment.fromJson(null));
    }
}
