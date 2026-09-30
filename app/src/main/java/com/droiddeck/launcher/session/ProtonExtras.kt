package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONArray

/** Immediate download and installation of the optional ARM64 Proton builds. */
object ProtonExtras {
    private const val TAG = "ProtonExtras"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val RELEASES = "https://api.github.com/repos/%s/releases?per_page=15"
    @Volatile
    var installInProgress = false
        private set

    class Tool(val id: String, val name: String, val prefix: String, val repo: String, val assetPattern: Regex)

    /** [sha256] from GitHub's asset digest; [sha512] the URL of a checksum file published beside it. */
    private data class Asset(val tag: String, val name: String, val url: String, val sha512: String?, val size: Long,
                             val sha256: String? = null)

    val tools = listOf(
        Tool("ge", "GE-Proton", "GE-Proton", "GloriousEggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz)$")),
        Tool("cachyos", "proton-cachyos", "proton-cachyos", "CachyOS/proton-cachyos", Regex("arm64\\.tar\\.(gz|xz)$")),
    )

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun requests(context: Context) = File(home(context), ".bl-proton-extra")
    private fun toolsDir(context: Context) = File(home(context), ".local/share/Steam/compatibilitytools.d")

    /** The installed build's directory name (e.g. GE-Proton11-7), or null. */
    fun installed(context: Context, tool: Tool): String? =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(tool.prefix) && File(it, "toolmanifest.vdf").isFile }
            ?.maxByOrNull { it.name }?.name

    /** A request left by an earlier app version, still consumed by the session shim. */
    fun queued(context: Context, tool: Tool): Boolean =
        requestLines(context).any { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }

    fun unqueue(context: Context, tool: Tool) {
        val lines = requestLines(context).filterNot { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }
        val file = requests(context)
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    /** Deletes the installed build; Steam discovers its absence on its next start. */
    fun remove(context: Context, tool: Tool) {
        installed(context, tool)?.let { File(toolsDir(context), it).deleteRecursively() }
    }

    /** Downloads, verifies and registers the latest build without waiting for another session. */
    @Synchronized
    fun install(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return "A compatibility tool install is already running"
        installInProgress = true
        return try { installNow(context, tool, onProgress) } finally { installInProgress = false }
    }

    private fun installNow(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return "Detén la sesión activa antes de instalar herramientas de compatibilidad"
        if (!LinuxRuntime.isInstalled(context)) return "Instala primero el entorno Linux"

        onProgress("Buscando la última versión ARM64 de ${tool.name}…", -1)
        val asset = findLatestAsset(tool) ?: return "No se encontró una versión ARM64 para ${tool.name}"
        onProgress("Comprobando espacio disponible…", -1)
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "${tool.id}-${asset.name}")
        if (asset.size > 0 && archive.length() > asset.size) archive.delete()
        val missing = (asset.size - archive.length()).coerceAtLeast(0L)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing) {
            return "Se necesitan al menos 4 GB libres, además del tamaño de la descarga, para instalar ${tool.name}"
        }

        onProgress("Conectando con GitHub…", -1)
        onProgress("Descargando ${tool.name} ${asset.tag}", 0)
        var downloaded = false
        for (attempt in 0 until 5) {
            downloaded = Downloader.downloadFile(asset.url, archive, true) { fraction ->
                onProgress("Descargando ${tool.name} ${asset.tag}", if (fraction < 0) -1 else (fraction * 100f).toInt().coerceIn(0, 100))
            }
            if (downloaded) break
            if (attempt < 4) {
                onProgress("Reintentando descarga · intento ${attempt + 2} de 5", -1)
                try { Thread.sleep(3_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return "Descarga interrumpida"
                }
            }
        }
        if (!downloaded) return "La descarga falló; se conserva el archivo parcial para poder reanudarla"
        if (asset.size > 0 && archive.length() != asset.size) {
            if (archive.length() > asset.size) {
                archive.delete()
                return "La descarga superó el tamaño esperado y se eliminó"
            }
            return "La descarga quedó incompleta; se conserva el archivo parcial para reanudarla"
        }

        // One checksum has to be found and has to match: GitHub's sha256 digest, else the
        // release's own sha512 file. A download neither vouches for is not installed.
        onProgress("Verificando integridad de la descarga…", -1)
        val verified = when {
            asset.sha256 != null -> asset.sha256.equals(Hashes.sha256(archive), ignoreCase = true)
            asset.sha512 != null -> {
                val expected = Downloader.downloadString(asset.sha512)?.let { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it)?.value }
                    ?: return "No se pudo leer la suma de comprobación de la versión; vuelve a intentarlo"
                expected.equals(Hashes.sha512(archive), ignoreCase = true)
            }
            else -> {
                archive.delete()
                return "${tool.name} ${asset.tag} publishes no checksum; nothing was installed"
            }
        }
        if (!verified) {
            archive.delete()
            return "El checksum no coincide; se eliminó la descarga"
        }

        if (SessionState.running) return "Se inició una sesión durante la descarga; detenla antes de instalar herramientas de compatibilidad"
        onProgress("Preparando instalación de ${tool.name}…", -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            com.droiddeck.launcher.session.SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".proton-install-rt").apply { mkdirs() }
            val guest = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                "/usr/local/bin/bannerlator-proton-extra", "/root/.local/share/Steam", archive.absolutePath,
            )
            val command = LinuxRuntime.command(context, null, runtimeDir, null, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    if (line.startsWith("BL_PROGRESS\t")) {
                        val parts = line.split('\t')
                        val phase = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Instalando ${tool.name}…"
                        val pct = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(-1, 100) ?: -1
                        onProgress(phase, pct)
                    } else if (line.contains("unpacking", ignoreCase = true)) {
                        onProgress("Descomprimiendo ${tool.name}…", -1)
                    } else if (line.contains("installed", ignoreCase = true) || line.contains("adopted", ignoreCase = true)) {
                        onProgress("Registrando ${tool.name} en Steam…", -1)
                    }
                }
            }
            val status = child.waitFor()
            if (status != 0) "${tool.name}: falló la instalación (código $status)"
            else {
                onProgress("${tool.name} listo para Steam", 100)
                archive.delete()
                unqueue(context, tool)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install ${tool.id}", e)
            e.message ?: "No se pudo instalar ${tool.name}"
        }
    }

    private fun findLatestAsset(tool: Tool): Asset? {
        val releases = Downloader.downloadString(RELEASES.format(tool.repo)) ?: return null
        return try {
            val array = JSONArray(releases)
            for (i in 0 until array.length()) {
                val release = array.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
                val archive = entries.sortedBy { it.optString("name") }.firstOrNull { tool.assetPattern.containsMatchIn(it.optString("name")) } ?: continue
                val name = archive.optString("name")
                val stem = name.substringBefore(".tar")
                val checksum = entries.firstOrNull {
                    it.optString("name").startsWith(stem) && it.optString("name").contains("sha512", ignoreCase = true)
                }?.optString("browser_download_url")?.takeIf { it.startsWith("http") }
                return Asset(
                    release.optString("tag_name"), name,
                    archive.optString("browser_download_url").takeIf { it.startsWith("http") } ?: continue, checksum,
                    archive.optLong("size", 0L),
                    Hashes.githubSha256(archive.optString("digest")),
                )
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "release metadata for ${tool.id}", e)
            null
        }
    }

    private fun requestLines(context: Context): List<String> =
        FileUtils.readString(requests(context))?.lines()?.filter { it.isNotBlank() } ?: emptyList()
}
