package app.ipatlas.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** What the phone itself knows about its connection: no internet requests needed. */
data class DeviceNet(
    val connected: Boolean,
    val transport: Transport,
    val vpn: Boolean,
    /** What the VPN runs over (Wi-Fi, mobile data), when a VPN is on. */
    val underlying: Transport?,
    val localV4: List<String>,
    val localV6: List<String>,
    val gateway: String?,
    val dns: List<String>,
    /** "Off", "Automatic" or the Private DNS server's name. */
    val privateDns: String?,
    val iface: String?,
    val mtu: Int?,
    val metered: Boolean?,
    val validated: Boolean?,
    val downMbps: Double?,
) {
    /** Changes that mean the public address may be different now. */
    val identity: String get() = "$connected/$transport/$vpn/$underlying/${localV4.firstOrNull()}"

    companion object {
        val NONE = DeviceNet(
            connected = false, transport = Transport.NONE, vpn = false, underlying = null,
            localV4 = emptyList(), localV6 = emptyList(), gateway = null, dns = emptyList(), privateDns = null,
            iface = null, mtu = null, metered = null, validated = null, downMbps = null,
        )
    }
}

enum class Transport(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile data"),
    ETHERNET("Ethernet"),
    VPN("VPN"),
    BLUETOOTH("Bluetooth"),
    SATELLITE("Satellite"),
    OTHER("Other"),
    NONE("Not connected"),
}

object DeviceNetwork {

    fun read(context: Context): DeviceNet {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return DeviceNet.NONE
        val active = cm.activeNetwork ?: return DeviceNet.NONE
        val caps = cm.getNetworkCapabilities(active) ?: return DeviceNet.NONE
        val lp = cm.getLinkProperties(active)
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val transport = transportOf(caps)
        val underlying = if (vpn) underlyingTransport(cm, active) else null
        // With a VPN on, the phone's real local address and gateway sit on the network underneath.
        val baseLp = if (vpn) underlyingNetwork(cm, active)?.let { cm.getLinkProperties(it) } ?: lp else lp
        val addresses = baseLp?.linkAddresses.orEmpty()
        val v4 = addresses.filter { it.address is Inet4Address }.map { "${host(it.address)}/${it.prefixLength}" }
        val v6 = addresses.filter { it.address is Inet6Address }
            .sortedBy { if (it.address.isLinkLocalAddress) 1 else 0 }
            .map { "${host(it.address)}/${it.prefixLength}" }
        val gateway = baseLp?.routes.orEmpty()
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway }
            .firstOrNull { !it.isAnyLocalAddress }
            ?.let { host(it) }
        val privateDns = if (Build.VERSION.SDK_INT >= 28 && lp != null) {
            when {
                lp.privateDnsServerName != null -> lp.privateDnsServerName
                lp.isPrivateDnsActive -> "Automatic"
                else -> "Off"
            }
        } else {
            null
        }
        val mtu = if (Build.VERSION.SDK_INT >= 29) lp?.mtu?.takeIf { it > 0 } else null
        val down = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1000.0 }
        return DeviceNet(
            connected = true,
            transport = transport,
            vpn = vpn,
            underlying = underlying,
            localV4 = v4,
            localV6 = v6,
            gateway = gateway,
            dns = lp?.dnsServers.orEmpty().map { host(it) }.distinct(),
            privateDns = privateDns,
            iface = lp?.interfaceName,
            mtu = mtu,
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            downMbps = down,
        )
    }

    private fun host(a: InetAddress): String = (a.hostAddress ?: "").substringBefore('%')

    private fun transportOf(caps: NetworkCapabilities): Transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> Transport.BLUETOOTH
        Build.VERSION.SDK_INT >= 35 && caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> Transport.SATELLITE
        else -> Transport.OTHER
    }

    @Suppress("DEPRECATION")
    private fun underlyingNetwork(cm: ConnectivityManager, vpnNetwork: Network): Network? =
        cm.allNetworks.firstOrNull { n ->
            if (n == vpnNetwork) return@firstOrNull false
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    private fun underlyingTransport(cm: ConnectivityManager, vpnNetwork: Network): Transport? =
        underlyingNetwork(cm, vpnNetwork)?.let { n -> cm.getNetworkCapabilities(n)?.let { transportOf(it) } }

    /** Calls [onChange] whenever the phone's connection changes. Returns a function that stops listening. */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()
        }
        val stop: () -> Unit = { runCatching { cm.unregisterNetworkCallback(callback) } }
        val nothing: () -> Unit = {}
        return try {
            cm.registerDefaultNetworkCallback(callback)
            stop
        } catch (e: Exception) {
            nothing
        }
    }
}
