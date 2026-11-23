package io.github.safeuq.ideate.client.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Lets an agent drive the running app at screen points (docs/client.md, "Agent MCP server"). Off
 * unless the system property `ideate.agent.mcp.port` is set; then it listens on 127.0.0.1 only.
 * [IdeateLocals]' window registers here when it shows.
 */
object AgentMcp {
  private var server: AgentMcpServer? = null

  /** Makes [window] the one the tools drive; starts the server on the first call if enabled. */
  @Synchronized
  fun register(window: Window) {
    server?.input = ComposeWindowInput(window)
    if (server != null) return
    val port = System.getProperty("ideate.agent.mcp.port")?.toInt() ?: return
    server = AgentMcpServer().also { it.input = ComposeWindowInput(window) }.also { it.start(port) }
  }

  @Synchronized
  fun unregister(window: Window) {
    if (server?.input?.window === window) server?.input = null
  }
}

/** MCP over Streamable HTTP, `POST /mcp` only: no sessions, no SSE. */
class AgentMcpServer {
  @Volatile var input: ComposeWindowInput? = null

  fun start(port: Int): HttpServer =
    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
      createContext("/mcp") { exchange -> respond(exchange) }
      start()
    }

  private fun respond(exchange: HttpExchange) = exchange.use {
    if (it.requestMethod != "POST") {
      it.sendResponseHeaders(405, -1)
      return@use
    }
    val reply = handle(it.requestBody.readBytes().decodeToString())
    if (reply == null) {
      it.sendResponseHeaders(202, -1)
      return@use
    }
    val bytes = reply.encodeToByteArray()
    it.responseHeaders.add("Content-Type", "application/json")
    it.sendResponseHeaders(200, bytes.size.toLong())
    it.responseBody.write(bytes)
  }

  /** One JSON-RPC message in, the reply out; `null` for a notification (no reply). */
  fun handle(body: String): String? {
    val message =
      try {
        Json.parseToJsonElement(body).jsonObject
      } catch (parseFailure: Exception) {
        return failure(JsonNull, -32700, "Parse error: ${parseFailure.message}")
      }
    val id = message["id"] ?: return null
    val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
    val method = message["method"]?.jsonPrimitive?.contentOrNull
    val result =
      when (method) {
        "initialize" -> initialize(params)
        "ping" -> JsonObject(emptyMap())
        "tools/list" -> buildJsonObject { put("tools", tools) }
        "tools/call" -> callTool(params)
        else -> return failure(id, -32601, "Method not found: $method")
      }
    return buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }
      .toString()
  }

  private fun failure(id: JsonElement, code: Int, text: String) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put(
      "error",
      buildJsonObject {
        put("code", code)
        put("message", text)
      },
    )
  }
    .toString()

  private fun initialize(params: JsonObject) = buildJsonObject {
    val asked = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
    put("protocolVersion", asked?.takeIf { it in SUPPORTED_VERSIONS } ?: "2025-06-18")
    put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
    put(
      "serverInfo",
      buildJsonObject {
        put("name", "ideate-window")
        put("version", "1")
      },
    )
  }

  private fun callTool(params: JsonObject): JsonObject {
    val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    return try {
      val name =
        requireNotNull(params["name"]?.jsonPrimitive?.contentOrNull) { "Missing tool name" }
      val content = Tools(checkNotNull(input) { "No Ideate window is open" }, arguments).run(name)
      buildJsonObject { put("content", content) }
    } catch (toolFailure: Exception) {
      buildJsonObject {
        put("content", textContent(toolFailure.message ?: toolFailure.toString()))
        put("isError", true)
      }
    }
  }

  private fun textContent(text: String) = buildJsonArray {
    add(
      buildJsonObject {
        put("type", "text")
        put("text", text)
      },
    )
  }

  private inner class Tools(val input: ComposeWindowInput, val arguments: JsonObject) {
    fun run(name: String): JsonArray =
      when (name) {
        "list_windows" -> textContent(listWindows())
        "screenshot" -> screenshot()
        "move" -> done { input.move(int("x"), int("y"), target()) }
        "click" ->
          done {
            input.click(
              int("x"),
              int("y"),
              target(),
              button = button(),
              clicks = optionalInt("count") ?: 1,
            )
          }
        "drag" ->
          done {
            input.drag(int("fromX"), int("fromY"), int("toX"), int("toY"), target())
          }
        "scroll" -> done { input.scroll(int("x"), int("y"), int("notches"), target()) }
        "type" -> done { input.type(text("text"), target()) }
        "press" -> done { input.press(keyCode(), modifiers(), target()) }
        else -> throw IllegalArgumentException("Unknown tool: $name")
      }

    private fun done(action: () -> Unit): JsonArray {
      action()
      return textContent("ok")
    }

    private fun text(key: String) =
      requireNotNull(arguments[key]?.jsonPrimitive?.contentOrNull) { "Missing argument: $key" }

    private fun optionalInt(key: String) = arguments[key]?.jsonPrimitive?.int

    private fun int(key: String) = requireNotNull(optionalInt(key)) { "Missing argument: $key" }

    /**
     * The window argument: 0 or none is the main window, 1.. the popups in `popupWindows()` order.
     */
    private fun target(): Window {
      val index = optionalInt("window") ?: 0
      val popups = input.popupWindows()
      require(index in 0..popups.size) {
        "No window $index: use 0 (main) to ${popups.size} (see list_windows)"
      }
      return if (index == 0) input.window else popups[index - 1].window
    }

    private fun button() =
      when (val name = arguments["button"]?.jsonPrimitive?.contentOrNull ?: "left") {
        "left" -> MouseEvent.BUTTON1
        "right" -> MouseEvent.BUTTON3
        "middle" -> MouseEvent.BUTTON2
        else -> throw IllegalArgumentException("Unknown button: $name (left, right, middle)")
      }

    private fun keyCode(): Int {
      val name = text("key")
      return try {
        KeyEvent::class.java.getField("VK_$name").getInt(null)
      } catch (unknownKey: ReflectiveOperationException) {
        throw IllegalArgumentException("Unknown key: $name (an AWT VK_ name without VK_)")
      }
    }

    private fun modifiers() =
      (arguments["modifiers"] as? JsonArray).orEmpty().fold(0) { mask, modifier ->
        mask or
          when (val name = modifier.jsonPrimitive.content) {
            "shift" -> InputEvent.SHIFT_DOWN_MASK
            "ctrl" -> InputEvent.CTRL_DOWN_MASK
            "alt" -> InputEvent.ALT_DOWN_MASK
            "meta" -> InputEvent.META_DOWN_MASK
            else -> throw IllegalArgumentException("Unknown modifier: $name (shift ctrl alt meta)")
          }
      }

    private fun listWindows(): String {
      val windows =
        listOf(Triple("main", input.window, input.window.bounds)) +
          input.popupWindows().map { Triple("popup", it.window, it.bounds) }
      return buildJsonArray {
        windows.forEachIndexed { index, (kind, window, bounds) ->
          add(
            buildJsonObject {
              put("index", index)
              put("kind", kind)
              put(
                "title",
                when (window) {
                  is Frame -> window.title
                  is Dialog -> window.title
                  else -> ""
                },
              )
              put(
                "bounds",
                buildJsonObject {
                  put("x", bounds.x)
                  put("y", bounds.y)
                  put("width", bounds.width)
                  put("height", bounds.height)
                },
              )
            },
          )
        }
      }
        .toString()
    }

    private fun screenshot(): JsonArray {
      val pictures = input.screenshots()
      val index = optionalInt("window")
      require(index == null || index in pictures.indices) {
        "No window $index: use 0 to ${pictures.size - 1} (see list_windows)"
      }
      // ponytail: shoots every window, then keeps the asked one.
      return buildJsonArray {
        for (picture in if (index == null) pictures else listOf(pictures[index])) {
          add(
            buildJsonObject {
              put("type", "image")
              put("data", Base64.getEncoder().encodeToString(picture))
              put("mimeType", "image/png")
            },
          )
        }
      }
    }
  }

  private val tools: JsonArray = buildJsonArray {
    val point = mapOf("x" to "integer", "y" to "integer")
    fun tool(
      name: String,
      description: String,
      properties: Map<String, String>,
      vararg required: String,
    ) =
      add(
        buildJsonObject {
          put("name", name)
          put("description", description)
          put(
            "inputSchema",
            buildJsonObject {
              put("type", "object")
              put(
                "properties",
                buildJsonObject {
                  for ((property, type) in properties + ("window" to "integer")) {
                    put(property, buildJsonObject { put("type", type) })
                  }
                },
              )
              put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            },
          )
        },
      )
    tool(
      "list_windows",
      "Windows: index, kind (main or popup), title, bounds. Window 0 is the main one.",
      emptyMap(),
    )
    tool("screenshot", "PNG of window (one image each when omitted).", emptyMap())
    tool("move", "Move the pointer to x, y (content dp).", point, "x", "y")
    tool(
      "click",
      "Click at x, y. button: left (default), right or middle. count: 1 by default.",
      point + mapOf("button" to "string", "count" to "integer"),
      "x",
      "y",
    )
    tool(
      "drag",
      "Press at fromX, fromY, move to toX, toY, release.",
      mapOf("fromX" to "integer", "fromY" to "integer", "toX" to "integer", "toY" to "integer"),
      "fromX",
      "fromY",
      "toX",
      "toY",
    )
    tool(
      "scroll",
      "Wheel at x, y; positive notches scroll down.",
      point + ("notches" to "integer"),
      "x",
      "y",
      "notches",
    )
    tool(
      "type",
      "Type text into what has Compose focus (click it first).",
      mapOf("text" to "string"),
      "text",
    )
    tool(
      "press",
      "Press a key: an AWT VK_ name without VK_ (ENTER, ESCAPE, F). modifiers: shift, ctrl, alt, meta.",
      mapOf("key" to "string", "modifiers" to "array"),
      "key",
    )
  }

  private companion object {
    val SUPPORTED_VERSIONS = setOf("2025-06-18", "2025-03-26", "2024-11-05")
  }
}
