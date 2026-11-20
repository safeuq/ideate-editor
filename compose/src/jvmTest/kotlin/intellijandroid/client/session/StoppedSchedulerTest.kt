package intellijandroid.client.session

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.threading.SingleThreadScheduler
import kotlin.test.Test
import kotlin.test.assertEquals

/** A tap after the session stopped, e.g. while IDEate reconnects, is dropped, not thrown. */
class StoppedSchedulerTest {
  @Test
  fun anActionAfterTheSessionStoppedIsDroppedAndLogged() {
    val lifetime = LifetimeDefinition()
    val scheduler = SingleThreadScheduler(lifetime, "rd-test")
    lifetime.terminate()
    val logged = mutableListOf<String>()
    var ran = false
    scheduler.queueUnlessStopped(logged::add) { ran = true }
    assertEquals(false, ran)
    assertEquals(listOf("Session stopped; dropped an action"), logged)
  }
}
