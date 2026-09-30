package com.droiddeck.launcher.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * `network.txt`: estado de red entregado a la sesión, sin guardar SSID ni direcciones personales.
 * El runtime no usa NetworkManager; por eso el panel de red de Steam puede decir que no hay Wi‑Fi
 * aunque la conexión del teléfono esté funcionando. Este informe muestra la ruta que sí importa.
 */
object NetworkReport {
    private const val TAG = "NetworkReport"

    fun write(context: Context, target: File) {
        try {
            val text = LogRedactor.describeAddresses(build(context))
                .replace(Regex("(?m)^(\\s*mac )\\S+"), "$1<oculta>")
            target.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo escribir $target", e)
        }
    }

    private fun build(context: Context): String = buildString {
        append("Red al iniciar la sesión\n")
        append("========================\n")
        append("El SSID no se registra y las direcciones aparecen por tipo, no por su valor.\n\n")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        if (cm == null || network == null) {
            append("Red activa             ninguna · Android informa que no hay conexión\n")
        } else {
            val caps = cm.getNetworkCapabilities(network)
            val transport = when {
                caps == null -> "desconocido"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "otro"
            }
            append("Transporte             ").append(transport).append('\n')
            append("Validada               ")
                .append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: "desconocido")
                .append("   (false con enlace funcional suele indicar un portal cautivo)\n")
            append("Con medición de datos  ")
                .append(caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: "desconocido")
                .append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
                append("Velocidad del enlace   bajada ").append(caps.linkDownstreamBandwidthKbps)
                    .append(" kbps / subida ").append(caps.linkUpstreamBandwidthKbps).append(" kbps\n")
            }
            val link = cm.getLinkProperties(network)
            append("Interfaz               ").append(link?.interfaceName ?: "desconocida").append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append("MTU                    ").append(link?.mtu ?: 0).append('\n')
            }
            val v4 = link?.linkAddresses.orEmpty().count { it.address is Inet4Address }
            val v6 = link?.linkAddresses.orEmpty().count { it.address is Inet6Address }
            append("Direcciones            ").append(v4).append(" IPv4, ").append(v6).append(" IPv6")
                .append(if (v4 == 0) "   (sin IPv4; los servidores de contenido de Steam pueden necesitarla)" else "").append('\n')
            append("DNS del sistema        ")
                .append(link?.dnsServers.orEmpty().joinToString(", ") { it.hostAddress ?: "?" }.ifEmpty { "ninguno" })
                .append('\n')
            append("Dominios de búsqueda   ").append(link?.domains ?: "ninguno").append('\n')
        }

        append("\nLo que recibió el runtime\n")
        append("--------------------------\n")
        val resolv = File(LinuxRuntime.rootDir(context), "etc/resolv.conf")
        if (resolv.isFile) {
            append("etc/resolv.conf:\n")
            FileUtils.readString(resolv)?.lines()?.forEach { append("    ").append(it).append('\n') }
        } else {
            append("etc/resolv.conf todavía no existe; se escribe al publicar la red de la sesión.\n")
        }
        val netdev = File(LinuxRuntime.rootDir(context), "etc/bannerlator-net")
        if (netdev.isFile) {
            append("\netc/bannerlator-net (enlace visto por la sesión):\n")
            FileUtils.readString(netdev)?.lines()?.forEach { append("    ").append(it).append('\n') }
        }
        append("\nproot no crea un espacio de nombres de red: la sesión utiliza directamente la conexión\n")
        append("del teléfono. IPv4 e IPv6 pasan por ella y no hay puertos que reenviar.\n")
        append("El panel de red propio de Steam consulta NetworkManager mediante D-Bus, servicio que este\n")
        append("runtime no incluye. Por eso un aviso de Wi‑Fi desactivado allí puede ser solo visual.\n")
    }
}
