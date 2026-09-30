package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import org.json.JSONObject
import java.io.File

/**
 * Bootstrap mínimo de Steam para la edición Steam-only.
 *
 * El catálogo general de escritorio/emuladores del proyecto original fue eliminado. Este objeto
 * solo conoce el seed ARM64 de Proton que se coloca antes del primer inicio de Steam; después de
 * esa primera instalación, el propio cliente de Steam administra y actualiza la herramienta.
 */
object SteamSeedCatalog {
    private const val TAG = "SteamSeedCatalog"
    const val STEAM_SEED_URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/steam-seed.json"
    const val PROTON_SEED_ID = "proton-arm64"

    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val url: String,
        val sha256: String,
        val size: Long,
    )

    private fun marker(context: Context, id: String) =
        File(LinuxRuntime.rootDir(context), ".droiddeck-pkg-$id")

    fun installed(context: Context, id: String): String? =
        FileUtils.readString(marker(context, id))?.trim()?.takeIf { it.isNotEmpty() }

    fun protonSeedNeeded(context: Context): Boolean =
        installed(context, PROTON_SEED_ID) == null &&
            !File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/appmanifest_4427310.acf").isFile

    /** Lee exclusivamente el catálogo de bootstrap de Steam. */
    fun fetch(): List<Entry>? {
        val body = Downloader.downloadString(STEAM_SEED_URL) ?: return null
        return try {
            val arr = JSONObject(body).getJSONArray("packages")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    version = o.optString("version", ""),
                    url = o.getString("url"),
                    sha256 = o.optString("sha256", ""),
                    size = o.optLong("size", 0L),
                )
            }.filter { it.id == PROTON_SEED_ID }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo leer el catálogo de bootstrap de Steam", e)
            null
        }
    }

    /** Descarga/verifica/extrae un seed tar sobre el rootfs. Devuelve null si tuvo éxito. */
    fun install(context: Context, entry: Entry, listener: LinuxRuntimeInstaller.ProgressListener?): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!root.isDirectory) return "El entorno Linux no está instalado"
        if (entry.id != PROTON_SEED_ID) return "El paquete no pertenece al bootstrap de Steam"
        if (entry.sha256.isEmpty()) return "El catálogo de Steam no incluye suma SHA-256 para ${entry.name}"

        val download = File(context.cacheDir, "steam-seed-${entry.id}.download")
        try {
            listener?.onProgress("Conectando para descargar ${entry.name}…", -1)
            listener?.onProgress("Descargando ${entry.name}", 0)
            val ok = Downloader.downloadFile(entry.url, download, true) { f ->
                listener?.onProgress("Descargando ${entry.name}", if (f < 0) -1 else Math.round(f * 100f))
            }
            if (!ok) return "La descarga falló"
            listener?.onProgress("Verificando integridad", -1)
            if (!entry.sha256.equals(Hashes.sha256(download), ignoreCase = true)) {
                return "La suma SHA-256 no coincide; no se cambió nada"
            }
            listener?.onProgress("Descomprimiendo e instalando ${entry.name}", -1)
            if (!LinuxRuntimeInstaller.extract(download, root, listener)) return "No se pudo extraer el paquete"
            listener?.onProgress("Registrando ${entry.name} en Steam", 100)
            if (!FileUtils.writeString(marker(context, entry.id), entry.version)) {
                return "El paquete se extrajo, pero no se pudo guardar su versión instalada"
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "falló la instalación de ${entry.id}", e)
            return e.message ?: "La instalación falló"
        } finally {
            download.delete()
        }
    }
}
