package com.nezhahq.agent.core.platform

object VpnTrafficCompatibility {
    fun isSupported(sdkInt: Int): Boolean = sdkInt < 31

    fun normalize(enabled: Boolean, sdkInt: Int): Boolean = enabled && isSupported(sdkInt)
}
