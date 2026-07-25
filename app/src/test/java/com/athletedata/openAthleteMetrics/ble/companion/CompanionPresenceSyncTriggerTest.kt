package com.athletedata.openAthleteMetrics.ble.companion

import com.athletedata.openAthleteMetrics.ble.sync.MultiDeviceSyncOrchestrator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPresenceSyncTriggerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var orchestrator: MultiDeviceSyncOrchestrator
    private lateinit var trigger: CompanionPresenceSyncTrigger

    @Before
    fun setUp() {
        // CompanionPresenceSyncTrigger's own scope resolves Dispatchers.Main.immediate at
        // construction time, so Main must be set before building it (same convention as
        // MultiDeviceSyncOrchestratorTest).
        Dispatchers.setMain(testDispatcher)
        orchestrator = mockk(relaxed = true) {
            coEvery { runIfIdle() } returns true
        }
        trigger = CompanionPresenceSyncTrigger(orchestrator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onDevicePresent calls the orchestrator's shared gate`() = runTest(testDispatcher) {
        trigger.onDevicePresent()
        advanceUntilIdle()

        coVerify(exactly = 1) { orchestrator.runIfIdle() }
    }
}
