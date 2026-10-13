package io.github.nasmonitor.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GatewayQueryTest {
    private lateinit var server: MockWebServer
    private lateinit var originalBaseUrl: String

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        originalBaseUrl = ApiClient.baseUrl
        ApiClient.setBaseUrl(server.url("").toString().trimEnd('/'))
        ApiClient.setApiToken("gateway-test-token")
    }

    @After
    fun stopServer() {
        if (originalBaseUrl.isNotBlank()) ApiClient.setBaseUrl(originalBaseUrl)
        ApiClient.setApiToken("")
        server.shutdown()
    }

    @Test
    fun rateLimitPreservesRetryDelayAndSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12"))
        val failure = runCatching { ApiClient.getNewApiGateway() }.exceptionOrNull()
        assertTrue(failure is GatewayRateLimitedException)
        assertEquals(12, (failure as GatewayRateLimitedException).retryAfterSeconds)
        assertFalse(ApiClient.authenticationExpired.value)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("{}"))
        ApiClient.getNewApiGateway()
        assertEquals("Bearer gateway-test-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun invalidRetryHeaderUsesDefault() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "invalid"))
        val failure = runCatching { ApiClient.getNewApiLogs(0) }.exceptionOrNull()
        assertEquals(60, (failure as GatewayRateLimitedException).retryAfterSeconds)
    }

    @Test
    fun hourLogsRetainPaginationAndTruncationMetadata() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":1000,"page":2,"page_size":30,"truncated":true,"scan_limit":1000,"window_seconds":3600}"""))
        val result = ApiClient.getNewApiLogs(1, token = "A B")
        assertEquals(2, result.page)
        assertEquals(30, result.pageSize)
        assertTrue(result.truncated)
        assertEquals(1000, result.scanLimit)
        assertEquals("/api/newapi/logs?p=1&page_size=30&token=A+B", server.takeRequest().path)
    }

    @Test
    fun cancellingLogQueryReturnsWithoutWaitingForNetworkTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch { ApiClient.getNewApiLogs(0) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
        withTimeout(2000) { pending.cancelAndJoin() }
        assertFalse(ApiClient.authenticationExpired.value)
    }
}
