// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class LanguageTest {
    @After public void english() { L.set(RuntimeEnvironment.getApplication(), false); }

    @Test public void englishIsTheDefaultAndRussianIsAnExplicitChoice() {
        RuntimeEnvironment.getApplication().getSharedPreferences("catchwave_language", 0).edit().clear().commit();
        var home = Robolectric.buildActivity(HomeActivity.class).create().get();
        assertFalse(L.russian());
        assertTrue(texts(home.getWindow().getDecorView()).contains("Open PC audio"));
        Button language = button(home.getWindow().getDecorView(), "Language: English · Русский");
        assertNotNull(language);
        language.performClick();
        assertTrue(L.russian());
        var again = Robolectric.buildActivity(HomeActivity.class).create().get();
        assertTrue(texts(again.getWindow().getDecorView()).contains("Открыть звук с ПК"));
        assertNotNull(button(again.getWindow().getDecorView(), "Язык: русский · English"));
    }

    @Test public void unknownTextFallsBackToEnglish() {
        L.set(RuntimeEnvironment.getApplication(), true);
        assertEquals("Громкость телефона", L.t("Phone volume"));
        assertEquals("not in the table", L.t("not in the table"));
    }

    @Test public void everyRussianEntryKeepsFormatMarkers() {
        Pattern marker = Pattern.compile("%[-+ 0#]*\\d*(?:\\.\\d+)?[a-zA-Z]|\\n");
        for (Map.Entry<String, String> entry : L.russianTable().entrySet()) {
            assertFalse(entry.getKey(), entry.getValue().isEmpty());
            assertEquals(entry.getKey(), markers(marker, entry.getKey()), markers(marker, entry.getValue()));
        }
    }

    private static List<String> markers(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) found.add(m.group());
        found.sort(null);
        return found;
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
