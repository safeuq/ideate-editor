package intellijandroid.client.desktop

import com.jetbrains.rd.ide.model.FleetRpcModel
import com.jetbrains.rd.ide.model.FleetTransportMessage
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rhizomedb.EID
import com.jetbrains.rhizomedb.Entity
import com.jetbrains.rhizomedb.all
import fleet.kernel.DurableEntityType
import fleet.kernel.FrontendPart
import fleet.kernel.change
import fleet.kernel.rebase.DefaultInstructionSet
import fleet.kernel.rebase.FollowerTransactorMiddleware
import fleet.kernel.rebase.RemoteKernel
import fleet.kernel.rebase.shared
import fleet.kernel.rebase.withRebaseLoop
import fleet.kernel.ref
import fleet.kernel.rete.withRete
import fleet.kernel.withTransactor
import fleet.rpc.client.proxy.serviceProxy
import fleet.rpc.client.rpcClient
import fleet.rpc.core.InstanceId
import fleet.rpc.core.Transport
import fleet.rpc.core.TransportMessage
import fleet.util.UID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * Spike, part 1 (docs/feature-parity-plan.md, section 5): JetBrains' own Fleet RPC client over
 * IDEate's rd connection, next to IDEate's hand-written [intellijandroid.client.session.FleetRpc].
 * Then keeps a local Rhizome database in step with the backend's and logs what is in it. Runs only
 * with `CLIENT_KERNEL_SPIKE=1`.
 */
object KernelSpike {
  /** The route JetBrains Client sends backend calls to; IDEate's FleetRpc uses the same. */
  private val BACKEND_ROUTE = UID.fromString("fleet")

  fun start(model: FleetRpcModel, lifetime: Lifetime, scheduler: IScheduler) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lifetime.onTermination { scope.cancel() }
    val origin = UID.random()
    val incoming = Channel<TransportMessage>(Channel.UNLIMITED)
    val outgoing = Channel<TransportMessage>(Channel.UNLIMITED)
    // Both clients read the same signal: take only what is addressed to this one, and the
    // broadcasts about routes.
    model.incoming.advise(lifetime) { message ->
      val parsed = runCatching { TransportMessage.deserialize(message.message) }.getOrNull()
      if (parsed !is TransportMessage.Envelope || parsed.destination == origin)
        parsed?.let(incoming::trySend)
    }
    scope.launch {
      for (message in outgoing) scheduler.queue {
        model.outgoing.fire(FleetTransportMessage(message.serialize()))
      }
    }
    scope.launch {
      println("[kernel] JetBrains RPC client as $origin")
      rpcClient(Transport(outgoing, incoming), origin, abortOnError = false).use { client ->
        val descriptor = RemoteKernel._generated_RemoteApiDescriptor
        val kernel =
          serviceProxy(client).proxy(descriptor, BACKEND_ROUTE, InstanceId(descriptor.getApiFqn()))
        // Part 2: the local database, kept in step with the backend's by JetBrains' rebase loop,
        // as JetBrains Client's FrontendKernelService does.
        // IntelliJ's startClientKernel (platform-impl/bootstrap/kernel/kernel.kt): a follower of
        // the backend's database, local entities in the frontend partition.
        withTransactor(
          middleware = FollowerTransactorMiddleware(DefaultInstructionSet.encoder()),
          registerEntityTypeOnEntityCreation = false,
          defaultPart = FrontendPart,
        ) { transactor ->
          withRete {
            withRebaseLoop(
              CompletableDeferred(kernel),
              DefaultInstructionSet,
              reconnectWhenBroken = false,
            ) {
              println("[kernel] rebase loop ready")
              // Part 3a: a Search Everywhere session in the shared database, as
              // SeSessionEntity.createSession does on JetBrains Client.
              change { register(SearchSessionEntity) }
              val session = change { shared { SearchSessionEntity.new {} } }.ref()
              println("[kernel] created session ${session.uid}")
              transactor.dbSource.flow.take(20).collect { _ ->
                val types =
                  Entity.Type.all().groupingBy { (_, type) -> type.toString() }.eachCount()
                println(
                  "[kernel] database: ${types.values.sum()} entities, types ${types.keys.sorted()}",
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * IntelliJ's `SeSessionEntity` (platform/searchEverywhere/shared): an entity type is known by its
 * name alone, so this one is the same type on both sides without IntelliJ's platform jars.
 */
class SearchSessionEntity(override val eid: EID) : Entity {
  companion object :
    DurableEntityType<SearchSessionEntity>(
      "com.intellij.platform.searchEverywhere.SeSessionEntity",
      "<unknown>",
      ::SearchSessionEntity,
    )
}
