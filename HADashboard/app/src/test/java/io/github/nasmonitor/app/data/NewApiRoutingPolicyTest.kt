package io.github.nasmonitor.app

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NewApiRoutingPolicyTest {
    private val gson = Gson()
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        ApiClient.setBaseUrl(server.url("").toString().trimEnd('/'))
        ApiClient.setApiToken("monitor-test-token")
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun parsesActivePreferredPolicyFromBackend() {
        val state = gson.fromJson(
            """{"active":true,"mode":"preferred","channel_id":15,"channel_name":"tyasai-gpt","phase":"active","channels_count":12}""",
            NewApiRoutingPolicy::class.java
        )
        assertTrue(state.active)
        assertEquals("preferred", state.mode)
        assertEquals(15, state.channelId)
        assertEquals("tyasai-gpt", state.channelName)
        assertEquals(12, state.channelsCount)
        assertEquals("最优先", routingPolicyModeLabel(state.mode))
    }

    @Test
    fun parsesIdleStateAndLabelsExclusiveMode() {
        val idle = gson.fromJson(
            """{"active":false,"mode":"normal","phase":"idle"}""",
            NewApiRoutingPolicy::class.java
        )
        assertFalse(idle.active)
        assertEquals("正常路由", routingPolicyModeLabel(idle.mode))
        assertEquals("仅使用", routingPolicyModeLabel("exclusive"))
    }

    @Test
    fun getPolicyUsesAuthenticatedGetEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"active":false,"mode":"normal","phase":"idle"}"""))
        val state = ApiClient.getNewApiRoutingPolicy()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/newapi/routing-policy", request.path)
        assertEquals("Bearer monitor-test-token", request.getHeader("Authorization"))
        assertFalse(state.active)
    }

    @Test
    fun applyAndRestoreUseExpectedPostPayloads() = runBlocking {
        val active = """{"success":true,"policy":{"active":true,"mode":"exclusive","channel_id":9,"channel_name":"商汤","phase":"active"}}"""
        val idle = """{"success":true,"policy":{"active":false,"mode":"normal","phase":"idle","restored_count":12}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(active))
        server.enqueue(MockResponse().setResponseCode(200).setBody(idle))

        val applied = ApiClient.applyNewApiRoutingPolicy(9, "exclusive")
        val applyRequest = server.takeRequest()
        assertEquals("POST", applyRequest.method)
        assertEquals("/api/newapi/routing-policy", applyRequest.path)
        val applyBody = gson.fromJson(applyRequest.body.readUtf8(), Map::class.java)
        assertEquals(9.0, applyBody["channel_id"])
        assertEquals("exclusive", applyBody["mode"])
        assertTrue(applied.active)

        val restored = ApiClient.restoreNewApiRoutingPolicy()
        val restoreRequest = server.takeRequest()
        assertEquals("POST", restoreRequest.method)
        assertEquals("/api/newapi/routing-policy/restore", restoreRequest.path)
        assertFalse(restored.active)
        assertEquals(12, restored.restoredCount)
    }
}
