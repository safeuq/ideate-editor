package intellijandroid.client.session

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.reactive.ViewableMap
import kotlin.test.Test
import kotlin.test.assertEquals

class StartWhenListedTest {
  private val session = LifetimeDefinition()
  private val configurations = ViewableMap<String, String>()
  private val started = mutableListOf<String>()

  @Test
  fun startsOnceWhenTheKeyIsPutRemovedAndPutAgain() {
    startWhenListed(session, configurations, "Application.Main") { started += it }
    configurations["Gradle.hello"] = "hello"
    assertEquals(emptyList(), started)

    configurations["Application.Main"] = "first"
    configurations.remove("Application.Main")
    configurations["Application.Main"] = "second"

    assertEquals(listOf("first"), started)
  }

  @Test
  fun startsAtOnceWhenTheKeyIsAlreadyListed() {
    configurations["Application.Main"] = "listed"
    startWhenListed(session, configurations, "Application.Main") { started += it }
    configurations["Application.Main"] = "again"
    assertEquals(listOf("listed"), started)
  }
}
