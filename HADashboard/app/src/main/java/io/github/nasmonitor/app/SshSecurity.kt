package io.github.nasmonitor.app

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

enum class SshAuthMethod { PASSWORD, PRIVATE_KEY }

data class SshCredential(val method: SshAuthMethod, val password: String, val privateKey: String, val passphrase: String)

object SshKeyTools {
    data class GeneratedKey(val privateKey: String, val publicKey: String)

    fun generateRsa3072(comment: String): GeneratedKey {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        return try {
            val privateOut = ByteArrayOutputStream()
            val publicOut = ByteArrayOutputStream()
            pair.writePrivateKey(privateOut)
            pair.writePublicKey(publicOut, comment)
            GeneratedKey(privateOut.toString(Charsets.UTF_8.name()), publicOut.toString(Charsets.UTF_8.name()).trim())
        } finally { pair.dispose() }
    }

    fun publicKey(privateKey: String, passphrase: String, comment: String): String {
        val pair = KeyPair.load(JSch(), privateKey.toByteArray(), null)
        return try {
            if (pair.isEncrypted && !pair.decrypt(passphrase)) throw IllegalArgumentException("私钥口令不正确")
            val out = ByteArrayOutputStream()
            pair.writePublicKey(out, comment)
            out.toString(Charsets.UTF_8.name()).trim()
        } finally { pair.dispose() }
    }

    fun validate(privateKey: String, passphrase: String) { publicKey(privateKey, passphrase, "NAS-Monitor") }
}

object SshProfileStore {
    fun auth(context: Context, prefix: String, jump: Boolean, legacyPassword: String): SshCredential {
        val slot = if (jump) "jump" else "target"
        val method = SecureCredentialStore.get(context, "$prefix.$slot.auth")
        return SshCredential(
            if (method == "key") SshAuthMethod.PRIVATE_KEY else SshAuthMethod.PASSWORD,
            SecureCredentialStore.get(context, "$prefix.${if (jump) "jumpPassword" else "password"}").ifEmpty { legacyPassword },
            SecureCredentialStore.get(context, "$prefix.$slot.privateKey"),
            SecureCredentialStore.get(context, "$prefix.$slot.passphrase")
        )
    }

    fun saveAuth(context: Context, prefix: String, jump: Boolean, credential: SshCredential) {
        val slot = if (jump) "jump" else "target"
        SecureCredentialStore.put(context, "$prefix.$slot.auth", if (credential.method == SshAuthMethod.PRIVATE_KEY) "key" else "password")
        SecureCredentialStore.put(context, "$prefix.${if (jump) "jumpPassword" else "password"}", credential.password)
        SecureCredentialStore.put(context, "$prefix.$slot.privateKey", credential.privateKey)
        SecureCredentialStore.put(context, "$prefix.$slot.passphrase", credential.passphrase)
    }
}

object SshHostIdentity {
    fun name(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"
    fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
}

class SecureHostKeyRepository(private val context: Context) : HostKeyRepository {
    private val pending = ConcurrentHashMap<String, ByteArray>()
    private fun storage(host: String) = "ssh.hostkey.$host"
    override fun check(host: String, key: ByteArray): Int {
        val saved = SecureCredentialStore.get(context, storage(host))
        if (saved.isEmpty()) {
            pending[host] = key.copyOf()
            return HostKeyRepository.NOT_INCLUDED
        }
        return try {
            if (MessageDigest.isEqual(Base64.getDecoder().decode(saved), key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
        } catch (_: IllegalArgumentException) {
            HostKeyRepository.CHANGED
        }
    }
    fun pendingKey(host: String): ByteArray? = (pending[host] ?: pending.values.singleOrNull())?.copyOf()
    fun remember(host: String, key: ByteArray) {
        pending.remove(host)
        SecureCredentialStore.put(context, storage(host), Base64.getEncoder().encodeToString(key))
    }
    fun forget(host: String) {
        pending.remove(host)
        SecureCredentialStore.remove(context, storage(host))
    }
    fun fingerprint(host: String): String? = SecureCredentialStore.get(context, storage(host)).takeIf { it.isNotEmpty() }?.let {
        runCatching { SshHostIdentity.fingerprint(Base64.getDecoder().decode(it)) }.getOrNull()
    }
    override fun add(hostkey: HostKey, ui: UserInfo?) = remember(hostkey.host, Base64.getDecoder().decode(hostkey.key))
    override fun remove(host: String, type: String?) = forget(host)
    override fun remove(host: String, type: String?, key: ByteArray?) = forget(host)
    override fun getKnownHostsRepositoryID(): String = "Android Keystore protected host keys"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

object SshSessionFactory {
    fun create(host: String, port: Int, user: String, auth: SshCredential, repository: HostKeyRepository, alias: String, confirmUnknown: (String, String) -> Boolean): Session {
        val jsch = JSch() // Each hop owns a JSch instance so identities can never bleed between sessions.
        if (auth.method == SshAuthMethod.PRIVATE_KEY) {
            require(auth.privateKey.isNotBlank()) { "尚未配置私钥" }
            jsch.addIdentity(alias, auth.privateKey.toByteArray(), null, auth.passphrase.toByteArray().takeIf { it.isNotEmpty() })
        }
        jsch.hostKeyRepository = repository
        val session = jsch.getSession(user, host, port).apply {
            hostKeyAlias = alias
            if (auth.method == SshAuthMethod.PASSWORD) setPassword(auth.password)
            setConfig("PreferredAuthentications", if (auth.method == SshAuthMethod.PRIVATE_KEY) "publickey" else "password,keyboard-interactive")
            setConfig("StrictHostKeyChecking", "ask")
            setConfig("server_host_key", "ssh-ed25519,rsa-sha2-512,rsa-sha2-256")
            userInfo = object : UserInfo {
                override fun getPassword() = auth.password
                override fun promptPassword(message: String?) = auth.method == SshAuthMethod.PASSWORD
                override fun getPassphrase() = auth.passphrase
                override fun promptPassphrase(message: String?) = auth.method == SshAuthMethod.PRIVATE_KEY
                override fun showMessage(message: String?) = Unit
                override fun promptYesNo(message: String?): Boolean {
                    val key = (repository as? SecureHostKeyRepository)?.pendingKey(alias) ?: return false
                    val accepted = confirmUnknown(alias, SshHostIdentity.fingerprint(key))
                    if (accepted) (repository as? SecureHostKeyRepository)?.remember(alias, key)
                    return accepted
                }
            }
        }
        return session
    }
}
