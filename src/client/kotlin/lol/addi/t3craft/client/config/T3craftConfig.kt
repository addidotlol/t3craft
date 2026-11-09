package lol.addi.t3craft.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

enum class HudAnchor(val labelKey: String, val right: Boolean, val bottom: Boolean) {
    TOP_LEFT("t3craft.anchor.top_left", right = false, bottom = false),
    TOP_RIGHT("t3craft.anchor.top_right", right = true, bottom = false),
    BOTTOM_LEFT("t3craft.anchor.bottom_left", right = false, bottom = true),
    BOTTOM_RIGHT("t3craft.anchor.bottom_right", right = true, bottom = true),
}

data class T3craftConfig(
    var serverUrl: String = discoverLocalServerUrl(),
    var hudEnabled: Boolean = true,
    var hudAnchor: HudAnchor = HudAnchor.TOP_RIGHT,
    var hudOffsetX: Int = 4,
    var hudOffsetY: Int = 4,
    var hudScale: Float = 1.0f,
    var hudMaxRows: Int = 6,
    var showReadyThreads: Boolean = true,
    var notifyOnChange: Boolean = true,
    var pollSeconds: Int = 5,
) {
    fun normalizedServerUrl(): String = serverUrl.trim().trimEnd('/')
}

data class StoredCredential(
    val serverUrl: String,
    val accessToken: String,
    val expiresAtEpochMillis: Long,
) {
    fun isUsableFor(server: String): Boolean =
        serverUrl == server && System.currentTimeMillis() < expiresAtEpochMillis
}

object ConfigStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val directory: Path = FabricLoader.getInstance().configDir.resolve("t3craft")
    private val configFile: Path = directory.resolve("config.json")
    private val credentialFile: Path = directory.resolve("credential.json")

    var config: T3craftConfig = load()
        private set

    var credential: StoredCredential? = loadCredential()
        private set

    fun save() {
        Files.createDirectories(directory)
        Files.writeString(configFile, gson.toJson(config))
    }

    fun saveCredential(value: StoredCredential?) {
        credential = value
        if (value == null) {
            Files.deleteIfExists(credentialFile)
            return
        }
        Files.createDirectories(directory)
        Files.writeString(credentialFile, gson.toJson(value))
        restrictToOwner(credentialFile)
    }

    private fun load(): T3craftConfig =
        readJson(configFile, T3craftConfig::class.java)?.sanitized() ?: T3craftConfig().also {
            config = it
            save()
        }

    private fun loadCredential(): StoredCredential? = readJson(credentialFile, StoredCredential::class.java)

    private fun <T> readJson(file: Path, type: Class<T>): T? =
        runCatching { if (Files.exists(file)) gson.fromJson(Files.readString(file), type) else null }.getOrNull()

    private fun T3craftConfig.sanitized(): T3craftConfig {
        val defaults = T3craftConfig()
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        return copy(
            serverUrl = if (serverUrl.isNullOrBlank()) defaults.serverUrl else serverUrl,
            hudAnchor = hudAnchor ?: defaults.hudAnchor,
            hudScale = hudScale.coerceIn(0.5f, 2.0f),
            hudMaxRows = hudMaxRows.coerceIn(1, 20),
            pollSeconds = pollSeconds.coerceIn(2, 60),
        )
    }

    private fun restrictToOwner(file: Path) {
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }
}

private fun discoverLocalServerUrl(): String {
    val home = System.getenv("T3CODE_HOME")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".t3")
    val runtimeFile = home.resolve("userdata").resolve("server-runtime.json")
    return runCatching {
        JsonParser.parseString(Files.readString(runtimeFile)).asJsonObject.get("origin").asString
    }.getOrDefault("http://127.0.0.1:3773")
}
