package io.github.nasmonitor.app

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ServerRegistryTest {
    @Test fun deletedLegacyHostsAreNotRecreatedByModelDefaults() {
        val overview = Gson().fromJson("""{"nas":{"status":"up"},"servers":[{"id":"nas","name":"NAS","deletable":false,"metrics":{"status":"up"}}]}""", OverviewResponse::class.java)
        assertEquals(listOf("nas"), overview.serverEntries().map { it.id })
    }

    @Test fun dynamicServerRetainsNameMetricsAndAddress() {
        val overview = Gson().fromJson("""{"servers":[{"id":"srv_123","name":"备用服务器","glances_url":"http://test:61208","mesh_ip":"test","metrics":{"status":"up","cpu":23.5}}]}""", OverviewResponse::class.java)
        val server = overview.serverEntries().single()
        assertEquals("备用服务器", server.name)
        assertEquals("http://test:61208", server.glancesUrl)
        assertEquals(23.5, server.metrics.cpu, 0.001)
        assertEquals("server/srv_123", serverDetailRoute(server.id))
    }

    @Test fun oldBackendSnapshotRemainsReadable() {
        val overview = Gson().fromJson("""{"nas":{"status":"up"},"aliyun":{"status":"unknown"},"tencent":{"status":"up"}}""", OverviewResponse::class.java)
        assertEquals(listOf("nas", "aliyun", "tencent"), overview.serverEntries().map { it.id })
        assertEquals("up", overview.serverEntries().last().metrics.status)
    }

    @Test fun dynamicToolRoutesUseGenericDetailScreen() {
        val entries = io.github.nasmonitor.app.ui.toolEntrySpecsForHost("srv_123", "备用服务器")
        assertTrue(entries.any { it.route == "server/srv_123" })
        assertFalse(entries.any { it.route.contains("tencent") })
    }
}
