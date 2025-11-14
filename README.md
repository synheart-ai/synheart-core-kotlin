# HSI Android SDK

Human State Interface (HSI) Android SDK - A Kotlin library for inferring human state from biosignals, behavior, and context.

## Overview

HSI Android implements a layered architecture that processes raw signals from various sources (wearables, phone sensors, user interactions) and produces a `HumanStateVector` (HSV) containing emotion, focus, behavior, and context information.

## Architecture

The SDK follows a pipeline architecture:

```
Raw Signals → Ingestion Service → Signal Processor → Fusion Engine → Base HSV
                                                                    ↓
Final HSV ← Focus Head ← Emotion Head ←──────────────────────────────┘
```

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.

## Features

- **Signal Processing**: Synchronization, normalization, noise reduction
- **Fusion Engine**: Combines biosignals, behavior, and context into unified state
- **Emotion Head**: Predicts emotion state (stress, calm, engagement, activation, valence)
- **Focus Head**: Predicts focus state (score, cognitive load, clarity, distraction)
- **Background Processing**: Android Service for continuous signal collection
- **Kotlin Flow API**: Reactive state updates using Kotlin Coroutines

## Setup

### Add to your project

Add the library to your `build.gradle`:

```gradle
dependencies {
    implementation project(':hsi')
    // Or if published to Maven:
    // implementation 'com.synheart:hsi:1.0.0'
}
```

### Permissions

Add required permissions to your `AndroidManifest.xml`:

```xml
<!-- Health data permissions (if using Health Connect) -->
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_STEPS" />

<!-- Foreground service permission -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Usage

### Basic Usage

```kotlin
import com.synheart.hsi.HSI
import com.synheart.hsi.models.HumanStateVector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure HSI with your app key
        HSI.configure("your-app-key")
        
        // Start HSI
        HSI.start(this)
        
        // Observe state updates
        lifecycleScope.launch {
            HSI.stateFlow.collect { hsv ->
                hsv?.let { updateUI(it) }
            }
        }
    }
    
    private fun updateUI(hsv: HumanStateVector) {
        // Access emotion state
        hsv.emotion?.let { emotion ->
            val stress = emotion.stress
            val engagement = emotion.engagement
            // Update UI...
        }
        
        // Access focus state
        hsv.focus?.let { focus ->
            val score = focus.score
            val cognitiveLoad = focus.cognitiveLoad
            // Update UI...
        }
        
        // Access behavior
        hsv.behavior?.let { behavior ->
            val typingSpeed = behavior.typingSpeed
            val engagementLevel = behavior.engagementLevel
            // Update UI...
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop HSI when done
        HSI.stop()
    }
}
```

### Access Current State

```kotlin
val currentState = HSI.currentState
currentState?.emotion?.stress?.let { stressLevel ->
    // Handle stress level
}
```

### Lifecycle Integration

HSI integrates with Android lifecycle. The service will continue running in the background even when your activity is paused.

## Data Models

### HumanStateVector

The main data structure containing all state information:

```kotlin
data class HumanStateVector(
    val timestamp: Long,
    val meta: MetaState,
    val heartRate: Float?,
    val hrv: Float?,
    val hrvSdnn: Float?,
    val hsiEmbedding: List<Float>,
    val emotion: EmotionState?,
    val focus: FocusState?,
    val behavior: BehaviorState?,
    val context: ContextState?
)
```

### EmotionState

```kotlin
data class EmotionState(
    val stress: Float,      // 0.0 - 1.0
    val calm: Float,         // 0.0 - 1.0
    val engagement: Float,  // 0.0 - 1.0
    val activation: Float,  // 0.0 - 1.0
    val valence: Float      // -1.0 to 1.0
)
```

### FocusState

```kotlin
data class FocusState(
    val score: Float,           // 0.0 - 1.0
    val cognitiveLoad: Float,   // 0.0 - 1.0
    val clarity: Float,          // 0.0 - 1.0
    val distraction: Float       // 0.0 - 1.0
)
```

## Integration Points

### SDK Integration

The SDK is designed to integrate with:

- **Synheart Wear SDK/Service**: For heart rate, HRV, motion, sleep data
- **Synheart Phone SDK**: For typing, scrolling, app switch detection
- **Context Adapters**: For conversation timing, device state, user patterns

### Model Integration

The emotion and focus heads are designed to integrate with:

- **synheart_emotion**: Emotion prediction model (TensorFlow Lite)
- **synheart_focus**: Focus prediction model (TensorFlow Lite)

Currently, placeholder implementations are used. Replace with actual model integrations.

## Development

### Building

```bash
./gradlew build
```

### Testing

```bash
./gradlew test
```

## Privacy & Security

- All processing is **on-device by default**
- **No raw biosignals** are stored or transmitted
- Cloud sync (when implemented) will only sync aggregated HSV with user consent
- Non-medical use only

## Next Steps

1. **Connect to actual SDKs**: Integrate with Synheart Wear SDK and Phone SDK
2. **Implement fusion model**: Replace placeholder embedding with actual Tiny Transformer or CNN-LSTM
3. **Integrate model packages**: Connect synheart_emotion and synheart_focus modules
4. **Cloud sync**: Implement cloud sync functionality (optional, with consent)

## License

[Add your license here]

## Author

Israel Goytom

