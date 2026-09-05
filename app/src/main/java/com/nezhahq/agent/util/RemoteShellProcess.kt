package com.nezhahq.agent.util

/**
 * Normalize a remote process to the local Process contract. Querying exitValue while a
 * Shizuku process is running sends an exception through Binder, which need not preserve
 * IllegalThreadStateException. Only request the remote exit code after alive() is false.
 * Transport failures deliberately propagate so callers can discard a broken session.
 */
internal class RemoteShellProcess(
    private val delegate: Process,
    private val remoteAlive: () -> Boolean
) : Process() {
    override fun getOutputStream() = delegate.outputStream
    override fun getInputStream() = delegate.inputStream
    override fun getErrorStream() = delegate.errorStream
    override fun waitFor(): Int = delegate.waitFor()

    override fun exitValue(): Int {
        if (remoteAlive()) throw IllegalThreadStateException("Remote shell is still running")
        return delegate.exitValue()
    }

    override fun destroy() = delegate.destroy()
}
