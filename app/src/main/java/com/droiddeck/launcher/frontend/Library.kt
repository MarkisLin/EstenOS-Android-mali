package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import java.io.File

/**
 * Biblioteca Steam-only del fork Mali-first. Lee los juegos instalados del cliente Steam y sus
 * bibliotecas; no descubre ROMs ni emuladores.
 */
object Library {
    /** [gameId] is what steam://rungameid/ takes: the appid for a Steam title, the shortcut id for an added game. */
    /**
     * [art] is the portrait capsule; [hero] the wide banner Steam shows above a game's page, when
     * the client has cached one. [lastPlayed] is Steam's own "LastPlayed" (unix seconds), 0 = never.
     */
    class SteamGame(
        val appId: Int, val name: String, val art: File?, val library: String, val gameId: Long = appId.toLong(),
        val hero: File? = null, val lastPlayed: Long = 0L,
        val gameFiles: File? = null, val protonPrefix: File? = null,
    )
    /** The client's own tools and runtimes live in steamapps beside the games; they are not titles. */
    private val NOT_GAMES = setOf(
        858280,  // Proton 3.7
        961940,  // Proton 3.16
        993090,  // Lossless Scaling
        1054830, // Proton 4.2
        1070560, // Steam Linux Runtime 1.0
        1113280, // Proton 4.11
        1245040, // Proton 5.0
        1391110, // Steam Linux Runtime 2.0
        1420170, // Proton 5.13
        1493710, // Proton Experimental
        1580130, // Proton 6.3
        1628350, // Steam Linux Runtime 3.0
        1887720, // Proton 7
        2180100, // Proton Hotfix
        228980,  // Steamworks Common Redistributables
        2348590, // Proton 8
        2805730, // Proton 9
        3029110, // Lepton
        3127680, // FEX
        3658110, // Proton 10
        4183110, // Steam Linux Runtime 4.0
        4185400, // Steam Linux Runtime 4.0 for ARM64
        4427310, // Proton Experimental for ARM64
        4628710, // Proton 11 / Proton Next
        4628740, // Proton 11 for ARM64
        4690330, // Legacy Steam Runtime
    )
    private val STEAM_CAPSULES = listOf("library_capsule.jpg", "library_600x900.jpg")
    private val STEAM_HEROES = listOf("library_hero.jpg")
    private val NAME = Regex("^\\s*\"name\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val STATE = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val LAST_PLAYED = Regex("^\\s*\"LastPlayed\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val INSTALL_DIR = Regex("^\\s*\"installdir\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)

    /** Steam's library roots visible to this launcher: its private default plus the selected library. */
    private fun steamLibraries(context: Context): List<Pair<File, String>> {
        val root = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
        return listOfNotNull(
            root to "internal",
            GameStorage.effective(context)?.let { File(it.path) to it.label },
        )
    }

    /** Proton keeps each game's prefix below compatdata/<appid>/pfx in a Steam library. */
    fun protonPrefix(context: Context, appId: Long, preferredLibrary: File? = null): File? {
        val ids = listOf(appId.toString(), java.lang.Integer.toString(appId.toInt())).distinct()
        val roots = (listOfNotNull(preferredLibrary) + steamLibraries(context).map { it.first })
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        return roots.asSequence()
            .flatMap { root -> ids.asSequence().map { id -> File(root, "steamapps/compatdata/$id/pfx") } }
            .firstOrNull { it.isDirectory }
    }

    fun steamGames(context: Context): List<SteamGame> {
        val root = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
        val cache = File(root, "appcache/librarycache")
        val libraries = steamLibraries(context)
        val out = LinkedHashMap<Int, SteamGame>()
        for ((library, label) in libraries) {
            val steamapps = File(library, "steamapps")
            steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
                ?.sortedBy { it.name }?.forEach { manifest ->
                    val appId = manifest.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: return@forEach
                    if (appId in NOT_GAMES || out.containsKey(appId)) return@forEach
                    val text = try { manifest.readText() } catch (e: Exception) { return@forEach }
                    val name = NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty()
                    val flags = STATE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    // StateFlags 4 = fully installed; anything else is downloading, updating or broken.
                    if (name.isEmpty() || flags and 4 == 0) return@forEach
                    val art = steamCacheImage(cache, appId, STEAM_CAPSULES)
                    val hero = steamCacheImage(cache, appId, STEAM_HEROES)
                    val lastPlayed = LAST_PLAYED.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    // App manifests are Valve KeyValues (VDF) files. installdir is one folder
                    // below steamapps/common; only expose it when the directory exists and the
                    // manifest value cannot escape that directory.
                    val installDir = INSTALL_DIR.find(text)?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
                    val gameFiles = installDir?.let { File(steamapps, "common/$it").takeIf(File::isDirectory) }
                    out[appId] = SteamGame(
                        appId, name, art, label, hero = hero, lastPlayed = lastPlayed,
                        gameFiles = gameFiles,
                        protonPrefix = protonPrefix(context, appId.toLong(), library),
                    )
                }
        }
        return out.values.toList()
    }

    /** Steam stores current library art inside hash-named folders under the app's cache dir. */
    private fun steamCacheImage(cache: File, appId: Int, names: List<String>): File? {
        val appDir = File(cache, appId.toString())
        val dirs = listOf(appDir) + appDir.listFiles()
            .orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        return names.asSequence()
            .flatMap { name -> dirs.asSequence().map { File(it, name) } }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

}
