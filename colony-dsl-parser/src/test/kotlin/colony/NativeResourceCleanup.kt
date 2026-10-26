package colony

class NativeResourceCleanup : org.junit.jupiter.api.extension.AfterEachCallback {
    override fun afterEach(context: org.junit.jupiter.api.extension.ExtensionContext) = colony.runtime.NativeResources.closeAll()
}
