// SPDX-License-Identifier: GPL-3.0-or-later
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace CatchWave.Host;

internal static class InteropTest
{
    internal static async Task<int> Run(string java, string classPath)
    {
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        using var identity = new HostIdentity();
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(1);
        var status = new HostStatus();
        Task server = Task.Run(async () =>
        {
            using var peer = await listener.AcceptTcpClientAsync(stop.Token);
            try
            {
                await new HostSession(
                    identity,
                    true,
                    false,
                    _ => throw new Exception("No media input allowed"),
                    status: status
                ).Run(peer, stop.Token);
            }
            catch (IOException) { }
            catch (OperationCanceledException) { }
            ;
        });
        try
        {
            var start = new ProcessStartInfo(java)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            start.ArgumentList.Add("-cp");
            start.ArgumentList.Add(classPath);
            start.ArgumentList.Add("app.catchwave.WireClient");
            using var client = Process.Start(start) ?? throw new IOException("JVM could not start");
            await client.StandardInput.WriteLineAsync(
                ((IPEndPoint)listener.LocalEndpoint).Port.ToString()
            );
            await client.StandardInput.WriteLineAsync(
                Convert.ToBase64String(SHA256.HashData(identity.Certificate.RawData))
            );
            await client.StandardInput.WriteLineAsync(Convert.ToBase64String(identity.Token));
            client.StandardInput.Close();
            Task<string> output = client.StandardOutput.ReadToEndAsync(),
                error = client.StandardError.ReadToEndAsync();
            try
            {
                await client.WaitForExitAsync(stop.Token);
            }
            catch
            {
                if (!client.HasExited)
                    client.Kill();
                throw;
            }
            string line = await output;
            string detail = await error;
            await server.WaitAsync(stop.Token);
            if (
                client.ExitCode != 0
                || !line.StartsWith("PASS:", StringComparison.Ordinal)
                || detail.Length != 0
                || status.Snapshot.Frames < 20
            )
                throw new IOException("Interop failed");
            Console.WriteLine(line.Trim());
            Console.WriteLine(
                "Loopback only; no owner QR, Android installation, Windows audio capture or media keys."
            );
            return 0;
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("FAIL: interop category=" + e.GetType().Name);
            return 1;
        }
        finally
        {
            stop.Cancel();
            listener.Stop();
            try
            {
                await server.WaitAsync(TimeSpan.FromSeconds(2));
            }
            catch (Exception) { }
            ;
        }
    }
}
