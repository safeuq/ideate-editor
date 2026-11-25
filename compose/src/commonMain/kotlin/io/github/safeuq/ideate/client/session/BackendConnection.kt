package io.github.safeuq.ideate.client.session

import com.intellij.platform.split.protocol.DefaultSplitMarshallersProvider
import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeModel
import com.jetbrains.ide.model.highlighterRegistration.DefaultRegistryModel
import com.jetbrains.ide.model.highlighterRegistration.HighlightSeverityModel
import com.jetbrains.ide.model.highlighterRegistration.highlighterRegistrationHostModel
import com.jetbrains.rd.framework.IMarshaller
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.IdKind
import com.jetbrains.rd.framework.Identities
import com.jetbrains.rd.framework.MarshallersProvider
import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.RdId
import com.jetbrains.rd.framework.Serializers
import com.jetbrains.rd.ide.model.BasicIdeRootMarshallersProvider
import com.jetbrains.rd.ide.model.RdRetryTimestamp
import com.jetbrains.rd.ide.model.fleetRpcModel
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rd.util.threading.SingleThreadScheduler
import io.github.safeuq.ideate.client.connection.ClientCertificate
import io.github.safeuq.ideate.client.connection.Connection
import io.github.safeuq.ideate.client.connection.JoinLink
import io.github.safeuq.ideate.client.connection.SplitWire
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The connection to a backend and its rd protocol. The backend lists every open project on it
 * (`solutionModel.solutions`), so it is to be shared by the sessions of all open projects
 * (docs/plans/shared-connection.md); for now each [ClientSession] makes its own.
 */
class BackendConnection(
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

  @Volatile private var protocol: IProtocol? = null
  private val lostListeners = java.util.concurrent.CopyOnWriteArrayList<(Throwable) -> Unit>()

  // The rest is touched only on the rd thread, and is the same for every session on the
  // connection.

  /** The last id of the request sequence all sessions share (`RdRequestHolder.requestId`). */
  var requestIds = 0L

  /** The backend's retry timestamp for that sequence. */
  var retryTimestamp = RdRetryTimestamp(0, 0)

  /** The ids of the client's editing commands: one chain for all editors of all projects. */
  val commands = CommandChain()
  private var factoryIds = 0

  /**
   * An id for one project's main toolbar (`AsyncActionUpdateRequest.factoryId`): the backend keeps
   * a toolbar's components by it, so with one id for two projects the first project's widget button
   * became the second's (seen 2026-11-24).
   */
  fun nextFactoryId(): Int = ++factoryIds

  /** Every installed editor scheme the backend has sent, by name. */
  val schemes = mutableMapOf<String, ColorsSchemeModel>()

  /** The severities the backend sent last (it sends the whole list once). */
  var severities: List<HighlightSeverityModel> = emptyList()
    private set

  private var registry: DefaultRegistryModel? = null

  /**
   * The highlighter registry, set on the protocol by the first session that asks: a second `set`
   * would take the schemes away from the first.
   */
  fun registry(protocol: IProtocol): DefaultRegistryModel =
    registry
      ?: DefaultRegistryModel().also { created ->
        registry = created
        protocol.highlighterRegistrationHostModel.registry.set(created)
        created.colorsScheme.advise(lifetime) { scheme -> schemes[scheme.name] = scheme }
        created.severities.advise(lifetime) { severities = it }
      }

  private var fleet: FleetRpc? = null

  /** The Fleet RPC channel, one for the connection. */
  fun fleet(protocol: IProtocol): FleetRpc =
    fleet
      ?: FleetRpc(
          protocol.fleetRpcModel,
          lifetime,
          requireNotNull(scheduler) { "The connection has no rd scheduler yet" },
          { if (requestIds == 0L) -1 else requestIds },
          log,
        )
        .also { fleet = it }

  private val completionHandlers = mutableListOf<(JsonObject) -> Unit>()
  private var completionSubscribed = false

  /**
   * The connection's one `commandFlow` of the completion service. The backend keeps one per client
   * and closes the earlier one when a second is opened, so every project's completion sends here.
   */
  val completionCommands: FleetRpc.OutgoingStream by lazy {
    requireNotNull(fleet) { "The Fleet RPC channel is made before completion" }
      .openStream(COMPLETION_SERVICE, "commandFlow", "commands")
  }

  /**
   * Completion's `responseFlow`, which the backend keeps once per client: subscribed by the first
   * project's completion, and every element goes to each project's [handler] until
   * [sessionLifetime] ends (a handler ignores what is not its own).
   */
  fun completionResponses(sessionLifetime: Lifetime, handler: (JsonObject) -> Unit) {
    completionHandlers += handler
    sessionLifetime.onTermination { completionHandlers -= handler }
    if (completionSubscribed) return
    completionSubscribed = true
    requireNotNull(fleet) { "The Fleet RPC channel is made before completion" }
      .subscribe(COMPLETION_SERVICE, "responseFlow", JsonObject(emptyMap())) { element ->
        completionHandlers.toList().forEach { it(element.jsonObject) }
      }
  }

  /**
   * For a session that joins a connection another session opened: [onReady] with the protocol on
   * the rd thread, and [onLost] when the wire fails or closes, until [sessionLifetime] ends.
   */
  fun attach(
    sessionLifetime: Lifetime,
    onLost: (Throwable) -> Unit,
    onReady: (IProtocol) -> Unit,
  ) {
    val ready = requireNotNull(protocol) { "The connection is not open yet; nothing to attach to" }
    val rdScheduler = requireNotNull(scheduler) { "The connection has no rd scheduler yet" }
    lostListeners += onLost
    sessionLifetime.onTermination { lostListeners -= onLost }
    rdScheduler.queue { onReady(ready) }
  }

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
        val cause = failure ?: IllegalStateException("Connection closed")
        onLost(cause)
        lostListeners.forEach { it(cause) }
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
    this.protocol = protocol
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
