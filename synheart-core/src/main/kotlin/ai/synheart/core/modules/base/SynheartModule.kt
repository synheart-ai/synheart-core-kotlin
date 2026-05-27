package ai.synheart.core.modules.base

enum class ModuleStatus {
    UNINITIALIZED,
    INITIALIZING,
    INITIALIZED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
    DISPOSED
}

/** Base interface for all Synheart modules. */
interface SynheartModule {
    val moduleId: String
    val status: ModuleStatus
    val isEnabled: Boolean
    suspend fun initialize()
    suspend fun start()
    suspend fun stop()
    suspend fun dispose()
}

class ModuleException(
    val moduleId: String,
    message: String,
    cause: Throwable? = null
) : Exception("ModuleException [$moduleId]: $message", cause)

/** Base implementation of [SynheartModule] with common lifecycle management. */
abstract class BaseSynheartModule(override val moduleId: String) : SynheartModule {
    private var _status: ModuleStatus = ModuleStatus.UNINITIALIZED

    override val status: ModuleStatus
        get() = _status

    override val isEnabled: Boolean
        get() = _status == ModuleStatus.RUNNING

    override suspend fun initialize() {
        if (_status != ModuleStatus.UNINITIALIZED) {
            throw ModuleException(moduleId, "Module already initialized")
        }

        try {
            setStatus(ModuleStatus.INITIALIZING)
            onInitialize()
            setStatus(ModuleStatus.INITIALIZED)
        } catch (e: Exception) {
            setStatus(ModuleStatus.ERROR)
            throw ModuleException(moduleId, "Failed to initialize", e)
        }
    }

    override suspend fun start() {
        if (_status != ModuleStatus.INITIALIZED && _status != ModuleStatus.STOPPED) {
            throw ModuleException(moduleId, "Module must be initialized or stopped before starting")
        }

        try {
            setStatus(ModuleStatus.STARTING)
            onStart()
            setStatus(ModuleStatus.RUNNING)
        } catch (e: Exception) {
            setStatus(ModuleStatus.ERROR)
            throw ModuleException(moduleId, "Failed to start", e)
        }
    }

    override suspend fun stop() {
        if (_status != ModuleStatus.RUNNING) {
            throw ModuleException(moduleId, "Module is not running")
        }

        try {
            setStatus(ModuleStatus.STOPPING)
            onStop()
            setStatus(ModuleStatus.STOPPED)
        } catch (e: Exception) {
            setStatus(ModuleStatus.ERROR)
            throw ModuleException(moduleId, "Failed to stop", e)
        }
    }

    override suspend fun dispose() {
        try {
            if (_status == ModuleStatus.RUNNING) {
                stop()
            }
            onDispose()
            setStatus(ModuleStatus.DISPOSED)
        } catch (e: Exception) {
            setStatus(ModuleStatus.ERROR)
            throw ModuleException(moduleId, "Failed to dispose", e)
        }
    }

    protected fun setStatus(newStatus: ModuleStatus) {
        _status = newStatus
    }

    protected abstract suspend fun onInitialize()
    protected abstract suspend fun onStart()
    protected abstract suspend fun onStop()
    protected abstract suspend fun onDispose()
}
