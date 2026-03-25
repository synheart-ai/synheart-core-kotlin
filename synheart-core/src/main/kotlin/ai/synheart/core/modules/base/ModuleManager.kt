package ai.synheart.core.modules.base

/// Manages the lifecycle of all Synheart modules
///
/// Responsibilities:
/// - Initialize modules in correct order
/// - Handle module dependencies
/// - Coordinate module lifecycle
/// - Handle errors and recovery
class ModuleManager {
    private val modules = mutableMapOf<String, SynheartModule>()
    private val dependencies = mutableMapOf<String, List<String>>()
    private var isInitialized = false

    /// Register a module with optional dependencies
    fun registerModule(module: SynheartModule, dependsOn: List<String> = emptyList()) {
        if (modules.containsKey(module.moduleId)) {
            throw ModuleException(module.moduleId, "Module already registered")
        }

        modules[module.moduleId] = module
        if (dependsOn.isNotEmpty()) {
            dependencies[module.moduleId] = dependsOn
        }
    }

    /// Get a module by ID
    @Suppress("UNCHECKED_CAST")
    fun <T : SynheartModule> getModule(moduleId: String): T? {
        return modules[moduleId] as? T
    }

    /// Initialize all registered modules in dependency order
    suspend fun initializeAll() {
        if (isInitialized) {
            throw IllegalStateException("Modules already initialized")
        }

        val initOrder = resolveInitializationOrder()

        for (moduleId in initOrder) {
            modules[moduleId]?.initialize()
        }

        isInitialized = true
    }

    /// Start all modules in dependency order
    suspend fun startAll() {
        if (!isInitialized) {
            throw IllegalStateException("Modules must be initialized before starting")
        }

        val startOrder = resolveInitializationOrder()

        for (moduleId in startOrder) {
            val module = modules[moduleId]
            if (module?.status == ModuleStatus.INITIALIZED) {
                module.start()
            }
        }
    }

    /// Stop all modules in reverse dependency order
    suspend fun stopAll() {
        val stopOrder = resolveInitializationOrder().reversed()

        for (moduleId in stopOrder) {
            val module = modules[moduleId]
            if (module?.status == ModuleStatus.RUNNING) {
                try {
                    module.stop()
                } catch (e: Exception) {
                    // Log error but continue stopping other modules
                    android.util.Log.e("ModuleManager", "Error stopping module $moduleId", e)
                }
            }
        }
    }

    /// Dispose all modules in reverse dependency order
    suspend fun disposeAll() {
        val disposeOrder = resolveInitializationOrder().reversed()

        for (moduleId in disposeOrder) {
            val module = modules[moduleId]
            try {
                module?.dispose()
            } catch (e: Exception) {
                // Log error but continue disposing other modules
                android.util.Log.e("ModuleManager", "Error disposing module $moduleId", e)
            }
        }

        modules.clear()
        dependencies.clear()
        isInitialized = false
    }

    /// Get status of all modules
    fun getModuleStatuses(): Map<String, ModuleStatus> {
        return modules.mapValues { it.value.status }
    }

    // MARK: - Private Methods

    /// Resolve the initialization order based on dependencies
    private fun resolveInitializationOrder(): List<String> {
        val order = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(moduleId: String) {
            if (visited.contains(moduleId)) {
                return
            }

            if (visiting.contains(moduleId)) {
                throw IllegalStateException("Circular dependency detected for module: $moduleId")
            }

            visiting.add(moduleId)

            // Visit dependencies first
            dependencies[moduleId]?.forEach { dep ->
                if (!modules.containsKey(dep)) {
                    throw IllegalStateException(
                        "Module $moduleId depends on $dep, but $dep is not registered"
                    )
                }
                visit(dep)
            }

            visiting.remove(moduleId)
            visited.add(moduleId)
            order.add(moduleId)
        }

        // Visit all modules
        modules.keys.forEach { visit(it) }

        return order
    }
}
