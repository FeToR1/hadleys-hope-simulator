package colony.runtime

/** Explicit close is the production contract; this registry also permits test-scope cleanup. */
object NativeResources {
    private val resources = java.util.concurrent.ConcurrentLinkedQueue<AutoCloseable>()
    fun track(resource: AutoCloseable) { resources.add(resource) }
    fun release(resource: AutoCloseable) { resources.remove(resource) }
    fun closeAll() { while (true) { val resource = resources.poll() ?: return; runCatching { resource.close() } } }
}
