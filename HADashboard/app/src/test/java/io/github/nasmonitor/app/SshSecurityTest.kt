package io.github.nasmonitor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSecurityTest {
    @Test fun hostIdentityUsesRealEndpoint() {
        assertEquals("nas.internal", SshHostIdentity.name("nas.internal", 22))
        assertEquals("[nas.internal]:1996", SshHostIdentity.name("nas.internal", 1996))
    }

    @Test fun rsa3072CanRoundTripAndProducesSha2CapablePublicKey() {
        val generated = SshKeyTools.generateRsa3072("test")
        assertTrue(generated.privateKey.contains("PRIVATE KEY"))
        assertTrue(generated.publicKey.startsWith("ssh-rsa "))
        assertEquals(generated.publicKey.substringBeforeLast(" "), SshKeyTools.publicKey(generated.privateKey, "", "test").substringBeforeLast(" "))
    }

    @Test fun fingerprintIsStableAndModern() {
        val first = SshHostIdentity.fingerprint(byteArrayOf(1, 2, 3))
        assertTrue(first.startsWith("SHA256:"))
        assertFalse(first.contains("="))
        assertEquals(first, SshHostIdentity.fingerprint(byteArrayOf(1, 2, 3)))
    }
}
