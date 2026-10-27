// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
public class VersionChooserTest {
    private static final String AUTO="https://music.youtube.com/watch?v=ccccccccccc";
    private static final String REMIX="https://music.youtube.com/watch?v=ddddddddddd";
    private final SessionModel model=SessionModel.INSTANCE;
    private Context app;
    private MomentStore store;

    @Before public void fresh(){
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("match_pins",Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("intro",true).commit();
        store=MomentStore.get(app);store.clear();
        model.track=null;model.running=false;
    }
    @After public void cleanup(){store.clear();model.track=null;model.running=false;}

    private static JSONObject item(String title,String artist,String id) throws Exception {
        JSONArray columns=new JSONArray();
        for(String value:new String[]{title,artist+" • Album • 2:30"})
            columns.put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                new JSONObject().put("text",new JSONObject().put("runs",new JSONArray().put(new JSONObject().put("text",value))))));
        return new JSONObject().put("musicResponsiveListItemRenderer",new JSONObject()
            .put("playlistItemData",new JSONObject().put("videoId",id)).put("flexColumns",columns));
    }
    private RecognitionClient seededClient(Track track) throws Exception {
        String body=new JSONObject().put("contents",new JSONObject().put("items",new JSONArray()
            .put(item("Hello","Adele","ccccccccccc")).put(item("Hello (Remix)","Adele","ddddddddddd")))).toString();
        RecognitionClient client=new RecognitionClient(url -> new Response(body),12_000,6_000,()->0);
        assertEquals(AUTO,client.resolveResult(track,new RequestScope()).url);
        return client;
    }
    private Track track(){return new Track("1","Hello","Adele","",1000,100,0);}

    @Test public void pickingAVersionPinsItAndFixesHistoryWithoutRejecting() throws Exception {
        Track t=track();
        RecognitionClient client=seededClient(t);
        store.add("1","Hello","Adele",AUTO,1000);
        model.track=t;model.running=false;
        MainActivity screen=Robolectric.buildActivity(MainActivity.class).create().get();
        screen.recognition=client;
        button(screen.getWindow().getDecorView(),L.t("This is a different version")).performClick();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        shadowOf(dialog).clickOnItem(1);
        assertEquals(REMIX,MatchPins.get(app,"1").url);
        assertEquals(REMIX,t.youtubeUrl);
        assertEquals("Hello (Remix)",t.playbackTitle);
        assertEquals(REMIX,store.all().get(0).youtubeUrl);
        Intent started=shadowOf((android.app.Application)app).getNextStartedService();
        assertTrue(started==null||!SyncService.REJECT.equals(started.getAction()));
    }

    @Test public void noneOfTheseStillSendsThePlainRejection() throws Exception {
        Track t=track();
        RecognitionClient client=seededClient(t);
        model.track=t;
        MainActivity screen=Robolectric.buildActivity(MainActivity.class).create().get();
        screen.recognition=client;
        button(screen.getWindow().getDecorView(),L.t("This is a different version")).performClick();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        Intent started;
        boolean rejected=false;
        while((started=shadowOf((android.app.Application)app).getNextStartedService())!=null)
            if(SyncService.REJECT.equals(started.getAction()))rejected=true;
        assertTrue(rejected);
        assertNull(MatchPins.get(app,"1"));
    }

    private static Button button(View view,String text){
        if(view instanceof Button&&((Button)view).getText().toString().equals(text))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            Button found=button(((ViewGroup)view).getChildAt(i),text);
            if(found!=null)return found;
        }
        return null;
    }

    private static final class Response extends HttpURLConnection {
        private final byte[] body;
        Response(String body){super(null);this.body=body.getBytes(StandardCharsets.UTF_8);}
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public int getResponseCode(){return 200;}
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
    }
}
