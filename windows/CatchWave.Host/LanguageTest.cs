// SPDX-License-Identifier: GPL-3.0-or-later
using System.Text.RegularExpressions;
namespace CatchWave.Host;

internal static class LanguageTest
{
    internal static int Run()
    {
        bool saved = L.Russian; int checks = 0;
        try
        {
            L.Russian = false;
            Check(L.T("Audio source") == "Audio source", "English text is used as is"); checks++;
            L.Russian = true;
            Check(L.T("Audio source") == "Источник звука", "Russian table is used when chosen"); checks++;
            Check(L.T("not in the table") == "not in the table", "unknown text falls back to English"); checks++;
            foreach (var (english, russian) in L.RussianTable)
            {
                Check(russian.Length > 0 && Markers(english) == Markers(russian), "format markers match: " + english); checks++;
            }
        }
        finally { L.Russian = saved; }
        return checks;
    }

    private static string Markers(string text) => string.Join(",", Regex.Matches(text, @"\{\d+\}|\n").Select(m => m.Value).Order());

    private static void Check(bool ok, string what)
    {
        if (!ok) throw new InvalidOperationException("Language check failed: " + what);
    }
}
