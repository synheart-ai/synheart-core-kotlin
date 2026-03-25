package ai.synheart.core.modules.base

/// Module lifecycle status
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

/// Base interface for all Synheart modules
///
/// Each module (Wear, Phone, Behavior, HSI Runtime, etc.) implements this interface
/// to ensure consistent lifecycle management.
interface SynheartModule {
    /// Module identifier
    val moduleId: String

    /// Current module status
    val status: ModuleStatus

    /// Whether this module is currently enabled
    val isEnabled: Boolean

    /// Initialize the module with required dependencies
    suspend fun initialize()

    /// Start the module's operation
    suspend fun start()

    /// Stop the module's operation (can be restarted)
    suspend fun stop()

    /// Dispose of all resources (final cleanup)
    suspend fun dispose()
}

/// Module exception
class ModuleException(
    val moduleId: String,
    message: String,
    cause: Throwable? = null
) : Exception("ModuleException [$moduleId]: $message", cause)

/// Base implementation of SynheartModule with common functionality
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

    // MARK: - Protected methods for subclasses

    /// Set the module status
    protected fun setStatus(newStatus: ModuleStatus) {
        _status = newStatus
    }

    /// Called during initialization - override in subclass
    protected abstract suspend fun onInitialize()

    /// Called when starting the module - override in subclass
    protected abstract suspend fun onStart()

    /// Called when stopping the module - override in subclass
    protected abstract suspend fun onStop()

    /// Called during disposal - override in subclass
    protected abstract suspend fun onDispose()
}
