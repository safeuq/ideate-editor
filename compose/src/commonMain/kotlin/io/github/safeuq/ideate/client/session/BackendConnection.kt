package io.github.safeuq.ideate.client.session

import com.intellij.platform.split.protocol.DefaultSplitMarshallersProvider
import com.jetbrains.rd.framework.IMarshaller
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.IdKind
import com.jetbrains.rd.framework.Identities
import com.jetbrains.rd.framework.MarshallersProvider
import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.RdId
import com.jetbrains.rd.framework.Serializers
import com.jetbrains.rd.ide.model.BasicIdeRootMarshallersProvider
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rd.util.threading.SingleThreadScheduler
import io.github.safeuq.ideate.client.connection.ClientCertificate
import io.github.safeuq.ideate.client.connection.Connection
import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.connection.SplitWire
import kotlin.concurrent.thread

/**
 * The connection to a backend and its rd protocol. The backend lists every open project on it
 * (`solutionModel.solutions`), so it is to be shared by the sessions of all open projects
 * (docs/plans/shared-connection.md); for now each [ClientSession] makes its own.
 */
internal class BackendConnection(
  private val link: JoinLink,
  /** A stored identity to use [link]'s join token again; null makes a new one. */
  private val identity: ClientCertificate?,
  /** False while the app is in the background; the wire checks nothing then (SplitWire). */
  private val inForeground: () -> Boolean,
  private val log: (String) -> Unit,
) {
  private val lifetimeDefinition = LifetimeDefinition()

  /** Ends with the connection; everything bound to the protocol hangs off it. */
  val lifetime: Lifetime = lifetimeDefinition

  /** The rd thread's scheduler; null until [open] has connected. */
  @Volatile
  var scheduler: IScheduler? = null
    private set

  /**
   * Connects and blocks until the backend has accepted: then [onAccepted] with the identity it
   * accepted, on the calling thread, and [onReady] with the protocol on the rd thread. [onLost]
   * when the wire fails or closes later.
   */
  fun open(
    onAccepted: (ClientCertificate) -> Unit,
    onLost: (Throwable) -> Unit,
    onReady: (IProtocol) -> Unit,
  ) {
    val certificate =
      identity ?: ClientCertificate.create(link, System.getProperty("user.name") ?: "android")
    val connection = Connection.open(link, certificate, log)
    onAccepted(certificate)
    val wire =
      SplitWire(connection, inForeground) { failure ->
        onLost(failure ?: IllegalStateException("Connection closed"))
      }
    lifetime.onTermination { wire.terminate() }
    val rdScheduler = SingleThreadScheduler(lifetime, "rd")
    val protocol =
      Protocol(
        "ThinClient",
        Serializers(ProtocolMarshallers),
        Identities(IdKind.Client),
        rdScheduler,
        wire,
        lifetime,
      )
    wire.start()
    scheduler = rdScheduler
    rdScheduler.queue { onReady(protocol) }
  }

  /**
   * Ends the connection. Callers are on the UI thread (Back, a reconnect replacing a session), but
   * ending the lifetime makes rd send each model's last state through the wire
   * (`RdExtBase.sendState` → `SplitWire.send`), and Android refuses network use on the main thread
   * (`NetworkOnMainThreadException`, seen on Back). So it ends on a thread of its own.
   */
  fun close() {
    thread(name = "client-session-stop", isDaemon = true) { lifetimeDefinition.terminate() }
  }
}

internal object ProtocolMarshallers : MarshallersProvider {
  override fun getMarshaller(id: RdId): IMarshaller<*>? =
    BasicIdeRootMarshallersProvider.getMarshaller(id)
      ?: DefaultSplitMarshallersProvider.getMarshaller(id)
}
