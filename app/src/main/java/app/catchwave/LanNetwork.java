// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.content.Context;
import android.net.*;
import java.net.*;
import java.io.IOException;

/** Use the already connected Wi-Fi route to the paired LAN address, even without internet. */
final class LanNetwork {
    static boolean reaches(NetworkCapabilities capabilities,LinkProperties links,InetAddress target){
        return capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            &&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&links!=null
            &&links.getRoutes().stream().anyMatch(route->!route.isDefaultRoute()&&route.matches(target));
    }
    static void bind(Context context,Socket socket,String host)throws IOException {
        InetAddress target=InetAddress.getByName(host);if(target.isLoopbackAddress())return;
        ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);if(connectivity==null)return;
        NetworkCapabilities active=connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        // Preserve an owner-selected VPN rather than bypassing it for a private address.
        if(active!=null&&active.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return;
        for(Network network:connectivity.getAllNetworks())if(reaches(connectivity.getNetworkCapabilities(network),connectivity.getLinkProperties(network),target)){
            network.bindSocket(socket);DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"WIFI_ROUTE_BOUND");return;
        }
    }
}
