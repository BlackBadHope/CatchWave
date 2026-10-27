// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class MomentsUiTest {
    private MomentStore store;
    @Before public void fresh() { store=MomentStore.get(RuntimeEnvironment.getApplication()); store.clear(); }
    @After public void cleanup() { store.clear(); L.set(RuntimeEnvironment.getApplication(), false); }

    @Test public void emptyHistoryExplainsItselfAndDisablesExport() {
        var screen = Robolectric.buildActivity(MomentsActivity.class).create().get();
        View root = screen.getWindow().getDecorView();
        assertTrue(texts(root).stream().anyMatch(t -> t.startsWith("No moments yet")));
        Button share = button(root, "Share list");
        assertNotNull(share);
        assertFalse(share.isEnabled());
        assertFalse(button(root, "Clear").isEnabled());
    }

    @Test public void momentsRenderNewestFirstWithPositionAndSurviveFavoriteAndDelete() {
        store.add("k1", "First Song", "Artist One", "", 61_000);
        store.add("k2", "Second Song", "Artist Two", "https://music.youtube.com/watch?v=abcdefghijk", 30_000);
        var screen = Robolectric.buildActivity(MomentsActivity.class).create().get();
        View root = screen.getWindow().getDecorView();
        List<String> all = texts(root);
        assertTrue(all.stream().anyMatch(t -> t.contains("Second Song")));
        int first = all.indexOf(all.stream().filter(t -> t.contains("First Song")).findFirst().orElse(""));
        int second = all.indexOf(all.stream().filter(t -> t.contains("Second Song")).findFirst().orElse(""));
        assertTrue(second < first);
        assertTrue(all.stream().anyMatch(t -> t.startsWith("At 1:01")));
        button(root, "☆").performClick();
        assertTrue(store.all().get(0).favorite);
        assertNotNull(button(screen.getWindow().getDecorView(), "★"));
        button(screen.getWindow().getDecorView(), "✕").performClick();
        assertEquals(1, store.all().size());
        assertEquals("First Song", store.all().get(0).title);
    }

    @Test public void russianScreenUsesTranslatedStrings() {
        L.set(RuntimeEnvironment.getApplication(), true);
        store.add("k", "Song", "Artist", "", 5_000);
        var screen = Robolectric.buildActivity(MomentsActivity.class).create().get();
        List<String> all = texts(screen.getWindow().getDecorView());
        assertTrue(all.contains("Музыкальные моменты"));
        assertNotNull(button(screen.getWindow().getDecorView(), "Поделиться списком"));
        assertTrue(all.stream().anyMatch(t -> t.startsWith("На 0:05")));
    }

    private static List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        if (view instanceof TextView) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.addAll(texts(((ViewGroup) view).getChildAt(i)));
        return out;
    }

    private static Button button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
}
