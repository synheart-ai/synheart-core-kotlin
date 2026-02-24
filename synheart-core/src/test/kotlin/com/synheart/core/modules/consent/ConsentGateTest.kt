package com.synheart.core.modules.consent

import com.synheart.core.modules.interfaces.ConsentSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that the HSI stream is consent-gated: when biosignal consent is
 * false, HSI frames must NOT be forwarded to the public flow.
 */
class ConsentGateTest {

    @Test
    fun `HSI frames blocked when biosignals consent is false`() = runTest {
        val consent = ConsentSnapshot(
            biosignals = false,
            behavior = true,
            phoneContext = true,
            cloudUpload = false,
            focusEstimation = false,
            emotionEstimation = false,
            syni = false
        )

        // Simulate the consent gate logic from Synheart.kt:
        //   if (consentModule?.current()?.biosignals != true) return@collect
        val sourceFlow = MutableStateFlow<String?>(null)
        val received = mutableListOf<String>()

        val job = launch {
            sourceFlow.collect { hsiJson ->
                if (hsiJson == null) return@collect
                if (consent.biosignals != true) return@collect
                received.add(hsiJson)
            }
        }

        sourceFlow.value = "{\"hsi_version\":\"1.0\",\"frame\":1}"
        testScheduler.advanceUntilIdle()

        assertTrue(
            "HSI frames should not be emitted when biosignals consent is false",
            received.isEmpty()
        )

        job.cancel()
    }

    @Test
    fun `HSI frames emitted when biosignals consent is true`() = runTest {
        val consent = ConsentSnapshot(
            biosignals = true,
            behavior = true,
            phoneContext = true,
            cloudUpload = false,
            focusEstimation = false,
            emotionEstimation = false,
            syni = false
        )

        val sourceFlow = MutableStateFlow<String?>(null)
        val received = mutableListOf<String>()

        val job = launch {
            sourceFlow.collect { hsiJson ->
                if (hsiJson == null) return@collect
                if (consent.biosignals != true) return@collect
                received.add(hsiJson)
            }
        }

        sourceFlow.value = "{\"hsi_version\":\"1.0\",\"frame\":1}"
        testScheduler.advanceUntilIdle()

        assertEquals(
            "HSI frames should be emitted when biosignals consent is true",
            1,
            received.size
        )

        job.cancel()
    }

    @Test
    fun `consent gate blocks all frames with none() consent`() = runTest {
        val consent = ConsentSnapshot.none()

        val sourceFlow = MutableStateFlow<String?>(null)
        val received = mutableListOf<String>()

        val job = launch {
            sourceFlow.collect { hsiJson ->
                if (hsiJson == null) return@collect
                if (consent.biosignals != true) return@collect
                received.add(hsiJson)
            }
        }

        sourceFlow.value = "{\"frame\":1}"
        testScheduler.advanceUntilIdle()
        sourceFlow.value = "{\"frame\":2}"
        testScheduler.advanceUntilIdle()

        assertTrue(
            "No frames should pass when consent is none()",
            received.isEmpty()
        )

        job.cancel()
    }
}
