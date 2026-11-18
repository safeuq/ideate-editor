package intellijandroid.client.kernel

import fleet.util.logging.BaseLogger
import fleet.util.logging.KLogger
import fleet.util.logging.KLoggerFactory
import kotlin.reflect.KClass

/**
 * Logging for JetBrains' Fleet libraries, which find their logger factory through Java's service
 * loader (`KLoggers.jvm.kt`) and fail without one. Prints info and above as `[fleet] <name>: …`.
 */
class FleetLoggerFactory : KLoggerFactory {
  private var context: Map<String, String>? = null

  override fun logger(owner: KClass<*>) = logger(owner.qualifiedName ?: owner.toString())

  override fun logger(owner: Any) = logger(owner::class)

  override fun logger(name: String) = KLogger(PrintLogger(name.substringAfterLast('.')))

  override fun setLoggingContext(map: Map<String, String>?) {
    context = map
  }

  override fun getLoggingContext() = context
}

private class PrintLogger(private val name: String) : BaseLogger {
  override val isTraceEnabled = false
  override val isDebugEnabled = false
  override val isInfoEnabled = true
  override val isWarnEnabled = true
  override val isErrorEnabled = true

  private fun print(level: String, message: Any?, error: Throwable? = null) {
    println("[fleet] $level $name: $message")
    error?.printStackTrace(System.out)
  }

  override fun trace(message: Any?) = Unit

  override fun debug(message: Any?) = Unit

  override fun info(message: Any?) = print("INFO", message)

  override fun warn(message: Any?) = print("WARN", message)

  override fun error(message: Any?) = print("ERROR", message)

  override fun trace(t: Throwable?, message: Any?) = Unit

  override fun debug(t: Throwable?, message: Any?) = Unit

  override fun info(t: Throwable?, message: Any?) = print("INFO", message, t)

  override fun warn(t: Throwable?, message: Any?) = print("WARN", message, t)

  override fun error(t: Throwable?, message: Any?) = print("ERROR", message, t)
}
