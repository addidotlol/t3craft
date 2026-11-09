package lol.addi.t3craft.client.t3

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

enum class ThreadAttention {
    NEEDS_INPUT,
    RUNNING,
    READY,
    QUIET,
}

data class Environment(val id: String?, val label: String) {
    val isRemote: Boolean get() = id != null
}

data class Project(val id: String, val title: String, val environment: Environment)

data class ThreadRef(val environmentId: String?, val threadId: String)

data class ThreadSummary(
    val ref: ThreadRef,
    val projectTitle: String,
    val environment: Environment,
    val title: String,
    val status: String,
    val updatedAt: Instant,
    val model: String = "",
    val runtimeMode: String = "",
    val latestRunId: String? = null,
    val runStartedAt: Instant? = null,
) {
    val attention: ThreadAttention
        get() = attentionFor(status)
}

fun attentionFor(status: String): ThreadAttention = when (status) {
    "waiting" -> ThreadAttention.NEEDS_INPUT
    "preparing", "queued", "starting", "running" -> ThreadAttention.RUNNING
    "completed", "failed", "interrupted", "cancelled" -> ThreadAttention.READY
    else -> ThreadAttention.QUIET
}

enum class MessageRole { USER, ASSISTANT }

data class ThreadMessage(val role: MessageRole, val text: String)

data class RunTiming(val startedAt: Instant?, val completedAt: Instant?)

data class ThreadDetail(
    val ref: ThreadRef,
    val title: String,
    val status: String,
    val pendingRequestCount: Int,
    val model: String,
    val runtimeMode: String,
    val branch: String,
    val latestRun: RunTiming?,
    val messages: List<ThreadMessage>,
)

class T3Api(private val mcp: McpClient) {

    fun localEnvironment(): Environment =
        Environment(null, mcp.callTool("t3_environment_read", JsonObject()).string("label").ifBlank { "This machine" })

    fun reachableLinkedEnvironments(): List<Environment> {
        val result = try {
            mcp.callTool("t3_environment_links", JsonObject())
        } catch (unavailable: T3ToolException) {
            return emptyList()
        } catch (unknownTool: T3ProtocolException) {
            return emptyList()
        }
        return result.getAsJsonArray("links").objects()
            .filter { it.string("status") == "reachable" }
            .map { Environment(it.string("environmentId"), it.string("label")) }
    }

    fun listProjects(environment: Environment): List<Project> = paged { cursor ->
        val page = mcp.callTool(
            "t3_project_list",
            args("limit" to 100, "cursor" to cursor, "environmentId" to environment.id),
        )
        page.getAsJsonArray("projects").objects()
            .map { Project(it.string("id"), it.string("title"), environment) } to page.nextCursor()
    }

    fun listOpenThreads(project: Project): List<ThreadSummary> = paged { cursor ->
        val page = mcp.callTool(
            "t3_thread_list",
            args(
                "projectId" to project.id,
                "environmentId" to project.environment.id,
                "includeSubagents" to false,
                "settled" to false,
                "snoozed" to false,
                "limit" to 100,
                "cursor" to cursor,
            ),
        )
        page.getAsJsonArray("threads").objects().map { it.toSummary(project) } to page.nextCursor()
    }

    fun readThread(ref: ThreadRef, wantedMessages: Int = 40): ThreadDetail {
        val head = readPage(ref, afterPosition = null, limit = 1)
        val itemCount = head.getAsJsonObject("thread").int("itemCount")
        var span = wantedMessages * 4
        var messages: List<ThreadMessage>
        var latest: JsonObject
        do {
            val start = (itemCount - span).coerceAtLeast(0)
            val (collected, last) = readMessagesFrom(ref, start)
            messages = collected
            latest = last
            span *= 2
        } while (messages.size < wantedMessages && start > 0)

        val thread = latest.getAsJsonObject("thread")
        return ThreadDetail(
            ref = ref,
            title = thread.string("title"),
            status = thread.string("status"),
            pendingRequestCount = thread.int("pendingRequestCount"),
            model = thread.string("model"),
            runtimeMode = thread.string("runtimeMode"),
            branch = thread.string("branch"),
            latestRun = latestRunTiming(head),
            messages = messages.takeLast(wantedMessages),
        )
    }

    fun latestRunTiming(ref: ThreadRef): RunTiming? = latestRunTiming(readPage(ref, afterPosition = null, limit = 1))

    private fun latestRunTiming(page: JsonObject): RunTiming? {
        val latestRunId = page.getAsJsonObject("thread").string("latestRunId")
        val runs = page.getAsJsonArray("recentRuns").objects()
        val run = runs.firstOrNull { it.string("runId") == latestRunId } ?: runs.firstOrNull() ?: return null
        return RunTiming(
            startedAt = run.instant("startedAt") ?: run.instant("requestedAt"),
            completedAt = run.instant("completedAt"),
        )
    }

    fun sendMessage(ref: ThreadRef, message: String) {
        mcp.callTool(
            "t3_thread_send",
            args("threadId" to ref.threadId, "environmentId" to ref.environmentId, "message" to message, "mode" to "queue"),
        )
    }

    private fun readMessagesFrom(ref: ThreadRef, start: Int): Pair<List<ThreadMessage>, JsonObject> {
        val messages = mutableListOf<ThreadMessage>()
        var afterPosition: Int? = start.takeIf { it > 0 }
        var page: JsonObject
        do {
            page = readPage(ref, afterPosition, limit = 100)
            page.getAsJsonArray("items").objects().mapNotNullTo(messages) { it.toMessage(ref) }
            afterPosition = page.get("nextPosition")?.takeUnless { it.isJsonNull }?.asInt
        } while (page.get("hasMore")?.asBoolean == true && afterPosition != null)
        return messages to page
    }

    private fun readPage(ref: ThreadRef, afterPosition: Int?, limit: Int): JsonObject =
        mcp.callTool(
            "t3_thread_read",
            args(
                "threadId" to ref.threadId,
                "environmentId" to ref.environmentId,
                "view" to "messages",
                "limit" to limit,
                "runLimit" to 1,
                "maxCharsPerItem" to MAX_CHARS_PER_ITEM,
                "afterPosition" to afterPosition,
            ),
        )

    private fun JsonObject.toMessage(ref: ThreadRef): ThreadMessage? {
        val role = when (string("type")) {
            "user_message" -> MessageRole.USER
            "assistant_message" -> MessageRole.ASSISTANT
            else -> return null
        }
        return ThreadMessage(role, fullText(ref, this))
    }

    private fun fullText(ref: ThreadRef, item: JsonObject): String {
        val text = StringBuilder(item.string("text"))
        var offset = item.nextTextOffset()
        while (offset != null) {
            val chunk = mcp.callTool(
                "t3_thread_read",
                args(
                    "threadId" to ref.threadId,
                    "environmentId" to ref.environmentId,
                    "itemId" to item.string("itemId"),
                    "textOffset" to offset,
                    "runLimit" to 1,
                ),
            ).getAsJsonArray("items").objects().firstOrNull { it.string("itemId") == item.string("itemId") } ?: break
            text.append(chunk.string("text"))
            offset = chunk.nextTextOffset()
        }
        return text.toString()
    }

    private fun JsonObject.toSummary(project: Project) = ThreadSummary(
        ref = ThreadRef(project.environment.id, string("threadId")),
        projectTitle = project.title,
        environment = project.environment,
        title = string("title"),
        status = string("status"),
        updatedAt = instant("updatedAt") ?: Instant.EPOCH,
        model = string("model"),
        runtimeMode = string("runtimeMode"),
        latestRunId = string("latestRunId").ifBlank { null },
    )

    private fun <T> paged(fetch: (cursor: Int) -> Pair<List<T>, Int?>): List<T> {
        val all = mutableListOf<T>()
        var cursor: Int? = 0
        while (cursor != null) {
            val (items, next) = fetch(cursor)
            all += items
            cursor = next
        }
        return all
    }

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        for ((key, value) in pairs) {
            when (value) {
                null -> Unit
                is String -> addProperty(key, value)
                is Number -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                else -> error("Unsupported argument type for $key")
            }
        }
    }

    private companion object {
        const val MAX_CHARS_PER_ITEM = 20_000
    }
}

private fun JsonArray?.objects(): List<JsonObject> = this?.map { it.asJsonObject }.orEmpty()

private fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.instant(key: String): Instant? =
    string(key).takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JsonObject.int(key: String): Int = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: 0

private fun JsonObject.nextCursor(): Int? = get("nextCursor")?.takeUnless { it.isJsonNull }?.asInt

private fun JsonObject.nextTextOffset(): Int? =
    if (get("textTruncated")?.asBoolean == true) get("nextTextOffset")?.takeUnless { it.isJsonNull }?.asInt else null
