package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.connect.StoredText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Rows the user removed from Recent Projects. Ideate never edits the IDE's `recentProjects.xml`, so
 * a removed row would come back on refresh; this list hides it until the project is used again
 * (`dateLastOpened` later than `hiddenAt`). Stored as
 * `[{"hostId":"local","projectPath":"/Users/me/RESTest","hiddenAt":1764051234567}]`.
 */
class HiddenRecentProjects(private val storage: StoredText) {
  private data class Hidden(val hostId: String, val projectPath: String, val hiddenAt: Long)

  @Synchronized
  fun hide(hostId: String, projectPath: String, hiddenAt: Long) {
    write(
      read().filterNot { it.hostId == hostId && it.projectPath == projectPath } +
        Hidden(hostId, projectPath, hiddenAt),
    )
  }

  /** [projects] without the hidden ones; an entry whose project was used since is dropped. */
  @Synchronized
  fun visible(hostId: String, projects: List<RecentProject>): List<RecentProject> {
    val all = read()
    val usedAgain = all.filter { hidden ->
      hidden.hostId == hostId &&
        projects.any { it.projectPath == hidden.projectPath && it.dateLastOpened > hidden.hiddenAt }
    }
    if (usedAgain.isNotEmpty()) write(all - usedAgain.toSet())
    val stillHidden =
      (all - usedAgain.toSet()).filter { it.hostId == hostId }.map { it.projectPath }.toSet()
    return projects.filterNot { it.projectPath in stillHidden }
  }

  private fun read(): List<Hidden> {
    val text = storage.read()
    if (text.isNullOrBlank()) return emptyList()
    return Json.parseToJsonElement(text).jsonArray.map { element ->
      val item = element.jsonObject
      Hidden(
        item.getValue("hostId").jsonPrimitive.content,
        item.getValue("projectPath").jsonPrimitive.content,
        item.getValue("hiddenAt").jsonPrimitive.long,
      )
    }
  }

  private fun write(entries: List<Hidden>) {
    storage.write(
      JsonArray(
          entries.map {
            buildJsonObject {
              put("hostId", it.hostId)
              put("projectPath", it.projectPath)
              put("hiddenAt", it.hiddenAt)
            }
          },
        )
        .toString(),
    )
  }
}
