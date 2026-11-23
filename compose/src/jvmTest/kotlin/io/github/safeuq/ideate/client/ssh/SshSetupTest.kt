package io.github.safeuq.ideate.client.ssh

import io.github.safeuq.ideate.client.connect.StoredText
import io.github.safeuq.ideate.client.connect.defaultLoginMethod
import java.security.KeyPairGenerator
import java.security.PublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshSetupTest {
  private class MemoryText(var text: String? = null) : StoredText {
    override fun read() = text

    override fun write(text: String) {
      this.text = text
    }
  }

  private class MemorySecrets : SecretStore {
    val values = mutableMapOf<String, String>()

    override fun read(name: String) = values[name]

    override fun write(name: String, value: String) {
      values[name] = value
    }

    override fun delete(name: String) {
      values.remove(name)
    }
  }

  private fun newKey(): PublicKey =
    KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public

  @Test
  fun configTakesTheFirstValueAndAddsUpIdentityFiles() {
    val config =
      """
      |IdentityFile ~/.ssh/global
      |Host phone
      |  HostName 192.168.0.107
      |  Port 8022
      |  User u0_a404
      |  IdentityFile ~/.ssh/phone
      |Host phone *.lan
      |  Port 2222
      |  User someone
      |Match host phone
      |  Port 9999
      |Host * !phone
      |  User other
      """
        .trimMargin()
    val phone = OpenSshConfig.resolve(config, "phone")
    assertEquals(
      OpenSshHost("192.168.0.107", 8022, "u0_a404", listOf("~/.ssh/global", "~/.ssh/phone")),
      phone,
    )
    // `!phone` keeps the last block away from phone; for other hosts it applies.
    assertEquals(
      OpenSshHost(null, 2222, "someone", listOf("~/.ssh/global")),
      OpenSshConfig.resolve(config, "mac.lan"),
    )
    assertEquals("other", OpenSshConfig.resolve(config, "server").user)
  }

  @Test
  fun knownHostsAskForUnknownKeysAndRefuseChangedOnes() {
    val storage = MemoryText()
    val knownHosts = KnownHosts(storage)
    val key = newKey()
    assertEquals(KnownHosts.Verdict.UNKNOWN, knownHosts.check("192.168.0.107", 8022, key))
    knownHosts.trust("192.168.0.107", 8022, key)
    assertTrue(storage.text!!.startsWith("[192.168.0.107]:8022 ecdsa-sha2-nistp256 "), storage.text)
    assertEquals(KnownHosts.Verdict.KNOWN, knownHosts.check("192.168.0.107", 8022, key))
    assertEquals(KnownHosts.Verdict.CHANGED, knownHosts.check("192.168.0.107", 8022, newKey()))
    // Another port is another host for OpenSSH.
    assertEquals(KnownHosts.Verdict.UNKNOWN, knownHosts.check("192.168.0.107", 22, key))
    knownHosts.forget("192.168.0.107", 8022)
    assertEquals(KnownHosts.Verdict.UNKNOWN, knownHosts.check("192.168.0.107", 8022, key))
  }

  @Test
  fun knownHostsAlsoTrustTheUsersOwnList() {
    val key = newKey()
    val userList = MemoryText()
    KnownHosts(userList).trust("server.lan", 22, key)
    val knownHosts = KnownHosts(MemoryText(), also = { userList.text })
    assertEquals(KnownHosts.Verdict.KNOWN, knownHosts.check("server.lan", 22, key))
  }

  @Test
  fun hostsAreSavedReplacedAndRemovedWithTheirSecrets() {
    val hosts = SshHosts(MemoryText())
    val secrets = MemorySecrets()
    val phone = SshHostEntry("1", "192.168.0.107", 8022, "u0_a404", LoginMethod.PASSWORD)
    val mac = SshHostEntry("2", "mac.lan", 22, "dev", LoginMethod.OPEN_SSH)
    hosts.save(phone)
    hosts.save(mac)
    hosts.save(phone.copy(port = 8023))
    assertEquals(
      listOf("u0_a404@192.168.0.107:8023", "dev@mac.lan"),
      hosts.entries().map { it.name },
    )
    secrets.write(SecretNames.password(phone), "secret")
    hosts.remove(phone, secrets)
    assertEquals(listOf(mac), hosts.entries())
    assertNull(secrets.read(SecretNames.password(phone)))
  }

  @Test
  fun loginsComeFromTheSavedSecretsOrTheUser() {
    val secrets = MemorySecrets()
    val generated = SshHostEntry("1", "phone", 8022, "u", LoginMethod.GENERATED_KEY)
    val first = SshLogins.resolve(generated, secrets, null, "Pixel 3a").logins.single()
    // The same key every time, made once.
    assertEquals(first, SshLogins.resolve(generated, secrets, null, "Pixel 3a").logins.single())
    assertTrue(secrets.read(SecretNames.IDEATE_PUBLIC_KEY)!!.endsWith(" Ideate on Pixel 3a"))

    val password = SshHostEntry("2", "phone", 8022, "u", LoginMethod.PASSWORD)
    assertFailsWith<SshLogins.MissingSecret> {
      SshLogins.resolve(password, secrets, null, "Pixel 3a")
    }
    assertEquals(
      SshLogin.Password("typed"),
      SshLogins.resolve(password, secrets, null, "Pixel 3a", typed = "typed").logins.single(),
    )
  }

  @Test
  fun newConnectionsStartWithOpenSshOnlyWhenOfferedAndPreferred() {
    assertEquals(
      LoginMethod.OPEN_SSH,
      defaultLoginMethod(offersOpenSsh = true, openSshIsDefault = true),
    )
    assertEquals(LoginMethod.GENERATED_KEY, defaultLoginMethod(true, openSshIsDefault = false))
    assertEquals(LoginMethod.GENERATED_KEY, defaultLoginMethod(false, openSshIsDefault = true))
  }

  @Test
  fun openSshModeUsesTheConfigTheAgentAndTheIdentityFiles() {
    val files =
      object : OpenSshFiles {
        override val home = "/Users/dev"
        override val hasAgent = true

        override fun read(path: String) =
          mapOf(
            "/Users/dev/.ssh/config" to
              "Host phone\n  HostName 192.168.0.107\n  Port 8022\n  User u0_a404\n",
            "/Users/dev/.ssh/id_ed25519" to "KEY",
          )[path]
      }
    val entry = SshHostEntry("1", "phone", 22, "", LoginMethod.OPEN_SSH)
    val resolved = SshLogins.resolve(entry, MemorySecrets(), files, "Mac")
    assertEquals(
      ResolvedLogin(
        "192.168.0.107",
        8022,
        "u0_a404",
        listOf(SshLogin.Agent, SshLogin.PrivateKey("KEY")),
      ),
      resolved,
    )
    // A port typed for the host wins over the config's.
    assertEquals(
      9022,
      SshLogins.resolve(entry.copy(port = 9022), MemorySecrets(), files, "Mac").port,
    )
  }
}
