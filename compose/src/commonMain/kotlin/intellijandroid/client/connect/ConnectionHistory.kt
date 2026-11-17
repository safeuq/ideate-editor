package intellijandroid.client.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * How a saved connection gets a fresh join link (docs/connections.md, "History entry"). SSH comes
 * later.
 */
enum class ConnectionKind {
  LINK,
  TERMUX,
}

/**
 * A backend the app connected to. [identity] is the
 * [intellijandroid.client.connection.ClientCertificate] the backend accepted, encoded: with it the
 * same [lastLink] works again after an app restart, until the backend restarts.
 */
data class SavedConnection(
  val kind: ConnectionKind,
  /** For example `192.168.0.105:5990`, or `This phone · smoke` for Termux. */
  val name: String,
  val host: String,
  val port: Int,
  /**
   * The project folder on the backend; only Termux connections know it, e.g.
   * `/data/data/com.termux/files/home/intellij/smoke`.
   */
  val project: String?,
  val fingerprint: String,
  val lastLink: String,
  val identity: String,
  val openFile: String?,
  /** Milliseconds since 1970. */
  val lastConnected: Long,
) {
  /**
   * One entry per backend address, or per project for Termux (its port can change between starts).
   */
  val key: String
    get() = if (kind == ConnectionKind.TERMUX) "termux:$project" else "link:$host:$port"
}

/**
 * Saved connections, newest first, as a JSON array in [storage]. They hold client private keys,
 * which is fine in the app's private storage: a key only works with its backend's join token, and
 * only until that backend restarts. The format is the one the Android app wrote with org.json, so
 * existing history still reads.
 */
class ConnectionHistory(private val storage: StoredText) {
  fun entries(): List<SavedConnection> =
    Json.parseToJsonElement(storage.read() ?: "[]").jsonArray.map { element ->
      element.jsonObject.toSavedConnection()
    }

  fun save(entry: SavedConnection) = write(listOf(entry) + entries().filter { it.key != entry.key })

  fun remove(entry: SavedConnection) = write(entries().filter { it.key != entry.key })

  private fun write(entries: List<SavedConnection>) =
    storage.write(JsonArray(entries.map { it.toJson() }).toString())

  private companion object {
    fun SavedConnection.toJson() = buildJsonObject {
      put("kind", kind.name)
      put("name", name)
      put("host", host)
      put("port", port)
      // org.json left out null values; reading treats a missing key and null alike.
      project?.let { put("project", it) }
      put("fingerprint", fingerprint)
      put("lastLink", lastLink)
      put("identity", identity)
      openFile?.let { put("openFile", it) }
      put("lastConnected", lastConnected)
    }

    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    fun JsonObject.optionalText(key: String) = get(key)?.jsonPrimitive?.contentOrNull

    fun JsonObject.toSavedConnection() =
      SavedConnection(
        kind = ConnectionKind.valueOf(text("kind")),
        name = text("name"),
        host = text("host"),
        port = getValue("port").jsonPrimitive.int,
        project = optionalText("project"),
        fingerprint = text("fingerprint"),
        lastLink = text("lastLink"),
        identity = text("identity"),
        openFile = optionalText("openFile"),
        lastConnected = getValue("lastConnected").jsonPrimitive.long,
      )
  }
}
