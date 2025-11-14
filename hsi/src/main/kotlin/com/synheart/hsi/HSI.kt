package com.synheart.hsi

import android.content.Context
import android.content.Intent
import com.synheart.hsi.core.FusionEngine
import com.synheart.hsi.core.IngestionService
import com.synheart.hsi.core.SignalProcessor
import com.synheart.hsi.core.StateEngine
import com.synheart.hsi.heads.EmotionHead
import com.synheart.hsi.heads.FocusHead
import com.synheart.hsi.models.HumanStateVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Main HSI class - Singleton entry point for Human State Interface
 * 
 * Usage:
 * ```
 * HSI.configure("your-app-key")
 * HSI.start(context)
 * HSI.stateFlow.collect { hsv ->
 *     // Handle HSV updates
 * }
 * ```
 */
object HSI {
    
    private val hsiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var appKey: String? = null
    private var context: Context? = null
    private var sessionId: String = UUID.randomUUID().toString()
    
    // Core components
    private var ingestionService: IngestionService? = null
    private var signalProcessor: SignalProcessor? = null
    private var fusionEngine: FusionEngine? = null
    private var stateEngine: StateEngine? = null
    
    // Model heads
    private var emotionHead: EmotionHead? = null
    private var focusHead: FocusHead? = null
    
    // State flow for final HSV
    private val _stateFlow = MutableStateFlow<HumanStateVector?>(null)
    val stateFlow: StateFlow<HumanStateVector?> = _stateFlow.asStateFlow()
    
    private var isInitialized = false
    private var isRunning = false
    
    /**
     * Configure HSI with app key
     */
    fun configure(appKey: String) {
        this.appKey = appKey
    }
    
    /**
     * Start the HSI pipeline
     */
    fun start(context: Context) {
        if (isRunning) {
            return
        }
        
        if (appKey == null) {
            throw IllegalStateException("HSI must be configured with appKey before starting")
        }
        
        this.context = context.applicationContext
        initializeComponents()
        startPipeline()
        isRunning = true
    }
    
    /**
     * Stop the HSI pipeline
     */
    fun stop() {
        if (!isRunning) return
        
        stateEngine?.stop()
        emotionHead?.cleanup()
        focusHead?.cleanup()
        
        context?.let { ctx ->
            val serviceIntent = Intent(ctx, IngestionService::class.java)
            ctx.stopService(serviceIntent)
        }
        
        isRunning = false
    }
    
    /**
     * Get current HSV state
     */
    val currentState: HumanStateVector?
        get() = _stateFlow.value
    
    /**
     * Enable cloud sync (future feature)
     */
    fun enableCloudSync() {
        // TODO: Implement cloud sync
        throw NotImplementedError("Cloud sync not yet implemented")
    }
    
    /**
     * Initialize all components
     */
    private fun initializeComponents() {
        val ctx = context ?: return
        
        // Initialize core components
        ingestionService = IngestionService()
        signalProcessor = SignalProcessor()
        fusionEngine = FusionEngine(sessionId)
        
        ingestionService?.let { ingestion ->
            signalProcessor?.let { processor ->
                fusionEngine?.let { fusion ->
                    stateEngine = StateEngine(ingestion, processor, fusion)
                }
            }
        }
        
        // Initialize model heads
        emotionHead = EmotionHead().apply {
            initializeModel()
        }
        focusHead = FocusHead().apply {
            initializeModel()
        }
        
        // Start ingestion service
        val serviceIntent = Intent(ctx, IngestionService::class.java)
        ctx.startForegroundService(serviceIntent)
        
        isInitialized = true
    }
    
    /**
     * Start the processing pipeline
     */
    private fun startPipeline() {
        val stateEngine = this.stateEngine ?: return
        val emotionHead = this.emotionHead ?: return
        val focusHead = this.focusHead ?: return

        stateEngine.start()

        hsiScope.launch {
            try {
                // Pipeline: Base HSV -> Emotion Head -> Focus Head -> Final HSV
                stateEngine.baseHsvFlow
                    .collect { baseHsv ->
                        if (baseHsv != null) {
                            try {
                                val hsvWithEmotion = emotionHead.processOne(baseHsv)
                                val finalHsv = focusHead.processOne(hsvWithEmotion)
                                _stateFlow.value = finalHsv
                            } catch (e: Exception) {
                                // Log error but continue processing
                                android.util.Log.e("HSI", "Error processing HSV", e)
                            }
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.e("HSI", "Pipeline error", e)
            }
        }
    }
    
    /**
     * Check if HSI is running
     */
    fun isRunning(): Boolean = isRunning
}

