package intellijandroid.client.kernel

import com.jetbrains.rd.ide.model.FleetRpcModel
import com.jetbrains.rd.ide.model.FleetTransportMessage
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rhizomedb.DbContext
import com.jetbrains.rhizomedb.EID
import com.jetbrains.rhizomedb.Entity
import com.jetbrains.rhizomedb.all
import fleet.kernel.DurableEntityType
import fleet.kernel.FrontendPart
import fleet.kernel.change
import fleet.kernel.db
import fleet.kernel.rebase.DefaultInstructionSet
import fleet.kernel.rebase.FollowerTransactorMiddleware
import fleet.kernel.rebase.RemoteKernel
import fleet.kernel.rebase.clientClock
import fleet.kernel.rebase.shared
import fleet.kernel.rebase.withRebaseLoop
import fleet.kernel.ref
import fleet.kernel.rete.withRete
import fleet.kernel.withTransactor
import fleet.rpc.ParameterDescriptor
import fleet.rpc.RemoteApi
import fleet.rpc.RemoteApiDescriptor
import fleet.rpc.RemoteKind
import fleet.rpc.RpcSignature
import fleet.rpc.client.RpcInterceptor
import fleet.rpc.client.proxy.serviceProxy
import fleet.rpc.client.rpcClient
import fleet.rpc.core.InstanceId
import fleet.rpc.core.RpcMessage
import fleet.rpc.core.Transport
import fleet.rpc.core.TransportMessage
import fleet.util.CompressedVectorClock
import fleet.util.UID
import fleet.util.WithoutCausalityContextElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Spike, part 1 (docs/feature-parity-plan.md, section 5): JetBrains' own Fleet RPC client over
 * IDEate's rd connection, next to IDEate's hand-written [intellijandroid.client.session.FleetRpc].
 * Then keeps a local Rhizome database in step with the backend's and logs what is in it. Runs only
 * with `CLIENT_KERNEL_SPIKE=1`.
 */
object KernelSpike {
  /** The route JetBrains Client sends backend calls to; IDEate's FleetRpc uses the same. */
  private val BACKEND_ROUTE = UID.fromString("fleet")

  fun start(
    model: FleetRpcModel,
    lifetime: Lifetime,
    scheduler: IScheduler,
    kernelProjectId: String,
    dataContext: String,
  ) {
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
      rpcClient(
          Transport(outgoing, incoming),
          origin,
          CausalityInterceptor,
          abortOnError = false,
        )
        .use { client ->
          val descriptor = RemoteKernel._generated_RemoteApiDescriptor
          val kernel =
            serviceProxy(client)
              .proxy(descriptor, BACKEND_ROUTE, InstanceId(descriptor.getApiFqn()))
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
                // Part 3b: the Files tab's search with that session.
                println("[kernel] data context $dataContext")
                val search =
                  serviceProxy(client)
                    .proxy(SearchApiDescriptor, BACKEND_ROUTE, InstanceId(SEARCH_API))
                launch {
                  val requestedCount = Channel<Int>(Channel.UNLIMITED)
                  requestedCount.send(50)
                  search
                    .getItems(
                      projectId = buildJsonObject { put("id", kernelProjectId) },
                      session =
                        buildJsonObject {
                          put("type", "com.intellij.platform.searchEverywhere.SeSessionImpl")
                          putJsonObject("ref") { put("uid", session.uid.toString()) }
                        },
                      providerIds =
                        buildJsonArray {
                          addJsonObject { put("value", "FileSearchEverywhereContributor") }
                        },
                      isAllTab = false,
                      params =
                        buildJsonObject {
                          put("inputQuery", "Main")
                          putJsonObject("filter") {
                            put(
                              "type",
                              "com.intellij.platform.searchEverywhere.SeFilterState.Empty",
                            )
                          }
                        },
                      dataContextId = buildJsonObject { put("serializedValue", dataContext) },
                      requestedCountChannel = requestedCount,
                    )
                    .collect { event -> println("[kernel] search: $event") }
                  println("[kernel] search done")
                }
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

private val KERNEL_API = RemoteKernel._generated_RemoteApiDescriptor.getApiFqn()

private const val SEARCH_API = "com.intellij.platform.searchEverywhere.impl.SeRemoteApi"

/**
 * IntelliJ's `SeRemoteApi.getItems` (platform/searchEverywhere/shared/src/impl/SeRemoteApi.kt) with
 * its arguments and items as JSON, so IDEate needs no IntelliJ platform jars. JetBrains' RPC plugin
 * generates this class for IntelliJ; here it is written by hand.
 */
class SearchApi(private val proxy: suspend (String, Array<Any?>) -> Any?) : RemoteApi<Unit> {
  @Suppress("UNCHECKED_CAST")
  suspend fun getItems(
    projectId: JsonElement,
    session: JsonElement,
    providerIds: JsonElement,
    isAllTab: Boolean,
    params: JsonElement,
    dataContextId: JsonElement?,
    requestedCountChannel: ReceiveChannel<Int>,
  ): Flow<JsonElement> =
    proxy(
      "getItems",
      arrayOf(
        projectId,
        session,
        providerIds,
        isAllTab,
        params,
        dataContextId,
        requestedCountChannel,
      ),
    )
      as Flow<JsonElement>
}

private object SearchApiDescriptor : RemoteApiDescriptor<SearchApi> {
  private val json = RemoteKind.Data(JsonElement.serializer())

  override fun getSignature(methodName: String): RpcSignature {
    require(methodName == "getItems") { "SearchApi has no $methodName" }
    return RpcSignature(
      methodName,
      arrayOf(
        ParameterDescriptor("projectId", json),
        ParameterDescriptor("session", json),
        ParameterDescriptor("providerIds", json),
        ParameterDescriptor("isAllTab", RemoteKind.Data(Boolean.serializer())),
        ParameterDescriptor("params", json),
        ParameterDescriptor("dataContextId", RemoteKind.Data(JsonElement.serializer().nullable)),
        ParameterDescriptor(
          "requestedCountChannel",
          RemoteKind.ReceiveChannel(RemoteKind.Data(Int.serializer()), nullable = false),
        ),
      ),
      RemoteKind.Flow(json, nullable = false),
    )
  }

  override fun clientStub(proxy: suspend (String, Array<Any?>) -> Any?) = SearchApi(proxy)

  override fun getApiFqn() = SEARCH_API

  override suspend fun call(impl: SearchApi, methodName: String, args: Array<Any?>): Any? =
    error("SearchApi is a client only")
}

/**
 * JetBrains Client's `ClientCausalityRequestInterceptor` (remdev-plugin,
 * intellij.platform.kernel.base.frontend.split.jar), request half: a call made inside the database
 * carries the client's vector clock under `CompressedVectorClock`, so the backend waits until it
 * has seen the client's changes (the session) before it runs the call. ponytail: the answer's clock
 * is not awaited; add it when a call reads entities the backend wrote.
 */
private object CausalityInterceptor : RpcInterceptor {
  override suspend fun interceptCallRequest(
    request: RpcMessage.CallRequest,
  ): RpcMessage.CallRequest {
    // The rebase loop's own `RemoteKernel.transact` carries the very changes the clock counts: the
    // backend would wait for itself ("Timed out waiting for clock … sentTxs=1").
    if (!DbContext.isBound() || request.service.id == KERNEL_API) return request
    if (currentCoroutineContext()[WithoutCausalityContextElement] != null) return request
    val clock = db().clientClock?.compressed() ?: return request
    return request.copy(
      meta =
        request.meta +
          ("CompressedVectorClock" to
            Json.encodeToJsonElement(CompressedVectorClock.serializer(), clock)),
    )
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
