package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bug these rules exist to prevent: scanning only system partitions and reporting a 256 GB
 * device as roughly 8 GB. Every case below is a mount layout that used to be able to cause it.
 */
class DiskUsageCalculatorTest {
    @Test
    fun theDataPartitionIsTheInternalBaselineAndSystemMountsAreIgnored() {
        val calculator = calculator(
            mounts = listOf(
                "/dev/block/sda1 /system ext4 ro 0 0",
                "/dev/block/sda2 /vendor ext4 ro 0 0",
                "/dev/block/sda3 /product ext4 ro 0 0"
            ),
            capacities = mapOf(
                "/data" to DiskInfo(256_000_000_000L, 100_000_000_000L),
                "/system" to DiskInfo(4_000_000_000L, 3_000_000_000L),
                "/vendor" to DiskInfo(2_000_000_000L, 1_000_000_000L),
                "/product" to DiskInfo(2_000_000_000L, 1_000_000_000L)
            )
        )

        assertEquals(
            DiskInfo(256_000_000_000L, 100_000_000_000L),
            calculator.getDiskInfo(isRootMode = false)
        )
    }

    /** /storage/emulated is the same partition seen through FUSE; counting it would double it. */
    @Test
    fun theFuseViewOfInternalStorageIsNotAddedOnTopOfData() {
        val calculator = calculator(
            mounts = listOf("/dev/fuse /storage/emulated/0 fuse rw 0 0"),
            capacities = mapOf(
                "/data" to DiskInfo(128_000_000_000L, 40_000_000_000L),
                "/storage/emulated/0" to DiskInfo(128_000_000_000L, 40_000_000_000L)
            )
        )

        assertEquals(
            DiskInfo(128_000_000_000L, 40_000_000_000L),
            calculator.getDiskInfo(isRootMode = false)
        )
    }

    @Test
    fun aRemovableCardIsAddedToTheInternalBaseline() {
        val calculator = calculator(
            mounts = listOf("/dev/block/mmcblk1p1 /storage/1A2B-3C4D vfat rw 0 0"),
            capacities = mapOf(
                "/data" to DiskInfo(64_000_000_000L, 20_000_000_000L),
                "/storage/1A2B-3C4D" to DiskInfo(32_000_000_000L, 8_000_000_000L)
            )
        )

        assertEquals(
            DiskInfo(96_000_000_000L, 28_000_000_000L),
            calculator.getDiskInfo(isRootMode = false)
        )
    }

    /** The same card appears under several mount points; the volume id deduplicates them. */
    @Test
    fun oneCardMountedSeveralTimesIsCountedOnce() {
        val calculator = calculator(
            mounts = listOf(
                "/dev/block/mmcblk1p1 /mnt/media_rw/1A2B-3C4D vfat rw 0 0",
                "/dev/block/mmcblk1p1 /storage/1A2B-3C4D vfat rw 0 0",
                "/dev/block/mmcblk1p1 /mnt/runtime/write/1A2B-3C4D sdcardfs rw 0 0"
            ),
            capacities = mapOf(
                "/data" to DiskInfo(64_000_000_000L, 20_000_000_000L),
                "/mnt/media_rw/1A2B-3C4D" to DiskInfo(32_000_000_000L, 8_000_000_000L),
                "/storage/1A2B-3C4D" to DiskInfo(32_000_000_000L, 8_000_000_000L),
                "/mnt/runtime/write/1A2B-3C4D" to DiskInfo(32_000_000_000L, 8_000_000_000L)
            )
        )

        assertEquals(
            DiskInfo(96_000_000_000L, 28_000_000_000L),
            calculator.getDiskInfo(isRootMode = false)
        )
    }

    @Test
    fun anUnreadableDataPartitionFallsBackToTheInternalViewInTheMountTable() {
        val calculator = calculator(
            mounts = listOf("/dev/block/sda10 /sdcard ext4 rw 0 0"),
            capacities = mapOf("/sdcard" to DiskInfo(50_000_000_000L, 10_000_000_000L))
        )

        assertEquals(
            DiskInfo(50_000_000_000L, 10_000_000_000L),
            calculator.getDiskInfo(isRootMode = false)
        )
    }

    @Test
    fun withNoInternalStorageAtAllAnOrdinaryBlockDeviceIsTheLastResort() {
        val calculator = calculator(
            mounts = listOf("/dev/block/dm-5 /mnt/expand/abcd ext4 rw 0 0"),
            capacities = mapOf("/mnt/expand/abcd" to DiskInfo(16_000_000_000L, 4_000_000_000L))
        )

        assertEquals(
            DiskInfo(16_000_000_000L, 4_000_000_000L),
            calculator.getDiskInfo(isRootMode = false)
        )
    }

    @Test
    fun mountPointsWithOctalEscapesAreDecodedBeforeBeingMeasured() {
        val calculator = calculator(
            mounts = listOf("/dev/block/mmcblk1p1 /storage/MY\\040CARD vfat rw 0 0"),
            capacities = mapOf(
                "/data" to DiskInfo(8_000_000_000L, 1_000_000_000L),
                "/storage/MY CARD" to DiskInfo(4_000_000_000L, 1_000_000_000L)
            )
        )

        assertEquals(
            DiskInfo(12_000_000_000L, 2_000_000_000L),
            calculator.getDiskInfo(isRootMode = false)
        )
    }

    @Test
    fun rootModeReadsThePrivilegedMountTable() {
        var requestedPrivileged: Boolean? = null
        val calculator = DiskUsageCalculator(
            mountTable = { privileged ->
                requestedPrivileged = privileged
                emptyList()
            },
            capacityProbe = { path ->
                if (path == "/data") DiskInfo(1_000L, 100L) else null
            },
            internalStoragePath = "/data"
        )

        calculator.getDiskInfo(isRootMode = true)

        assertEquals(true, requestedPrivileged)
    }

    @Test
    fun anUnmeasurableSetupReportsZeroRatherThanFailing() {
        val calculator = calculator(mounts = emptyList(), capacities = emptyMap())

        assertEquals(DiskInfo(0L, 0L), calculator.getDiskInfo(isRootMode = false))
    }

    private fun calculator(
        mounts: List<String>,
        capacities: Map<String, DiskInfo>
    ) = DiskUsageCalculator(
        mountTable = { mounts },
        capacityProbe = { path -> capacities[path] },
        internalStoragePath = "/data"
    )
}
