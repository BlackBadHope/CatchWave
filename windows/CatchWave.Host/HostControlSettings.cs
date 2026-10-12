// SPDX-License-Identifier: GPL-3.0-or-later
using System.Text.Json;

namespace CatchWave.Host;

/** Only an owner's control preference; no token, certificate, peer identity or network rule. */
internal sealed class HostControlSettings(string path)
{
    internal static HostControlSettings Local() =>
        new(
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "CatchWave",
                "host-control21.json"
            )
        );

    internal bool Load()
    {
        try
        {
            return JsonSerializer.Deserialize<bool>(File.ReadAllText(path));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            return false;
        }
    }

    internal bool Save(bool enabled)
    {
        string temp = path + ".tmp";
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
            File.WriteAllText(temp, JsonSerializer.Serialize(enabled));
            File.Move(temp, path, true);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
