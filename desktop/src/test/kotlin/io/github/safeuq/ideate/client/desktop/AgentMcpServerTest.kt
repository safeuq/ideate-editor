package io.github.safeuq.ideate.client.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JSON-RPC handling and the HTTP rules, over a real server on an ephemeral port. */
class AgentMcpServerTest {
  private val mcp = AgentMcpServer()

  @Test
  fun `initialize echoes a supported version and falls back otherwise`() {
    val supported = mcp.handle(request("initialize", """{"protocolVersion":"2025-03-26"}"""))
    assertTrue(""""protocolVersion":"2025-03-26"""" in supported!!)
    assertTrue("\"tools\":{}" in supported)
    val unknown = mcp.handle(request("initialize", """{"protocolVersion":"1999-01-01"}"""))
    assertTrue(""""protocolVersion":"2025-06-18"""" in unknown!!)
  }

  @Test
  fun `tools list names every tool`() {
    val reply = mcp.handle(request("tools/list"))!!
    for (tool in
      listOf("list_windows", "screenshot", "move", "click", "drag", "scroll", "type", "press")) {
      assertTrue(""""name":"$tool"""" in reply, tool)
    }
  }

  @Test
  fun `a notification gets no reply`() {
    assertNull(mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
  }

  @Test
  fun `a bad tool call is a tool error, not a protocol error`() {
    val noWindow =
      mcp.handle(request("tools/call", """{"name":"move","arguments":{"x":1,"y":2}}"""))!!
    assertTrue(""""isError":true""" in noWindow && "No Ideate window is open" in noWindow)
    val unknownTool = mcp.handle(request("tools/call", """{"name":"nope"}"""))!!
    assertTrue(""""isError":true""" in unknownTool)
  }

  @Test
  fun `an unknown method is a JSON-RPC error`() {
    val reply = mcp.handle(request("nope"))!!
    assertTrue(""""code":-32601""" in reply && """"id":1""" in reply, reply)
    assertTrue(""""code":-32700""" in mcp.handle("not json")!!)
  }

  @Test
  fun `HTTP answers POST with json, a notification with 202 and GET with 405`() {
    val server = mcp.start(0)
    try {
      val url = URI("http://127.0.0.1:${server.address.port}/mcp")
      val client = HttpClient.newHttpClient()
      fun send(builder: HttpRequest.Builder) =
        client.send(builder.uri(url).build(), HttpResponse.BodyHandlers.ofString())
      val ping =
        send(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(request("ping"))))
      assertEquals(200, ping.statusCode())
      assertEquals("application/json", ping.headers().firstValue("Content-Type").get())
      val notification =
        send(
          HttpRequest.newBuilder()
            .POST(
              HttpRequest.BodyPublishers.ofString(
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
              ),
            ),
        )
      assertEquals(202, notification.statusCode())
      assertEquals(405, send(HttpRequest.newBuilder().GET()).statusCode())
    } finally {
      server.stop(0)
    }
  }

  private fun request(method: String, params: String = "{}") =
    """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
}
