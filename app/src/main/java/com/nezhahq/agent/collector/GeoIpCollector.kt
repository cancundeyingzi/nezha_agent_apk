package com.nezhahq.agent.collector

import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import proto.Nezha.GeoIP
import proto.Nezha.IP
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object GeoIpCollector {
    private const val MACOS_CHROME_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val CACHE_TTL_MS = 60_000L
    private const val FAILURE_BACKOFF_MS = 30_000L
    private const val FAILURE_BACKOFF_THRESHOLD = 3

    private val traceEndpoints = listOf(
        "https://blog.cloudflare.com/cdn-cgi/trace",
        "https://developers.cloudflare.com/cdn-cgi/trace",
        "https://hostinger.com/cdn-cgi/trace",
        "https://ahrefs.com/cdn-cgi/trace"
    )

    private val ipv4Client = createClient(IpFamily.V4)
    private val ipv6Client = createClient(IpFamily.V6)

    private val cacheLock = Any()
    private var cachedGeoIP: GeoIP? = null
    private var cachedAtMs: Long = 0L
    private var retryTimes: Int = 0
    private var latestRetryAtMs: Long = 0L

    suspend fun fetchGeoIP(): GeoIP? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        getFreshCache(now)?.let { return@withContext it }
        if (isBackoffActive(now)) return@withContext null

        val ipv4Deferred = async { fetchIp(IpFamily.V4, ipv4Client) }
        val ipv6Deferred = async { fetchIp(IpFamily.V6, ipv6Client) }
        val ipv4 = ipv4Deferred.await()
        val ipv6 = ipv6Deferred.await()

        if (ipv4 == null && ipv6 == null) {
            recordFailure(System.currentTimeMillis())
            null
        } else {
            val ipMessage = IP.newBuilder().apply {
                ipv4?.ip?.let { setIpv4(it) }
                ipv6?.ip?.let { setIpv6(it) }
            }.build()

            GeoIP.newBuilder()
                .setIp(ipMessage)
                .setCountryCode(ipv4?.countryCode ?: ipv6?.countryCode ?: "")
                .build()
                .also { recordSuccess(it, System.currentTimeMillis()) }
        }
    }

    private fun createClient(family: IpFamily): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(FamilyDns(family))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Tries each endpoint in turn until one answers.
     *
     * A caller that gives up must not keep the agent waiting: four endpoints at a ten-second call
     * timeout is forty seconds, and this runs both on the connect handshake and inside runtime
     * teardown. Cancellation now aborts the in-flight call and stops the loop rather than being
     * discovered only after the last endpoint has timed out.
     */
    private suspend fun fetchIp(family: IpFamily, client: OkHttpClient): FetchResult? {
        for (endpoint in traceEndpoints) {
            currentCoroutineContext().ensureActive()

            val result = try {
                requestTrace(endpoint, family, client)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

            if (result != null) return result
        }

        return null
    }

    /** Enqueues rather than blocking, so no thread is held for the duration of the call. */
    private suspend fun requestTrace(
        endpoint: String,
        family: IpFamily,
        client: OkHttpClient
    ): FetchResult? {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", MACOS_CHROME_UA)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(
                        runCatching {
                            response.use {
                                if (!it.isSuccessful) return@use null
                                val trace = it.body?.string()?.let(::parseTraceBody)
                                    ?: return@use null
                                validateIpForFamily(trace.ip, family)
                                    ?.let { ip -> FetchResult(ip, trace.countryCode) }
                            }
                        }
                    )
                }
            })
        }
    }

    internal fun parseTraceBody(body: String): TraceBody {
        var ip: String? = null
        var countryCode: String? = null
        val lines = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        for (line in lines) {
            when {
                line.startsWith("ip=") -> ip = line.substringAfter("ip=").trim()
                line.startsWith("loc=") -> countryCode = line.substringAfter("loc=").trim()
            }
        }

        if (ip == null && lines.size == 1 && !lines.first().contains("=")) {
            ip = lines.first()
        }

        return TraceBody(
            ip = ip?.takeIf { it.isNotBlank() },
            countryCode = countryCode?.takeIf { it.isNotBlank() }
        )
    }

    internal fun validateIpForFamily(ip: String?, family: IpFamily): String? {
        val normalizedIp = ip
            ?.trim()
            ?.removeSurrounding("[", "]")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (!family.looksLike(normalizedIp)) return null

        val address = runCatching { InetAddress.getByName(normalizedIp) }.getOrNull()
            ?: return null

        return if (family.matches(address)) address.hostAddress else null
    }

    private fun getFreshCache(now: Long): GeoIP? = synchronized(cacheLock) {
        cachedGeoIP?.takeIf { now - cachedAtMs < CACHE_TTL_MS }
    }

    private fun isBackoffActive(now: Long): Boolean = synchronized(cacheLock) {
        retryTimes >= FAILURE_BACKOFF_THRESHOLD && now - latestRetryAtMs < FAILURE_BACKOFF_MS
    }

    private fun recordSuccess(geoIP: GeoIP, now: Long) = synchronized(cacheLock) {
        cachedGeoIP = geoIP
        cachedAtMs = now
        retryTimes = 0
        latestRetryAtMs = 0L
    }

    private fun recordFailure(now: Long) = synchronized(cacheLock) {
        retryTimes += 1
        latestRetryAtMs = now
    }

    private class FamilyDns(private val family: IpFamily) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname).filter { family.matches(it) }
            if (addresses.isEmpty()) {
                throw UnknownHostException("No ${family.label} address for $hostname")
            }
            return addresses
        }
    }

    internal enum class IpFamily(val label: String) {
        V4("IPv4") {
            override fun matches(address: InetAddress): Boolean = address is Inet4Address
            override fun looksLike(ip: String): Boolean = IPV4_REGEX.matches(ip)
        },
        V6("IPv6") {
            override fun matches(address: InetAddress): Boolean = address is Inet6Address
            override fun looksLike(ip: String): Boolean = ":" in ip
        };

        abstract fun matches(address: InetAddress): Boolean
        abstract fun looksLike(ip: String): Boolean
    }

    internal data class TraceBody(
        val ip: String?,
        val countryCode: String?
    )

    private data class FetchResult(
        val ip: String,
        val countryCode: String?
    )

    private val IPV4_REGEX =
        Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
}

