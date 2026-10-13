package io.github.nasmonitor.app

import io.github.nasmonitor.app.ui.toolEntrySpecsForHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEntrySpecsTest {
    @Test
    fun host_tools_never_navigate_to_another_host_or_shared_storage() {
        for (host in listOf("nas", "aliyun", "tencent")) {
            val routes = toolEntrySpecsForHost(host, host).map { it.route }
            assertEquals(routes.size, routes.distinct().size)
            assertFalse(routes.contains("disks"))
            assertTrue(routes.all { it == host || it.endsWith("/$host") })
            assertTrue(routes.containsAll(listOf("trends/$host", "containers/$host", "services/$host", host)))
        }
    }

    @Test
    fun temperature_is_only_available_for_hosts_with_sensors() {
        assertTrue(toolEntrySpecsForHost("nas", "NAS").any { it.route == "temperature/nas" })
        assertTrue(toolEntrySpecsForHost("aliyun", "阿里云").any { it.route == "temperature/aliyun" })
        assertFalse(toolEntrySpecsForHost("tencent", "腾讯云").any { it.route.startsWith("temperature/") })
    }
}
