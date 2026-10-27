// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import static org.junit.Assert.*;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class MatchPinsTest {
    private static final String URL="https://music.youtube.com/watch?v=abcdefghijk";
    private Context context;
    @Before public void fresh(){
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("match_pins",Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void putGetRemoveRoundTrip(){
        assertNull(MatchPins.get(context,"k"));
        MatchPins.put(context,"k",URL,"Hello (Remix)","Adele");
        MatchPins.Pin pin=MatchPins.get(context,"k");
        assertNotNull(pin);
        assertEquals(URL,pin.url);
        assertEquals("Hello (Remix)",pin.title);
        MatchPins.put(context,"k","https://music.youtube.com/watch?v=aaaaaaaaaaa","Other","Adele");
        assertEquals("https://music.youtube.com/watch?v=aaaaaaaaaaa",MatchPins.get(context,"k").url);
        MatchPins.remove(context,"k");
        assertNull(MatchPins.get(context,"k"));
    }

    @Test public void invalidUrlsAndKeysAreNeverStored(){
        MatchPins.put(context,"k","javascript:alert(1)","T","A");
        MatchPins.put(context,"k","https://evil.example/watch?v=abcdefghijk","T","A");
        MatchPins.put(context,"","", "T","A");
        assertNull(MatchPins.get(context,"k"));
        assertNull(MatchPins.get(context,""));
        assertNull(MatchPins.get(context,null));
    }

    @Test public void oldestPinIsEvictedAtTheLimit(){
        for(int i=0;i<=MatchPins.LIMIT;i++)MatchPins.put(context,"key"+i,URL,"T"+i,"A");
        assertNull(MatchPins.get(context,"key0"));
        assertNotNull(MatchPins.get(context,"key1"));
        assertNotNull(MatchPins.get(context,"key"+MatchPins.LIMIT));
    }

    @Test public void corruptedPreferencesLeaveAnEmptyWorkingStore(){
        context.getSharedPreferences("match_pins",Context.MODE_PRIVATE).edit().putString("pins","{broken").commit();
        assertNull(MatchPins.get(context,"k"));
        MatchPins.put(context,"k",URL,"T","A");
        assertEquals(URL,MatchPins.get(context,"k").url);
    }
}
