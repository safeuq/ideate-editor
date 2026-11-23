package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isNotAlive
import com.jetbrains.rd.util.reactive.IViewableMap

/**
 * Calls [start] once, with the entry [key], as soon as the map has it, e.g. `"Application.Main"` in
 * the backend's run configurations (listed a few seconds after the project opens). The watch ends
 * after that call: the backend putting the same key again (an edited configuration, a refreshed
 * list) must not start the program a second time.
 */
internal fun <V : Any> startWhenListed(
  lifetime: Lifetime,
  map: IViewableMap<String, V>,
  key: String,
  start: (V) -> Unit,
) {
  val watch = lifetime.createNested()
  map.view(watch) { _, listedKey, value ->
    if (listedKey != key || watch.isNotAlive) return@view
    watch.terminate()
    start(value)
  }
}
