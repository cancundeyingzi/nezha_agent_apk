package com.nezhahq.agent.executor

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListQueryTest {

    @Test
    fun userApplicationFilterExcludesSystemAndUpdatedSystemApps() {
        assertTrue(AppListQuery.isUserApplicationFlags(0))
        assertFalse(AppListQuery.isUserApplicationFlags(ApplicationInfo.FLAG_SYSTEM))
        assertFalse(AppListQuery.isUserApplicationFlags(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        assertFalse(
            AppListQuery.isUserApplicationFlags(
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            )
        )
    }

    @Test
    fun parseProcessStatesGroupsChildProcessesAndPrefersRunning() {
        val userPackages = setOf(
            "com.demo.app",
            "com.chat.app",
            "com.media.player"
        )
        val dumpsys = """
            Process LRU list (sorted by oom_adj):
              Proc # 0: fore  F/ /TOP  trm: 0 1234:com.demo.app/u0a123 (top-activity)
              Proc # 1: cch   CEM /CEM trm: 0 1235:com.demo.app:push/u0a123 (cached-empty)
              Proc # 2: cch   CEM /CEM trm: 0 2234:com.chat.app:remote/u0a124 (cached-empty)
              Proc # 3: psvc  F/ /SVC  trm: 0 3234:com.media.player/u0a125 (service)
        """.trimIndent()

        val states = AppListQuery.parseProcessStates(dumpsys, userPackages)

        assertEquals(AppListQuery.ProcessState.RUNNING, states["com.demo.app"])
        assertEquals(AppListQuery.ProcessState.CACHED, states["com.chat.app"])
        assertEquals(AppListQuery.ProcessState.RUNNING, states["com.media.player"])
        assertEquals(3, states.size)
    }

    @Test
    fun parseProcessStatesIgnoresSystemPackages() {
        val dumpsys = """
            Process LRU list (sorted by oom_adj):
              Proc # 0: pers  F/ /PER  trm: 0 100:system/u0s1000 (system)
              Proc # 1: fore  F/ /TOP  trm: 0 200:com.android.settings/u0a10 (top-activity)
              Proc # 2: fore  F/ /TOP  trm: 0 300:com.user.app/u0a123 (top-activity)
        """.trimIndent()

        val states = AppListQuery.parseProcessStates(dumpsys, setOf("com.user.app"))

        assertEquals(mapOf("com.user.app" to AppListQuery.ProcessState.RUNNING), states)
    }

    @Test
    fun parseProcessStatesSupportsCustomProcessNamePrefix() {
        val dumpsys = """
            Process LRU list (sorted by oom_adj):
              Proc # 0: cch+2 CACHED trm: 0 4321:com.demo.app.worker/u0a123 (cached-empty)
        """.trimIndent()

        val states = AppListQuery.parseProcessStates(dumpsys, setOf("com.demo.app"))

        assertEquals(mapOf("com.demo.app" to AppListQuery.ProcessState.CACHED), states)
    }

    @Test
    fun parseProcessStatesReturnsEmptyForBlankOrNoUserPackages() {
        assertTrue(AppListQuery.parseProcessStates("", setOf("com.demo.app")).isEmpty())
        assertTrue(
            AppListQuery.parseProcessStates(
                "Proc # 0: fore trm: 0 1234:com.demo.app/u0a123",
                emptySet()
            ).isEmpty()
        )
    }

    @Test
    fun parseProcessMemoryBytesSumsAllUserAppProcesses() {
        val meminfo = """
            Applications Memory Usage (in Kilobytes):
            Total PSS by process:
                1,024K: com.demo.app (pid 100)
                  512K: com.demo.app:push (pid 101)
                2,048K: com.chat.app.worker (pid 200)
               99,999K: system (pid 1)
            Total PSS by OOM adjustment:
                1,536K: Native
        """.trimIndent()

        val memory = AppListQuery.parseProcessMemoryBytes(
            meminfo,
            setOf("com.demo.app", "com.chat.app")
        )

        assertEquals(1_536L * 1024L, memory["com.demo.app"])
        assertEquals(2_048L * 1024L, memory["com.chat.app"])
        assertEquals(2, memory.size)
    }

    @Test
    fun parseProcessMemoryBytesReturnsEmptyForBlankOrMissingSection() {
        assertTrue(AppListQuery.parseProcessMemoryBytes("", setOf("com.demo.app")).isEmpty())
        assertTrue(
            AppListQuery.parseProcessMemoryBytes(
                "Total PSS by OOM adjustment:\n  1,024K: Native",
                setOf("com.demo.app")
            ).isEmpty()
        )
    }
}
