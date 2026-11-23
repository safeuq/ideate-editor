package io.github.safeuq.ideate.client.session.terminal

/**
 * The text of a terminal tab (IntelliJ's `TerminalTitle.buildTitle`): the name the user gave it,
 * else the title the running program set (`vim build.gradle.kts`), else the default the backend
 * sent, else "Local". A program's title is cut in the middle to [MAX_APPLICATION_TITLE] characters.
 *
 * | userDefined | application            | default  | tab                    |
 * |-------------|------------------------|----------|------------------------|
 * | null        | null                   | null     | `Local`                |
 * | null        | null                   | `Remote` | `Remote`               |
 * | null        | `vim build.gradle.kts` | `Remote` | `vim build.gradle.kts` |
 * | `Server`    | `vim …`                | `Remote` | `Server`               |
 */
internal fun terminalTabTitle(userDefined: String?, application: String?, default: String?) =
  userDefined ?: application?.let(::trimMiddle) ?: default ?: "Local"

private const val MAX_APPLICATION_TITLE = 30

/**
 * Like `StringUtil.trimMiddle`: the start, `…`, the end, [MAX_APPLICATION_TITLE] characters in all.
 * ponytail: the exact split (14 + … + 15) is a guess; only the length is from the spec.
 */
private fun trimMiddle(title: String): String {
  if (title.length <= MAX_APPLICATION_TITLE) return title
  val keep = MAX_APPLICATION_TITLE - 1
  return title.take(keep / 2) + "…" + title.takeLast(keep - keep / 2)
}
