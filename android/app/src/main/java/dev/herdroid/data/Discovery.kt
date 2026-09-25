package dev.herdroid.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** An SSH server found nearby, with how it was found. */
data class FoundServer(
    val host: String,
    val name: String?,
    val source: Source,
    /** The server's SSH greeting, e.g. "SSH-2.0-OpenSSH_10.5". */
    val banner: String?,
    val port: Int = 22,
) {
    enum class Source(
        val label: String,
    ) {
        TAILSCALE("Tailscale"),
        BONJOUR("Bonjour"),
        LAN("Wi-Fi"),
    }

    /** "OpenSSH 10.5" from the greeting, for display. */
    val software: String?
        get() =
            banner
                ?.substringAfter("SSH-2.0-", "")
                ?.substringBefore(' ')
                ?.replace('_', ' ')
                ?.ifBlank { null }
}

/** A tailnet device as a connected server's `tailscale status` reported it. */
data class TailnetPeer(
    val name: String,
    val ip: String,
)

/**
 * Finds SSH servers: Bonjour (_ssh._tcp), port 22 on the Wi-Fi subnet, and tailnet peers
 * learned from a connected server. Every candidate is confirmed by reading its SSH greeting.
 */
class Discovery(
    private val context: Context,
) {
    fun scan(tailnet: List<TailnetPeer>): Flow<FoundServer> =
        channelFlow {
            val seen = HashSet<String>()
            val probes = Semaphore(48)

            fun probe(
                host: String,
                name: String?,
                source: FoundServer.Source,
            ) {
                launch {
                    probes.withPermit {
                        val banner = sshBanner(host, 22) ?: return@withPermit
                        val first = synchronized(seen) { seen.add(host) }
                        if (first) send(FoundServer(host, name ?: reverseName(host), source, banner))
                    }
                }
            }

            // Tailnet peers first: they are the likely herdr hosts and reachable from anywhere.
            tailnet.forEach { probe(it.ip, it.name, FoundServer.Source.TAILSCALE) }
            launch {
                bonjour().collect { (host, name) -> probe(host, name, FoundServer.Source.BONJOUR) }
            }
            wifiSubnet().forEach { probe(it, null, FoundServer.Source.LAN) }
        }.flowOn(Dispatchers.IO)

    /** Hosts in the Wi-Fi network's IPv4 subnet (capped at a /24 around the phone). */
    private fun wifiSubnet(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()

        @Suppress("DEPRECATION")
        val wifi =
            cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                ?: return emptyList()
        val address =
            cm
                .getLinkProperties(wifi)
                ?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address } ?: return emptyList()
        val bytes = address.address
        val self = address.hostAddress
        return (1..254)
            .map { "${bytes[0].toUByte()}.${bytes[1].toUByte()}.${bytes[2].toUByte()}.$it" }
            .filter { it != self }
    }

    /** Services advertising `_ssh._tcp` for a few seconds, as (address, name). */
    private fun bonjour(): Flow<Pair<String, String>> =
        callbackFlow {
            val nsd =
                context.getSystemService(NsdManager::class.java) ?: run {
                    close()
                    return@callbackFlow
                }
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(service: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(
                            service,
                            object : NsdManager.ResolveListener {
                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    @Suppress("DEPRECATION")
                                    val host = info.host?.hostAddress ?: return
                                    if (info.host is Inet4Address) trySendBlocking(host to info.serviceName)
                                }

                                override fun onResolveFailed(
                                    info: NsdServiceInfo,
                                    errorCode: Int,
                                ) {}
                            },
                        )
                    }

                    override fun onServiceLost(service: NsdServiceInfo) {}

                    override fun onDiscoveryStarted(serviceType: String) {}

                    override fun onDiscoveryStopped(serviceType: String) {}

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        Log.w("Discovery", "Bonjour discovery failed: $errorCode")
                        close()
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {}
                }
            nsd.discoverServices("_ssh._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            launch {
                delay(6_000)
                close()
            }
            awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
        }

    /** The router's name for a LAN address ("nas.lan" becomes "nas"), if it has one. */
    private suspend fun reverseName(host: String): String? =
        withTimeoutOrNull(800) {
            runCatching {
                InetAddress
                    .getByName(host)
                    .canonicalHostName
                    .takeIf { it != host }
                    ?.substringBefore('.')
            }.getOrNull()
        }

    companion object {
        /** Connects and reads the server's first line; null unless it greets like SSH. */
        suspend fun sshBanner(
            host: String,
            port: Int,
        ): String? =
            withTimeoutOrNull(1_500) {
                coroutineScope {
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(InetAddress.getByName(host), port), 700)
                            socket.soTimeout = 800
                            val line = socket.getInputStream().bufferedReader().readLine()
                            line?.takeIf { it.startsWith("SSH-") }
                        }
                    }.getOrNull()
                }
            }
    }
}
