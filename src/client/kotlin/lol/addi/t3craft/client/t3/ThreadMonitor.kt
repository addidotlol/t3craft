package lol.addi.t3craft.client.t3

import lol.addi.t3craft.client.config.ConfigStore
import lol.addi.t3craft.client.config.StoredCredential
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class ConnectionState { SIGNED_OUT, SIGNING_IN, CONNECTING, CONNECTED, UNREACHABLE, SIGN_IN_EXPIRED }

data class MonitorSnapshot(
    val connection: ConnectionState = ConnectionState.SIGNED_OUT,
    val threads: List<ThreadSummary> = emptyList(),
    val error: String? = null,
) {
    fun threadsWith(attention: ThreadAttention): List<ThreadSummary> = threads.filter { it.attention == attention }
}

data class ThreadTransition(val thread: ThreadSummary, val previous: ThreadAttention?)

object ThreadMonitor {
    private val log = LoggerFactory.getLogger("t3craft")
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "t3craft-monitor").apply { isDaemon = true }
    }
    private val modVersion: String = FabricLoader.getInstance().getModContainer("t3craft")
        .map { it.metadata.version.friendlyString }.orElse("dev")
    private val projectRefreshInterval: Duration = Duration.ofSeconds(60)

    @Volatile
    var snapshot: MonitorSnapshot = MonitorSnapshot()
        private set

    @Volatile
    var pendingLogin: PendingLogin? = null
        private set

    var onTransitions: (List<ThreadTransition>) -> Unit = {}

    private var mcp: McpClient? = null
    private var api: T3Api? = null
    private var projects: Map<Environment, List<Project>> = emptyMap()
    private var runStarts: Map<Pair<ThreadRef, String>, Instant> = emptyMap()
    private var projectsFetchedAt: Instant = Instant.EPOCH
    private var pollTask: ScheduledFuture<*>? = null
    private var hasBaseline = false

    fun start() {
        executor.execute { connectWithStoredCredential() }
    }

    fun signIn(): CompletableFuture<PendingLogin> = CompletableFuture.supplyAsync({
        val abandoned = pendingLogin
        pendingLogin = null
        abandoned?.cancel()
        val login = OAuthLogin.start(ConfigStore.config.normalizedServerUrl())
        pendingLogin = login
        snapshot = snapshot.copy(connection = ConnectionState.SIGNING_IN, error = null)
        login.credential.whenCompleteAsync({ credential, failure ->
            if (pendingLogin !== login) return@whenCompleteAsync
            pendingLogin = null
            if (credential != null) {
                ConfigStore.saveCredential(credential)
                connect(credential)
            } else {
                snapshot = snapshot.copy(
                    connection = ConnectionState.SIGNED_OUT,
                    error = (failure?.cause ?: failure)?.message,
                )
            }
        }, executor)
        login
    }, executor)

    fun signOut() {
        executor.execute {
            val abandoned = pendingLogin
            pendingLogin = null
            abandoned?.cancel()
            disconnect()
            ConfigStore.saveCredential(null)
            snapshot = MonitorSnapshot()
        }
    }

    fun reconnect() {
        executor.execute { connectWithStoredCredential() }
    }

    fun pollNow() {
        executor.execute { poll() }
    }

    fun readThread(ref: ThreadRef): CompletableFuture<ThreadDetail> = withApi { it.readThread(ref) }

    fun sendMessage(ref: ThreadRef, message: String): CompletableFuture<Unit> =
        withApi { it.sendMessage(ref, message) }.thenApply { pollNow() }

    private fun <T> withApi(block: (T3Api) -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({
        val current = api ?: throw IllegalStateException("Not connected to T3 Code.")
        guardAuth { block(current) }
    }, executor)

    private fun connectWithStoredCredential() {
        val server = ConfigStore.config.normalizedServerUrl()
        val credential = ConfigStore.credential?.takeIf { it.isUsableFor(server) }
        if (credential == null) {
            disconnect()
            snapshot = MonitorSnapshot(
                connection = if (ConfigStore.credential != null) ConnectionState.SIGN_IN_EXPIRED else ConnectionState.SIGNED_OUT,
            )
            return
        }
        connect(credential)
    }

    private fun connect(credential: StoredCredential) {
        disconnect()
        val client = McpClient(credential.serverUrl, credential.accessToken, modVersion)
        mcp = client
        api = T3Api(client)
        snapshot = MonitorSnapshot(connection = ConnectionState.CONNECTING)
        val period = ConfigStore.config.pollSeconds.toLong()
        pollTask = executor.scheduleWithFixedDelay(::poll, 0, period, TimeUnit.SECONDS)
    }

    private fun disconnect() {
        pollTask?.cancel(false)
        pollTask = null
        mcp?.close()
        mcp = null
        api = null
        projects = emptyMap()
        runStarts = emptyMap()
        projectsFetchedAt = Instant.EPOCH
        hasBaseline = false
    }

    private fun poll() {
        val current = api ?: return
        try {
            guardAuth {
                if (Duration.between(projectsFetchedAt, Instant.now()) > projectRefreshInterval) {
                    projects = discoverProjects(current)
                    projectsFetchedAt = Instant.now()
                }
                val threads = projects.flatMap { (environment, environmentProjects) ->
                    if (environment.isRemote) skipIfUnreachable(environment) { environmentProjects.flatMap(current::listOpenThreads) }
                    else environmentProjects.flatMap(current::listOpenThreads)
                }
                    .sortedWith(compareBy<ThreadSummary> { it.attention.ordinal }.thenByDescending { it.updatedAt })
                publish(withRunStarts(current, threads))
            }
        } catch (unauthorized: T3UnauthorizedException) {
            return
        } catch (failure: Exception) {
            log.debug("T3 Code poll failed", failure)
            snapshot = snapshot.copy(connection = ConnectionState.UNREACHABLE, error = failure.message)
        }
    }

    private fun withRunStarts(api: T3Api, threads: List<ThreadSummary>): List<ThreadSummary> {
        val known = runStarts
        val updated = mutableMapOf<Pair<ThreadRef, String>, Instant>()
        val enriched = threads.map { thread ->
            val runId = thread.latestRunId
            if (thread.attention != ThreadAttention.RUNNING || runId == null) return@map thread
            val key = thread.ref to runId
            val startedAt = known[key] ?: runCatching { api.latestRunTiming(thread.ref)?.startedAt }
                .onFailure { if (it is T3UnauthorizedException) throw it }
                .getOrNull()
            startedAt?.let { updated[key] = it }
            thread.copy(runStartedAt = startedAt)
        }
        runStarts = updated
        return enriched
    }

    private fun discoverProjects(api: T3Api): Map<Environment, List<Project>> {
        val local = api.localEnvironment()
        val remotes = api.reachableLinkedEnvironments()
        return buildMap {
            put(local, api.listProjects(local))
            for (remote in remotes) put(remote, skipIfUnreachable(remote) { api.listProjects(remote) })
        }
    }

    private fun <T> skipIfUnreachable(environment: Environment, block: () -> List<T>): List<T> =
        try {
            block()
        } catch (unauthorized: T3UnauthorizedException) {
            throw unauthorized
        } catch (failure: Exception) {
            log.debug("Skipping linked environment {} this poll", environment.label, failure)
            emptyList()
        }

    private fun publish(threads: List<ThreadSummary>) {
        val previous = snapshot.threads.associateBy { it.ref }
        val transitions = threads.mapNotNull { thread ->
            val before = previous[thread.ref]?.attention
            val becameNoteworthy = thread.attention == ThreadAttention.NEEDS_INPUT ||
                (thread.attention == ThreadAttention.READY && before == ThreadAttention.RUNNING)
            if (becameNoteworthy && before != thread.attention) ThreadTransition(thread, before) else null
        }
        snapshot = MonitorSnapshot(connection = ConnectionState.CONNECTED, threads = threads)
        if (hasBaseline && transitions.isNotEmpty()) onTransitions(transitions)
        hasBaseline = true
    }

    private fun <T> guardAuth(block: () -> T): T =
        try {
            block()
        } catch (unauthorized: T3UnauthorizedException) {
            disconnect()
            ConfigStore.saveCredential(null)
            snapshot = MonitorSnapshot(connection = ConnectionState.SIGN_IN_EXPIRED, error = unauthorized.message)
            throw unauthorized
        }
}
