package intellijandroid.client.kernel

import com.jetbrains.rd.ide.model.FleetRpcModel
import com.jetbrains.rd.ide.model.FleetTransportMessage
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rhizomedb.DbContext
import com.jetbrains.rhizomedb.EID
import com.jetbrains.rhizomedb.Entity
import fleet.kernel.DurableEntityType
import fleet.kernel.DurableRef
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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Search Everywhere's backend (docs/feature-parity-plan.md, section 5): JetBrains' own Fleet RPC
 * client over IDEate's rd connection, next to IDEate's hand-written
 * [intellijandroid.client.session.FleetRpc], and a local Rhizome database kept in step with the
 * backend's, as JetBrains Client's `FrontendKernelService` does. A search needs a session entity in
 * that shared database; the backend looks it up for every item.
 *
 * Callbacks come on a background thread.
 */
class SearchKernel
private constructor(
  private val kernelProjectId: String,
  private val dataContext: String,
  private val log: (String) -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The database's coroutine context (without its job) and the search service, once connected. */
  private val ready = CompletableDeferred<Pair<CoroutineContext, SearchApi>>()

  /** The popup's session, created with its first search and deleted when the popup closes. */
  @Volatile private var session: DurableRef<SearchSessionEntity>? = null

  /**
   * Streams the items of [providerId] for [query], as `SeItemData` JSON. Cancel the job to stop it,
   * e.g. when the query changes. [onEnd] comes when every provider has finished.
   */
  fun search(
    providerId: String,
    query: String,
    onItem: (JsonObject) -> Unit,
    onEnd: () -> Unit,
  ): Job = inKernel { search ->
    // SeFrontendItemDataProvidersFacade: 50 items, then 50 more each time those arrived.
    val requestedCount = Channel<Int>(Channel.UNLIMITED)
    requestedCount.send(CHUNK)
    var pending = CHUNK
    search
      .getItems(
        projectId(),
        sessionJson(),
        buildJsonArray { addJsonObject { put("value", providerId) } },
        isAllTab = false,
        params =
          buildJsonObject {
            put("inputQuery", query)
            putJsonObject("filter") { put("type", "$SEARCH_PACKAGE.SeFilterState.Empty") }
          },
        dataContextId = buildJsonObject { put("serializedValue", dataContext) },
        requestedCountChannel = requestedCount,
      )
      .collect { event ->
        if (--pending == 0) {
          pending = CHUNK
          requestedCount.send(CHUNK)
        }
        if (event.jsonObject["type"]?.jsonPrimitive?.content == "$SEARCH_PACKAGE.SeTransferItem")
          onItem(event.jsonObject.getValue("itemData").jsonObject)
      }
    onEnd()
  }

  /**
   * Opens [item] (`SeItemData` JSON from [search]) as a click on its row does; the backend opens
   * the file or runs the action. [onDone] gets whether the popup should close.
   */
  fun choose(item: JsonObject, query: String, onDone: (Boolean) -> Unit): Job = inKernel { search ->
    onDone(search.itemSelected(projectId(), sessionJson(), item, 0, query, isAllTab = false))
  }

  /** Deletes the popup's session, as `SeFrontendService` does when the popup closes. */
  fun endSession() {
    val ended = session ?: return
    session = null
    inKernel { _ ->
      withContext(NonCancellable) { change { shared { ended.derefOrNull()?.delete() } } }
    }
  }

  private fun inKernel(block: suspend (SearchApi) -> Unit): Job = scope.launch {
    val (context, search) = ready.await()
    try {
      withContext(context) { block(search) }
    } catch (failure: Exception) {
      if (failure is kotlinx.coroutines.CancellationException) throw failure
      log("Search Everywhere: $failure")
    }
  }

  private fun projectId() = buildJsonObject { put("id", kernelProjectId) }

  /** `SeSessionImpl`, e.g. `{"type": "…SeSessionImpl", "ref": {"uid": "lu5lflrsb7pt7o8n5kgf"}}`. */
  private suspend fun sessionJson(): JsonElement {
    val current =
      session ?: change { shared { SearchSessionEntity.new {} } }.ref().also { session = it }
    return buildJsonObject {
      put("type", "$SEARCH_PACKAGE.SeSessionImpl")
      putJsonObject("ref") { put("uid", current.uid.toString()) }
    }
  }

  private fun connect(model: FleetRpcModel, lifetime: Lifetime, scheduler: IScheduler) {
    lifetime.onTermination { scope.cancel() }
    val origin = UID.random()
    val incoming = Channel<TransportMessage>(Channel.UNLIMITED)
    val outgoing = Channel<TransportMessage>(Channel.UNLIMITED)
    // IDEate's FleetRpc reads the same signal: take only what is addressed to this client, and the
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
      rpcClient(
          Transport(outgoing, incoming),
          origin,
          CausalityInterceptor,
          abortOnError = false,
        )
        .use { client ->
          val proxy = serviceProxy(client)
          val kernelApi = RemoteKernel._generated_RemoteApiDescriptor
          val kernel = proxy.proxy(kernelApi, BACKEND_ROUTE, InstanceId(kernelApi.getApiFqn()))
          // IntelliJ's startClientKernel (platform-impl/bootstrap/kernel/kernel.kt): a follower of
          // the backend's database, local entities in the frontend partition.
          withTransactor(
            middleware = FollowerTransactorMiddleware(DefaultInstructionSet.encoder()),
            registerEntityTypeOnEntityCreation = false,
            defaultPart = FrontendPart,
          ) { _ ->
            withRete {
              withRebaseLoop(
                CompletableDeferred(kernel),
                DefaultInstructionSet,
                reconnectWhenBroken = false,
              ) {
                change { register(SearchSessionEntity) }
                log("Search Everywhere: database connected")
                ready.complete(
                  currentCoroutineContext().minusKey(Job) to
                    proxy.proxy(SearchApiDescriptor, BACKEND_ROUTE, InstanceId(SEARCH_API)),
                )
                awaitCancellation()
              }
            }
          }
        }
    }
  }

  companion object {
    /** The route JetBrains Client sends backend calls to; IDEate's FleetRpc uses the same. */
    private val BACKEND_ROUTE = UID.fromString("fleet")
    private const val CHUNK = 50

    /**
     * Connects for the project with kernel id [kernelProjectId] (e.g. `m3nniruk9k7j4qsibjui`);
     * [dataContext] is the project's data context as `DataContextId` carries it.
     */
    fun start(
      model: FleetRpcModel,
      lifetime: Lifetime,
      scheduler: IScheduler,
      kernelProjectId: String,
      dataContext: String,
      log: (String) -> Unit,
    ): SearchKernel =
      SearchKernel(kernelProjectId, dataContext, log).also {
        it.connect(model, lifetime, scheduler)
      }
  }
}

private const val SEARCH_PACKAGE = "com.intellij.platform.searchEverywhere"
private const val SEARCH_API = "$SEARCH_PACKAGE.impl.SeRemoteApi"
private val KERNEL_API = RemoteKernel._generated_RemoteApiDescriptor.getApiFqn()

/**
 * IntelliJ's `SeRemoteApi` (platform/searchEverywhere/shared/src/impl/SeRemoteApi.kt), the two
 * calls IDEate makes, with arguments and items as JSON so IDEate needs no IntelliJ platform jars.
 * JetBrains' RPC plugin generates this class for IntelliJ; here it is written by hand.
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

  suspend fun itemSelected(
    projectId: JsonElement,
    session: JsonElement,
    itemData: JsonElement,
    modifiers: Int,
    searchText: String,
    isAllTab: Boolean,
  ): Boolean =
    proxy("itemSelected", arrayOf(projectId, session, itemData, modifiers, searchText, isAllTab))
      as Boolean
}

private object SearchApiDescriptor : RemoteApiDescriptor<SearchApi> {
  private val json = RemoteKind.Data(JsonElement.serializer())
  private val boolean = RemoteKind.Data(Boolean.serializer())

  override fun getSignature(methodName: String): RpcSignature =
    when (methodName) {
      "getItems" ->
        RpcSignature(
          methodName,
          arrayOf(
            ParameterDescriptor("projectId", json),
            ParameterDescriptor("session", json),
            ParameterDescriptor("providerIds", json),
            ParameterDescriptor("isAllTab", boolean),
            ParameterDescriptor("params", json),
            ParameterDescriptor(
              "dataContextId",
              RemoteKind.Data(JsonElement.serializer().nullable),
            ),
            ParameterDescriptor(
              "requestedCountChannel",
              RemoteKind.ReceiveChannel(RemoteKind.Data(Int.serializer()), nullable = false),
            ),
          ),
          RemoteKind.Flow(json, nullable = false),
        )
      "itemSelected" ->
        RpcSignature(
          methodName,
          arrayOf(
            ParameterDescriptor("projectId", json),
            ParameterDescriptor("session", json),
            ParameterDescriptor("itemData", json),
            ParameterDescriptor("modifiers", RemoteKind.Data(Int.serializer())),
            ParameterDescriptor("searchText", RemoteKind.Data(String.serializer())),
            ParameterDescriptor("isAllTab", boolean),
          ),
          boolean,
        )
      else -> error("SearchApi has no $methodName")
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
 * has seen the client's changes (the session) before it runs the call.
 *
 * ponytail: the answer's clock is not awaited; add it when a call reads entities the backend wrote.
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
      "$SEARCH_PACKAGE.SeSessionEntity",
      "<unknown>",
      ::SearchSessionEntity,
    )
}
