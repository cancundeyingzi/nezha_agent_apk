package com.nezhahq.agent.util

internal object VpnTrafficCompatibility {
    fun isSupported(sdkInt: Int): Boolean = sdkInt < 31

    fun normalize(enabled: Boolean, sdkInt: Int): Boolean = enabled && isSupported(sdkInt)
}
