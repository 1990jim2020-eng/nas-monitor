package io.github.nasmonitor.app

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AuthenticationRecoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var originalBaseUrl: String

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        originalBaseUrl = ApiClient.baseUrl
        ApiClient.setBaseUrl(server.url("").toString().trimEnd('/'))
        ApiClient.setApiToken("old-test-token")
    }

    @After
    fun stopServer() {
        if (originalBaseUrl.isNotBlank()) ApiClient.setBaseUrl(originalBaseUrl)
        ApiClient.setApiToken("")
        server.shutdown()
    }

    @Test
    fun blankCandidateDoesNotSendRequestOrChangeSession() = runBlocking {
        assertTrue(runCatching { ApiClient.verifyApiToken("   ") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
        assertOriginalSessionWorks()
    }

    @Test
    fun failedCandidateVerificationPreservesOriginalSession() = runBlocking {
        for (code in listOf(401, 403, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertTrue(runCatching { ApiClient.verifyApiToken("invalid-candidate-token-12345") }.isFailure)
            assertEquals("Bearer invalid-candidate-token-12345", server.takeRequest().getHeader("Authorization"))
            assertOriginalSessionWorks()
        }
    }

    @Test
    fun successfulVerificationDoesNotReplaceUntilExplicitCommit() = runBlocking {
        val candidate = "valid-candidate-token-12345"
        server.enqueue(MockResponse().setBody("{}"))
        ApiClient.verifyApiToken(candidate)
        assertEquals("Bearer $candidate", server.takeRequest().getHeader("Authorization"))
        assertOriginalSessionWorks()
        ApiClient.setApiToken(candidate)
        server.enqueue(MockResponse().setBody("{}"))
        ApiClient.getOverview()
        assertEquals("Bearer $candidate", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun cancellingCandidateVerificationPreservesOriginalSession() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch { ApiClient.verifyApiToken("candidate-token-to-cancel-12345") }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
        withTimeout(2000) { pending.cancelAndJoin() }
        assertOriginalSessionWorks()
    }

    @Test
    fun verifyingRejectedCurrentTokenDoesNotTriggerGlobalLogout() = runBlocking {
        val candidate = "current-token-long-enough-12345"
        ApiClient.setApiToken(candidate)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { ApiClient.verifyApiToken(candidate) }.exceptionOrNull() is UnauthorizedException)
        server.takeRequest()
        assertFalse(ApiClient.authenticationExpired.value)
        server.enqueue(MockResponse().setBody("{}"))
        ApiClient.getOverview()
        assertEquals("Bearer $candidate", server.takeRequest().getHeader("Authorization"))
    }

    private suspend fun assertOriginalSessionWorks() {
        assertFalse(ApiClient.authenticationExpired.value)
        server.enqueue(MockResponse().setBody("{}"))
        ApiClient.getOverview()
        assertEquals("Bearer old-test-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun rejectedTokenExpiresAndReplacementCanAuthenticate() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { ApiClient.getOverview() }.exceptionOrNull()
        assertTrue(failure is UnauthorizedException)
        assertTrue(ApiClient.authenticationExpired.value)
        server.takeRequest()
        ApiClient.setApiToken("replacement-test-token")
        server.enqueue(MockResponse().setBody("{}"))
        ApiClient.getOverview()
        assertFalse(ApiClient.authenticationExpired.value)
        assertEquals("Bearer replacement-test-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun serverErrorsAndForbiddenDoNotRevokeToken() = runBlocking {
        for (code in listOf(403, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertTrue(runCatching { ApiClient.getOverview() }.isFailure)
            assertFalse(ApiClient.authenticationExpired.value)
        }
    }

    @Test
    fun postEndpointsAlsoInvalidateRejectedAuthentication() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { ApiClient.restoreNewApiRoutingPolicy() }.exceptionOrNull() is UnauthorizedException)
        assertTrue(ApiClient.authenticationExpired.value)
    }

    @Test
    fun delayedOldRejectionDoesNotInvalidateReplacementToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        val pending = async { runCatching { ApiClient.getOverview() } }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
        ApiClient.setApiToken("replacement-test-token")
        assertTrue(pending.await().isFailure)
        assertFalse(ApiClient.authenticationExpired.value)
    }

    @Test
    fun cancellingAnUnresponsiveQuestionReturnsPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch { ApiClient.ask("test question") }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
        withTimeout(2000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertFalse(ApiClient.authenticationExpired.value)
    }

    @Test
    fun questionEndpointUsesTheSameAuthenticationRecovery() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { ApiClient.ask("test question") }.exceptionOrNull() is UnauthorizedException)
        assertTrue(ApiClient.authenticationExpired.value)
    }

    @Test
    fun synchronousFileArchiveSucceedsWithoutAnAsyncTaskId() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"filename":"test.txt","workspace":"nas-docker","result":{"success":true}}"""))
        val result = ApiClient.archiveFile("test.txt", "test".toByteArray())
        assertEquals("test.txt", result.filename)
        assertTrue(result.taskId.isEmpty())
        val request = server.takeRequest()
        assertEquals("/api/knowledge/archive/file", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun synchronousFileArchiveRejectsApplicationFailureEvenWithHttp200() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":false,"filename":"test.txt","result":{"success":false}}"""))
        assertTrue(runCatching { ApiClient.archiveFile("test.txt", "test".toByteArray()) }.isFailure)
        assertFalse(ApiClient.authenticationExpired.value)
    }
}
