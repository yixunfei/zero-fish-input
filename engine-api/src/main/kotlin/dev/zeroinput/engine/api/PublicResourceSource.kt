package dev.zeroinput.engine.api

/** Public data only. Acquire on a worker; keep the lease until native readers close. */
interface PublicResourceSource {
    val revision: Long
    fun acquire(id: String): PublicResourceLease?
}

interface PublicResourceLease : AutoCloseable {
    val files: Map<String, PublicResourceFile>
}

data class PublicResourceFile(val path: String, val bytes: Long, val sha256: String)
