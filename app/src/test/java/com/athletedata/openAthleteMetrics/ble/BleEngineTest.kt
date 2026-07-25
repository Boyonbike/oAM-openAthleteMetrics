package com.athletedata.openAthleteMetrics.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.driver.AwaitEndOfStream
import com.athletedata.openAthleteMetrics.ble.driver.BleConfig
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.EndOfStreamStrategy
import com.athletedata.openAthleteMetrics.ble.driver.MatchConfidence
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode
import com.athletedata.openAthleteMetrics.ble.driver.SyncCommand
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.ble.sync.DeviceSyncProcessor
import com.athletedata.openAthleteMetrics.ble.sync.MetricRouter
import com.athletedata.openAthleteMetrics.ble.sync.SyncSummary
import com.athletedata.openAthleteMetrics.ble.sync.SyncValidator
import com.athletedata.openAthleteMetrics.ble.sync.ValidationResult
import com.athletedata.openAthleteMetrics.ble.wasm.SessionFrame
import com.athletedata.openAthleteMetrics.ble.wasm.WasmParseResult
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricStatsBackfillCoordinator
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BleEngineTest {

    private lateinit var driverRegistry: DriverRegistry
    private lateinit var syncContextFactory: SyncContextFactory
    private lateinit var syncProcessor: DeviceSyncProcessor
    private lateinit var validator: SyncValidator
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var rawDeviceDataRepository: RawDeviceDataRepository
    private lateinit var metricRouter: MetricRouter
    private lateinit var workManager: WorkManager
    private lateinit var metricStatsBackfillCoordinator: MetricStatsBackfillCoordinator
    private lateinit var engine: BleEngine
    // CHANGED: named (not anonymous) so tests can drive its virtual clock directly via
    // testDispatcher.scheduler.advanceTimeBy(...) + runCurrent().
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        // BleEngine's `scope` field captures Dispatchers.Main.immediate at construction
        // time, so the Main dispatcher must be installed before the engine is built.
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        driverRegistry = mockk(relaxed = true)
        syncContextFactory = mockk(relaxed = true)
        syncProcessor = mockk(relaxed = true)
        validator = mockk(relaxed = true)
        deviceRepository = mockk(relaxed = true)
        rawDeviceDataRepository = mockk(relaxed = true)
        metricRouter = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        metricStatsBackfillCoordinator = mockk(relaxed = true)

        engine = BleEngine(
            context = mockk<Context>(relaxed = true),
            driverRegistry = driverRegistry,
            syncContextFactory = syncContextFactory,
            syncProcessor = syncProcessor,
            validator = validator,
            deviceRepository = deviceRepository,
            rawDeviceDataRepository = rawDeviceDataRepository,
            metricRouter = metricRouter,
            workManager = workManager,
            metricStatsBackfillCoordinator = metricStatsBackfillCoordinator,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testManifest() = WasmDriverManifest(
        id = "test-driver",
        displayName = "Test",
        version = "1.0.0",
        author = "test",
        supportedMetrics = listOf(MetricType.HR),
        ble = BleConfig(
            matchConfidence = MatchConfidence.CERTAIN,
            services = listOf("0000180d-0000-1000-8000-00805f9b34fb"),
            characteristics = mapOf(
                "write" to "00002a39-0000-1000-8000-00805f9b34fb",
                "notify" to "00002a37-0000-1000-8000-00805f9b34fb",
            ),
        ),
        parsing = ParsingConfig.WasmParsing(
            wasmBytes = ByteArray(0),
            exports = WasmExports(parseMetrics = "parseMetrics"),
        ),
        stepsMode = StepsMode.DELTA,
    )

    private fun setPrivateField(name: String, value: Any?) {
        val field = BleEngine::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(engine, value)
    }

    // CHANGED: reflection getter, symmetric to setPrivateField — used by the timer-gating
    // regression tests below to read back quiescenceJob/silentSyncTimeoutJob/isQuiescent.
    private fun getPrivateField(name: String): Any? {
        val field = BleEngine::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(engine)
    }

    // CHANGED: reads the real STREAM_QUIESCENCE_MS companion constant so tests can't
    // silently drift from the production delay value.
    private fun streamQuiescenceMs(): Long {
        val field = BleEngine::class.java.getDeclaredField("STREAM_QUIESCENCE_MS")
        field.isAccessible = true
        return field.get(null) as Long
    }

    // CHANGED: polls a private field until it satisfies [predicate] — handleNotification's
    // job assignments happen on the real Dispatchers.IO consumer coroutine (BleEngine.kt:204-210),
    // not the test thread, so reading a job field back after invokeOnCharacteristicChanged
    // requires polling rather than a synchronous read (same rationale as awaitSessionFrameCount).
    private fun awaitPrivateField(name: String, timeoutMs: Long = 2_000, predicate: (Any?) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(getPrivateField(name))) return true
            Thread.sleep(10)
        }
        return predicate(getPrivateField(name))
    }

    @Suppress("UNCHECKED_CAST")
    private fun addSessionFrame(frame: SessionFrame) {
        val field = BleEngine::class.java.getDeclaredField("sessionCache")
        field.isAccessible = true
        (field.get(engine) as MutableList<SessionFrame>).add(frame)
    }

    private fun invokeDispatchPostStreamParse() {
        val method = BleEngine::class.java.getDeclaredMethod("dispatchPostStreamParse")
        method.isAccessible = true
        method.invoke(engine)
    }

    // reflects into the private gattCallback field and invokes its pre-Tiramisu
    // onCharacteristicChanged(gatt, characteristic) overload directly, mirroring the
    // reflection style already used above for private fields/methods.
    private fun invokeOnCharacteristicChanged(characteristicUuid: String, bytes: ByteArray) {
        val callbackField = BleEngine::class.java.getDeclaredField("gattCallback")
        callbackField.isAccessible = true
        val callback = callbackField.get(engine)
        val method = callback.javaClass.getDeclaredMethod(
            "onCharacteristicChanged",
            BluetoothGatt::class.java,
            BluetoothGattCharacteristic::class.java,
        )
        method.isAccessible = true
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { characteristic.value } returns bytes
        every { characteristic.uuid } returns UUID.fromString(characteristicUuid)
        method.invoke(callback, mockk<BluetoothGatt>(relaxed = true), characteristic)
    }

    // notificationChannel has buffer capacity 512, so tryReceive() is synchronous —
    // safe to use only when nothing was ever sent (asserting absence), since BleEngine's own
    // real Dispatchers.IO consumer coroutine (started in init{}) is racing to drain the
    // channel concurrently with the test thread whenever something *was* sent.
    @Suppress("UNCHECKED_CAST")
    private fun tryReceiveNotification(): Pair<String, ByteArray>? {
        val field = BleEngine::class.java.getDeclaredField("notificationChannel")
        field.isAccessible = true
        val channel = field.get(engine) as Channel<Pair<String, ByteArray>>
        return channel.tryReceive().getOrNull()
    }

    // polls sessionCache instead of racing the live notificationChannel consumer —
    // a forwarded notification is drained and processed by BleEngine's real IO coroutine
    // asynchronously, so its effect must be awaited rather than read back synchronously.
    @Suppress("UNCHECKED_CAST")
    private fun awaitSessionFrameCount(expected: Int, timeoutMs: Long = 2_000): Boolean {
        val field = BleEngine::class.java.getDeclaredField("sessionCache")
        field.isAccessible = true
        val cache = field.get(engine) as MutableList<SessionFrame>
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(cache) { if (cache.size >= expected) return true }
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun `dispatchPostStreamParse enqueues the summary worker only after syncProcessor process completes`() {
        val reading = MetricReading(
            metricType = MetricType.HR,
            value = 60.0,
            unit = "bpm",
            recordedAt = Instant.parse("2026-06-15T12:00:00Z"),
            createdAt = Instant.now(),
            source = DataSource.DEVICE,
            driverId = "test-driver",
        )
        coEvery { driverRegistry.parseSession(any(), any()) } returns WasmParseResult.Success(listOf(reading))
        every { validator.validateReadings(any()) } returns listOf(ValidationResult.Accepted(reading))
        coEvery { syncProcessor.process(any(), any()) } returns mockk<SyncSummary>(relaxed = true)

        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        addSessionFrame(SessionFrame("notify", "0x01", byteArrayOf(1, 2, 3)))

        val enqueueLatch = CountDownLatch(1)
        every {
            workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } answers {
            enqueueLatch.countDown()
            mockk(relaxed = true)
        }

        invokeDispatchPostStreamParse()

        assertTrue("enqueueUniqueWork was not called", enqueueLatch.await(5, TimeUnit.SECONDS))
        coVerifyOrder {
            syncProcessor.process(any(), any())
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
        // CHANGED: downstream effects must fire exactly once via the EOS path — no
        // double-enqueue from a legacy timer that should never have been running alongside it.
        verify(exactly = 1) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    // notify characteristic UUID from testManifest()'s BleConfig.
    private val notifyCharacteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb"

    @Test
    fun `onCharacteristicChanged excludes a SentinelPacket terminator from the notification channel`() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1_000L

            val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 255, matchOpcode = true)
            strategy.onStreamStart(0x66)
            setPrivateField("awaitEndOfStreamStrategy", strategy)
            setPrivateField("awaitEndOfStreamCharUuid", notifyCharacteristicUuid)
            setPrivateField("awaitEndOfStreamArmedAtMs", 0L)
            val deferred = CompletableDeferred<Unit>()
            setPrivateField("awaitEndOfStreamDeferred", deferred)

            invokeOnCharacteristicChanged(notifyCharacteristicUuid, byteArrayOf(0x66, 0xFF.toByte()))

            assertTrue("awaitEndOfStreamDeferred should have completed", deferred.isCompleted)
            assertNull(
                "the standalone [opcode,0xFF] sentinel must not reach the notification channel",
                tryReceiveNotification(),
            )
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun `onCharacteristicChanged still forwards normal records to the notification channel`() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1_000L

            val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 255, matchOpcode = true)
            strategy.onStreamStart(0x66)
            setPrivateField("awaitEndOfStreamStrategy", strategy)
            setPrivateField("awaitEndOfStreamCharUuid", notifyCharacteristicUuid)
            setPrivateField("awaitEndOfStreamArmedAtMs", 0L)
            setPrivateField("awaitEndOfStreamDeferred", CompletableDeferred<Unit>())
            // A real manifest so BleEngine's IO consumer coroutine processes the forwarded
            // notification instead of bailing out at handleNotification's first line.
            setPrivateField("activeManifest", testManifest())

            // Realistic-shaped record (not the 2-byte sentinel) — must still flow through.
            val record = byteArrayOf(0x66, 0x01, 0x00, 0x50, 0x02, 0x03)
            invokeOnCharacteristicChanged(notifyCharacteristicUuid, record)

            assertTrue(
                "a normal record must still reach handleNotification (observed via sessionCache)",
                awaitSessionFrameCount(1),
            )
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    // regression test for the SENTINEL_PACKET -> IN_STREAM_TERMINATOR fix: a real device
    // capture showed Hume Band's terminator is the tail of its LAST DATA notification
    // ([opcode,0xFF] appended after real payload bytes), not a standalone 2-byte sentinel.
    // Unlike SentinelPacket (excluded wholesale, see the test above), InStreamTerminator's
    // matched notification must still flow through whole — including the terminator
    // suffix — since it carries real data that would otherwise be silently dropped.
    @Test
    fun `onCharacteristicChanged forwards a realistic InStreamTerminator notification whole, including its trailing terminator bytes`() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1_000L

            val strategy = EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(0x66)
            setPrivateField("awaitEndOfStreamStrategy", strategy)
            setPrivateField("awaitEndOfStreamCharUuid", notifyCharacteristicUuid)
            setPrivateField("awaitEndOfStreamArmedAtMs", 0L)
            val deferred = CompletableDeferred<Unit>()
            setPrivateField("awaitEndOfStreamDeferred", deferred)
            setPrivateField("activeManifest", testManifest())
            // real devices negotiate MTU 512 (see BleEngine's connection setup); without this,
            // handleNotification's fragment-reassembly gate (bytes.size >= negotiatedMtu - 3)
            // would misread this realistically-sized record as a non-terminal BLE fragment.
            setPrivateField("negotiatedMtu", 512)

            // Realistic-shaped: several real SpO2-record-like bytes, then the trailing
            // [0x66, 0xFF] tail — NOT a toy 2-3 byte array, and NOT the idealized
            // standalone-sentinel shape SentinelPacket requires.
            val recordBytes = byteArrayOf(0x66) + ByteArray(30) { (it + 1).toByte() } + byteArrayOf(0x66, 0xFF.toByte())
            invokeOnCharacteristicChanged(notifyCharacteristicUuid, recordBytes)

            assertTrue("awaitEndOfStreamDeferred should have completed", deferred.isCompleted)
            assertTrue(
                "the full notification, including its trailing terminator tail, must still reach handleNotification — unlike SentinelPacket's exclusion",
                awaitSessionFrameCount(1),
            )
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun `onCharacteristicChanged does not complete InStreamTerminator early when the terminator bytes appear mid-packet`() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1_000L

            val strategy = EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(0x66)
            setPrivateField("awaitEndOfStreamStrategy", strategy)
            setPrivateField("awaitEndOfStreamCharUuid", notifyCharacteristicUuid)
            setPrivateField("awaitEndOfStreamArmedAtMs", 0L)
            val deferred = CompletableDeferred<Unit>()
            setPrivateField("awaitEndOfStreamDeferred", deferred)
            setPrivateField("activeManifest", testManifest())

            // [0x66, 0xFF] appears mid-packet, followed by more real data — must not
            // falsely trigger completion (InStreamTerminator only checks the tail).
            val recordBytes = byteArrayOf(0x66, 0x01, 0x66, 0xFF.toByte(), 0x02, 0x03, 0x04)
            invokeOnCharacteristicChanged(notifyCharacteristicUuid, recordBytes)

            assertTrue(
                "a mid-packet incidental match must not resolve the stream (actual tail is 0x03,0x04, not the terminator)",
                !deferred.isCompleted,
            )
            assertTrue(
                "the record must still be forwarded normally",
                awaitSessionFrameCount(1),
            )
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    // regression test for the null-window bug — a stray/duplicate notification arriving
    // after one command's awaitEndOfStream resolves but before the next command (if any) arms
    // its own strategy used to fall through unconditionally into the notification channel,
    // since awaitEndOfStreamCharUuid was nulled at resolution and the equality check silently
    // failed. Post-fix, charUuid stays sticky and awaitEndOfStreamStrategy == null is what now
    // signals "nothing currently armed" — this reproduces exactly that field state.
    @Test
    fun `onCharacteristicChanged drops a stray notification arriving after resolution but before the next arm`() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1_000L

            setPrivateField("awaitEndOfStreamCharUuid", notifyCharacteristicUuid)
            setPrivateField("awaitEndOfStreamStrategy", null)
            setPrivateField("awaitEndOfStreamDeferred", null)
            setPrivateField("awaitEndOfStreamArmedAtMs", 0L)
            setPrivateField("activeManifest", testManifest())

            invokeOnCharacteristicChanged(notifyCharacteristicUuid, byteArrayOf(0x66, 0x01, 0x00, 0x50))

            assertNull(
                "a stray notification arriving after resolution but before the next arm must be dropped, not forwarded",
                tryReceiveNotification(),
            )
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    // CHANGED: regression tests for gating STREAM_QUIESCENCE_MS / SILENT_SYNC_TIMEOUT_MS
    // behind EOS-armed state — the legacy timers must be inert while an EOS strategy is
    // armed and must behave exactly as before when no EOS strategy is declared.

    // CHANGED: pre-fix, both legacy timers get cancelled/restarted unconditionally in
    // handleNotification regardless of EOS-armed state — this test would fail against
    // that code (verify(exactly = 0) would observe cancel() having been called).
    @Test
    fun `handleNotification leaves legacy timers untouched while an EOS strategy is armed`() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1_000L

            // Non-matching strategy shape (see "still forwards normal records" test above) —
            // the record must still flow through to handleNotification without resolving EOS.
            val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 255, matchOpcode = true)
            strategy.onStreamStart(0x66)
            setPrivateField("awaitEndOfStreamStrategy", strategy)
            setPrivateField("awaitEndOfStreamCharUuid", notifyCharacteristicUuid)
            setPrivateField("awaitEndOfStreamArmedAtMs", 0L)
            setPrivateField("awaitEndOfStreamDeferred", CompletableDeferred<Unit>())
            setPrivateField("activeManifest", testManifest())
            setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")

            val quiescenceMock = mockk<Job>(relaxed = true)
            val silentTimeoutMock = mockk<Job>(relaxed = true)
            setPrivateField("quiescenceJob", quiescenceMock)
            setPrivateField("silentSyncTimeoutJob", silentTimeoutMock)
            setPrivateField("isQuiescent", true)

            val record = byteArrayOf(0x66, 0x01, 0x00, 0x50, 0x02, 0x03)
            invokeOnCharacteristicChanged(notifyCharacteristicUuid, record)

            assertTrue("handleNotification must have run", awaitSessionFrameCount(1))
            // handleNotification's job-touching code is a few synchronous statements past
            // the sessionCache append on the same IO-consumer coroutine; give it a brief
            // moment to finish before asserting absence of a mutation (same rationale as
            // awaitSessionFrameCount's own polling above).
            Thread.sleep(50)

            assertTrue(
                "quiescenceJob must remain the pre-seeded instance while EOS is armed",
                getPrivateField("quiescenceJob") === quiescenceMock,
            )
            assertTrue(
                "silentSyncTimeoutJob must remain the pre-seeded instance while EOS is armed",
                getPrivateField("silentSyncTimeoutJob") === silentTimeoutMock,
            )
            assertTrue(
                "isQuiescent must not be reset while EOS is armed",
                getPrivateField("isQuiescent") as Boolean,
            )
            verify(exactly = 0) { quiescenceMock.cancel() }
            verify(exactly = 0) { silentTimeoutMock.cancel() }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    // CHANGED: proves the fallback path for commands with no EOS strategy is unchanged —
    // both legacy timers must still be cancelled/restarted exactly as before.
    @Test
    fun `handleNotification restarts legacy timers unchanged when no EOS strategy is armed`() {
        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        setPrivateField("awaitEndOfStreamStrategy", null)

        @Suppress("UNCHECKED_CAST")
        val connectionStateFlow = getPrivateField("_connectionState") as MutableStateFlow<BleConnectionState>
        connectionStateFlow.value = BleConnectionState.Connected("AA:BB:CC:DD:EE:FF", "Test", 0, false)

        val oldQuiescenceJob = mockk<Job>(relaxed = true)
        val oldSilentTimeoutJob = mockk<Job>(relaxed = true)
        setPrivateField("quiescenceJob", oldQuiescenceJob)
        setPrivateField("silentSyncTimeoutJob", oldSilentTimeoutJob)

        invokeOnCharacteristicChanged(notifyCharacteristicUuid, byteArrayOf(0x01, 0x02, 0x03))

        assertTrue("handleNotification must have run", awaitSessionFrameCount(1))
        Thread.sleep(50)

        verify(exactly = 1) { oldQuiescenceJob.cancel() }
        verify(exactly = 1) { oldSilentTimeoutJob.cancel() }
        val newQuiescenceJob = getPrivateField("quiescenceJob")
        assertTrue(
            "a fresh quiescenceJob must be scheduled for a non-EOS command, exactly as before",
            newQuiescenceJob != null && newQuiescenceJob !== oldQuiescenceJob,
        )
        assertNull(
            "silentSyncTimeoutJob must be nulled (not restarted) on real packet arrival, exactly as before",
            getPrivateField("silentSyncTimeoutJob"),
        )
    }

    // CHANGED: closes the stale-timer race — a quiescenceJob left running by an earlier
    // non-EOS command must be flushed the instant the next command arms its own EOS
    // strategy, since connectionState stays Connected throughout automatic sync execution
    // and would otherwise let the stale job fire mid-EOS-stream.
    @Test
    fun `executeNextSyncCommand cancels a stale quiescenceJob when arming awaitEndOfStream`() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1_000L

            val manifest = testManifest()
            setPrivateField("activeManifest", manifest)
            setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")

            val writeCommand = SyncCommand.Write(
                characteristic = "write",
                bytes = "0x01",
                awaitEndOfStream = AwaitEndOfStream(characteristic = "notify"),
            )
            setPrivateField("effectiveSyncCommands", listOf(writeCommand))
            setPrivateField("commandIndex", 0)

            val writeCharUuid = manifest.ble.characteristics.getValue("write")
            val gattCharacteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
            every { gattCharacteristic.uuid } returns UUID.fromString(writeCharUuid)
            val service = mockk<BluetoothGattService>(relaxed = true)
            every { service.characteristics } returns listOf(gattCharacteristic)
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            every { gatt.services } returns listOf(service)
            setPrivateField("activeGatt", gatt)

            val staleQuiescenceJob = mockk<Job>(relaxed = true)
            setPrivateField("quiescenceJob", staleQuiescenceJob)

            val method = BleEngine::class.java.getDeclaredMethod("executeNextSyncCommand")
            method.isAccessible = true
            method.invoke(engine)

            verify(exactly = 1) { staleQuiescenceJob.cancel() }
            assertNull(
                "quiescenceJob must be flushed the moment awaitEndOfStream is armed",
                getPrivateField("quiescenceJob"),
            )
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    // CHANGED: proves the legacy fallback path still fires its downstream effect exactly
    // once for a non-EOS command, using virtual time to let the real 3s quiescence delay
    // elapse deterministically instead of a real Thread.sleep.
    @Test
    fun `legacy quiescenceJob still enqueues the summary worker exactly once for a non-EOS command`() {
        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        setPrivateField("awaitEndOfStreamStrategy", null)

        @Suppress("UNCHECKED_CAST")
        val connectionStateFlow = getPrivateField("_connectionState") as MutableStateFlow<BleConnectionState>
        connectionStateFlow.value = BleConnectionState.Connected("AA:BB:CC:DD:EE:FF", "Test", 0, false)

        val today = java.time.LocalDate.now()
        @Suppress("UNCHECKED_CAST")
        val affectedDates = getPrivateField("affectedDates") as MutableSet<java.time.LocalDate>
        synchronized(affectedDates) { affectedDates.add(today) }

        val enqueueLatch = CountDownLatch(1)
        every {
            workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } answers {
            enqueueLatch.countDown()
            mockk(relaxed = true)
        }

        invokeOnCharacteristicChanged(notifyCharacteristicUuid, byteArrayOf(0x01, 0x02, 0x03))

        assertTrue(
            "quiescenceJob must be scheduled for a non-EOS command",
            awaitPrivateField("quiescenceJob") { it != null },
        )

        testDispatcher.scheduler.advanceTimeBy(streamQuiescenceMs() + 1)
        testDispatcher.scheduler.runCurrent()

        assertTrue("enqueueUniqueWork was not called", enqueueLatch.await(5, TimeUnit.SECONDS))
        assertTrue("isQuiescent must be set once the legacy timer fires", getPrivateField("isQuiescent") as Boolean)
        verify(exactly = 1) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    // ---------------------------------------------------------------------------
    // deviceId (physical devices.id, not the driver): triggerSync() resolves it via
    // DeviceRepository.getDeviceByAddress, and routeReading() stamps it onto every
    // MetricReading before it reaches MetricRouter.
    // ---------------------------------------------------------------------------

    @Test
    fun `triggerSync resolves the physical device id from activeDeviceAddress via DeviceRepository`() = runBlocking {
        val resolvedDevice = Device(
            id = 77L,
            bleAddress = "AA:BB:CC:DD:EE:FF",
            driverId = "test-driver",
            displayName = "Test",
        )
        coEvery { deviceRepository.getDeviceByAddress("AA:BB:CC:DD:EE:FF") } returns resolvedDevice

        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        @Suppress("UNCHECKED_CAST")
        val connectionStateFlow = getPrivateField("_connectionState") as MutableStateFlow<BleConnectionState>
        connectionStateFlow.value = BleConnectionState.Connected("AA:BB:CC:DD:EE:FF", "Test", 0, false)

        // Only the device-id resolution at the top of triggerSync() is under test here;
        // downstream sync-command execution against fully-mocked collaborators is exercised
        // elsewhere, so any exception past that point is irrelevant to this assertion.
        runCatching { engine.triggerSync() }

        coVerify { deviceRepository.getDeviceByAddress("AA:BB:CC:DD:EE:FF") }
        assertEquals(77L, getPrivateField("activeDeviceId"))
    }

    @Test
    fun `triggerSync leaves activeDeviceId null when no matching device row exists`() = runBlocking {
        coEvery { deviceRepository.getDeviceByAddress("AA:BB:CC:DD:EE:FF") } returns null

        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        @Suppress("UNCHECKED_CAST")
        val connectionStateFlow = getPrivateField("_connectionState") as MutableStateFlow<BleConnectionState>
        connectionStateFlow.value = BleConnectionState.Connected("AA:BB:CC:DD:EE:FF", "Test", 0, false)

        runCatching { engine.triggerSync() }

        assertNull(getPrivateField("activeDeviceId"))
    }

    @Test
    fun `dispatchPostStreamParse threads activeDeviceId into the reading passed to MetricRouter`() {
        val reading = MetricReading(
            metricType = MetricType.HR,
            value = 60.0,
            unit = "bpm",
            recordedAt = Instant.parse("2026-06-15T12:00:00Z"),
            createdAt = Instant.now(),
            source = DataSource.DEVICE,
            driverId = "test-driver",
        )
        coEvery { driverRegistry.parseSession(any(), any()) } returns WasmParseResult.Success(listOf(reading))
        every { validator.validateReadings(any()) } returns listOf(ValidationResult.Accepted(reading))
        coEvery { syncProcessor.process(any(), any()) } returns mockk<SyncSummary>(relaxed = true)

        val captured = slot<MetricReading>()
        val routeLatch = CountDownLatch(1)
        coEvery { metricRouter.route(capture(captured), any(), any(), any()) } answers {
            routeLatch.countDown()
        }

        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        setPrivateField("activeDeviceId", 55L)
        addSessionFrame(SessionFrame("notify", "0x01", byteArrayOf(1, 2, 3)))

        invokeDispatchPostStreamParse()

        assertTrue("metricRouter.route was not called", routeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(55L, captured.captured.deviceId)
    }

    @Test
    fun `dispatchPostStreamParse leaves deviceId null on the routed reading when no device is resolved`() {
        val reading = MetricReading(
            metricType = MetricType.HR,
            value = 60.0,
            unit = "bpm",
            recordedAt = Instant.parse("2026-06-15T12:00:00Z"),
            createdAt = Instant.now(),
            source = DataSource.DEVICE,
            driverId = "test-driver",
        )
        coEvery { driverRegistry.parseSession(any(), any()) } returns WasmParseResult.Success(listOf(reading))
        every { validator.validateReadings(any()) } returns listOf(ValidationResult.Accepted(reading))
        coEvery { syncProcessor.process(any(), any()) } returns mockk<SyncSummary>(relaxed = true)

        val captured = slot<MetricReading>()
        val routeLatch = CountDownLatch(1)
        coEvery { metricRouter.route(capture(captured), any(), any(), any()) } answers {
            routeLatch.countDown()
        }

        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        // activeDeviceId left at its default null.
        addSessionFrame(SessionFrame("notify", "0x01", byteArrayOf(1, 2, 3)))

        invokeDispatchPostStreamParse()

        assertTrue("metricRouter.route was not called", routeLatch.await(5, TimeUnit.SECONDS))
        assertNull(captured.captured.deviceId)
    }
}
