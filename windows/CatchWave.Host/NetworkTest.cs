// SPDX-License-Identifier: GPL-3.0-or-later
using System.Net;
using System.Net.Sockets;

namespace CatchWave.Host;

internal static class NetworkTest
{
    internal static async Task<int> Run()
    {
        int checks = 0;
        void Check(bool value, string reason)
        {
            if (!value)
                throw new Exception(reason);
            checks++;
        }
        var lan = new HostEndpoint("192.168.1.20", "LAN");
        var usb = new HostEndpoint("172.31.92.128", "USB", true);
        Check(
            HostNetwork.UsbTether("Remote NDIS based Internet Sharing Device #2")
                && !HostNetwork.UsbTether("Realtek USB GbE Family Controller"),
            "USB Ethernet dongle is not a phone modem"
        );
        Check(HostNetwork.Choose("auto", new[] { lan, usb }) == usb, "auto prefers USB");
        Check(HostNetwork.Choose("auto", new[] { lan }) == lan, "auto falls back to LAN");
        Check(
            HostNetwork.Choose(lan.Address, new[] { lan, usb }) == lan,
            "explicit LAN survives USB availability"
        );
        Check(
            HostNetwork.PrivateV4(IPAddress.Parse("172.31.92.128"))
                && !HostNetwork.PrivateV4(IPAddress.Parse("172.32.1.1"))
                && !HostNetwork.PrivateV4(IPAddress.IPv6Loopback)
                && !HostNetwork.PrivateV4(IPAddress.Parse("169.254.1.2")),
            "exclude public, IPv6 and link-local auto addresses"
        );
        try
        {
            HostNetwork.Choose("0.0.0.0", []);
            throw new Exception("wildcard admitted");
        }
        catch (ArgumentException)
        {
            checks++;
        }
        try
        {
            HostNetwork.Choose("auto", []);
            throw new Exception("missing network admitted");
        }
        catch (ArgumentException)
        {
            checks++;
        }
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelled = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously
        );
        await using (
            var server = new HostListener(
                stop.Token,
                async (peer, token) =>
                {
                    entered.TrySetResult();
                    try
                    {
                        await Task.Delay(Timeout.Infinite, token);
                    }
                    catch (OperationCanceledException)
                    {
                        cancelled.TrySetResult();
                    }
                }
            )
        )
        {
            var reserve = new TcpListener(IPAddress.Loopback, 0);
            reserve.Start();
            int port = ((IPEndPoint)reserve.LocalEndpoint).Port;
            reserve.Stop();
            await server.Select("127.0.0.1", port);
            using var client = new TcpClient();
            await client.ConnectAsync("127.0.0.1", port, stop.Token);
            await entered.Task.WaitAsync(stop.Token);
            await server.Select("127.0.0.2", port);
            await cancelled.Task.WaitAsync(stop.Token);
            Check(
                server.Address == "127.0.0.2",
                "switch cancels old session and binds new address"
            );
            var busy = new TcpListener(IPAddress.Parse("127.0.0.3"), port);
            busy.Start();
            try
            {
                await server.Select("127.0.0.3", port);
                throw new Exception("busy bind admitted");
            }
            catch (SocketException)
            {
                Check(server.Address == "127.0.0.2", "bind failure preserves previous route");
            }
            finally
            {
                busy.Stop();
            }
            using var next = new TcpClient();
            await next.ConnectAsync("127.0.0.2", port, stop.Token);
            Check(next.Connected, "previous route still accepts after failed switch");
        }
        using var alreadyStopped = new CancellationTokenSource();
        alreadyStopped.Cancel();
        await using (
            var server = new HostListener(alreadyStopped.Token, (_, _) => Task.CompletedTask)
        )
            try
            {
                await server.Select("127.0.0.1", 49372);
                throw new Exception("cancelled bind admitted");
            }
            catch (OperationCanceledException)
            {
                checks++;
            }
        return checks;
    }
}
