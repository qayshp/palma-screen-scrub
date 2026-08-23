package dev.palma.screenscrub

object ProductionScrubGate {
    private var activeToken: String? = null

    @Synchronized
    fun tryAcquire(token: String): Boolean {
        if (activeToken != null) return false
        activeToken = token
        return true
    }

    @Synchronized
    fun release(token: String) {
        if (activeToken == token) activeToken = null
    }
}
