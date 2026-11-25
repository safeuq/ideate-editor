package io.github.safeuq.ideate.client.connect

/**
 * A backend found on this device (docs/connections.md, "Discovery"): from Termux's
 * `intellij-backend status --json` only. LAN machines are added once by link and then reached from
 * history.
 */
data class DiscoveredBackend(
  /** For example `This device · smoke`. */
  val name: String,
  val port: Int,
  /** Project path from `status --json`, e.g. `/data/data/com.termux/files/home/intellij/smoke`. */
  val project: String?,
  /** The join link from `status --json`. */
  val joinLink: String,
) {
  val host: String
    get() = "127.0.0.1"
}
