# HSI Android Architecture

This document describes the architecture of the Human State Interface (HSI) Android/Kotlin implementation, based on the RFC.

## Overview

HSI Android implements a layered architecture where:

1. **HSI Core (State Engine)** processes raw signals and produces base HSV
2. **Emotion Head** (using `synheart_emotion` module) populates emotion state
3. **Focus Head** (using `synheart_focus` module) populates focus state
4. **Final HSV** is emitted to subscribers via Kotlin Flow

## Architecture Layers

### 1. HSI Core (`src/main/kotlin/com/synheart/hsi/core/`)

#### State Engine (`StateEngine.kt`)
- Orchestrates ingestion, processing, and fusion
- Produces base HSV Flow (`StateFlow<HSV>`)
- Manages lifecycle (start/stop)
- Uses Kotlin Coroutines for async processing
- Lifecycle-aware component (integrates with Android Lifecycle)

#### Ingestion Service (`IngestionService.kt`)
- Android Service or WorkManager for background collection
- Collects signals from:
  - Synheart Wear SDK/Service (HR, HRV, motion, sleep)
  - Synheart Phone SDK (typing, scrolling, app switches)
  - Context Adapters (conversation timing, device state, user patterns)
- Emits raw `SignalData` via Flow
- Uses Android Sensor APIs, AccessibilityService, or custom SDKs

#### Signal Processor (`SignalProcessor.kt`)
- Synchronization and windowing
- Noise reduction and artifact handling
- Vendor-agnostic normalization
- Baseline alignment
- Calculates derived metrics (RMSSD, SDNN, burstiness indices)
- Processes signals in background coroutines

#### Fusion Engine (`FusionEngine.kt`)
- Computes low-level derived metrics
- Generates `hsi_embedding` (latent representation)
- Creates base HSV from processed signals
- May use TensorFlow Lite or ML Kit for on-device inference

### 2. Model Heads (`src/main/kotlin/com/synheart/hsi/heads/`)

#### Emotion Head (`EmotionHead.kt`)
- Subscribes to base HSV Flow from State Engine
- Extracts features from HSV
- Calls `synheart_emotion` module/library to predict emotion
- Populates `hsv.emotion` with:
  - stress, calm, engagement, activation, valence
- Emits HSV with emotion populated via Flow

#### Focus Head (`FocusHead.kt`)
- Subscribes to emotion Flow (or base HSV Flow)
- Extracts features from HSV (including emotion)
- Calls `synheart_focus` module/library to predict focus
- Populates `hsv.focus` with:
  - score, cognitive_load, clarity, distraction
- Emits final HSV via Flow

### 3. Data Models (`src/main/kotlin/com/synheart/hsi/models/`)

#### HSV (`Hsv.kt`)
- `HumanStateVector`: Main data class (Kotlin data class)
- `MetaState`: Device, session, embeddings
- `DeviceInfo`: Platform information
- Uses Kotlin data classes with serialization support (Kotlinx Serialization)

#### Emotion (`Emotion.kt`)
- `EmotionState`: Emotion metrics data class

#### Focus (`Focus.kt`)
- `FocusState`: Focus metrics data class

#### Behavior (`Behavior.kt`)
- `BehaviorState`: Behavioral metrics data class

#### Context (`Context.kt`)
- `ContextState`: Context information data class
- `ConversationContext`: Conversation timing
- `DeviceStateContext`: Device state
- `UserPatternsContext`: User patterns

### 4. Main HSI Class (`src/main/kotlin/com/synheart/hsi/HSI.kt`)

- Singleton pattern (`HSI` object or companion object)
- Orchestrates State Engine and Heads
- Provides public API:
  - `configure(appKey: String)`: Initialize with app key
  - `start(context: Context)`: Start the pipeline
  - `stop()`: Stop the pipeline
  - `stateFlow: StateFlow<HSV>`: Flow of final HSV
  - `currentState: HSV?`: Latest HSV (nullable)
  - `enableCloudSync()`: Enable cloud sync (future)
- Integrates with Android Application lifecycle

## Data Flow

```
Raw Signals (Wear SDK, Phone SDK, Context)
    ↓
Ingestion Service
    ↓
Signal Processor (normalization, cleaning)
    ↓
Fusion Engine (hsi_embedding, base HSV)
    ↓
Emotion Head (synheart_emotion) → HSV with emotion
    ↓
Focus Head (synheart_focus) → Final HSV
    ↓
Subscribers (apps, Syni LLM layer)
```

## Integration Points

### synheart_emotion Module/Library
- Expected interface: `EmotionModel.predict(features: Map<String, Float>): Map<String, Float>`
- Features extracted from HSV: hsi_embedding, HR, HRV, behavioral metrics, context
- Returns: stress, calm, engagement, activation, valence
- May use TensorFlow Lite, ML Kit, or custom native model
- Runs inference on background thread/coroutine

### synheart_focus Module/Library
- Expected interface: `FocusModel.predict(features: Map<String, Float>): Map<String, Float>`
- Features extracted from HSV: hsi_embedding, behavioral metrics, emotion state
- Returns: score, cognitive_load, clarity, distraction
- May use TensorFlow Lite, ML Kit, or custom native model
- Runs inference on background thread/coroutine

## Android-Specific Considerations

### Background Processing
- Use WorkManager for periodic background tasks
- Use Foreground Service for continuous signal collection (with notification)
- Consider battery optimization exemptions
- Handle Doze mode and App Standby

### Lifecycle Management
- Integrate with Android Lifecycle components
- Handle configuration changes (retain StateFlow)
- Proper cleanup on app termination

### Permissions
- Health data permissions (if using Health Connect API)
- Sensor permissions
- Background location (if needed for context)
- Notification permissions (for foreground service)

## Next Steps

1. **Connect to actual SDKs**:
   - Integrate with Synheart Wear SDK/Service (via Wear OS or companion app)
   - Integrate with Synheart Phone SDK
   - Implement Context Adapters (AccessibilityService, UsageStatsManager)

2. **Implement fusion model**:
   - Replace placeholder embedding with actual Tiny Transformer or CNN-LSTM
   - Train model to fuse biosignals, behavior, and context
   - Convert to TensorFlow Lite format for on-device inference

3. **Integrate model modules**:
   - Implement `synheart_emotion` Android library/module
   - Implement `synheart_focus` Android library/module
   - Adjust feature extraction based on model requirements
   - Optimize model loading and inference

4. **Cloud sync**:
   - Implement `enableCloudSync()` method
   - Use WorkManager for sync jobs
   - Ensure only aggregated HSV is synced (no raw biosignals)
   - Implement encryption for sensitive data

5. **Testing**:
   - Unit tests for processors (JUnit, MockK)
   - Integration tests for full pipeline (AndroidJUnitRunner)
   - Mock SDKs for testing
   - UI tests for integration (Espresso)

6. **Performance optimization**:
   - Optimize sampling rates
   - Optimize model inference (use GPU delegate if available)
   - Battery usage considerations
   - Memory management (avoid leaks with Flow subscriptions)

## Privacy & Security

- All processing is on-device by default
- No raw biosignals stored or transmitted
- Cloud sync only for aggregated HSV (with consent)
- Non-medical use only

