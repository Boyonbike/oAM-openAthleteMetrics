package com.athletedata.openAthleteMetrics.ble.driver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber

/**
 * Pluggable end-of-stream detection for a WRITE command's [AwaitEndOfStream] block.
 *
 * A driver declares which technique its device uses (via the `strategy`/`params` fields
 * on [AwaitEndOfStream]); BleEngine consults the resulting strategy instance instead of
 * hardcoding a single detection technique. [onStreamStart] resets any per-command state;
 * [onNotification] is called once per raw GATT notification on the armed characteristic.
 */
sealed interface EndOfStreamStrategy {

    /**
     * Called once, synchronously, when the command's write is sent. [opcode] is
     * bytes[0] of the write payload (0 if empty). Resets any internal state so
     * nothing leaks between commands.
     */
    fun onStreamStart(opcode: Int)

    /**
     * Called once per GATT notification received while this command's stream is
     * armed (one onCharacteristicChanged callback's raw bytes, before any app-level
     * multi-notification reassembly). [elapsedMs] is time since [onStreamStart] for
     * this arm. Returns true iff the stream is now complete.
     */
    fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean

    /**
     * Polled independently of [onNotification] so silence-based strategies can
     * complete in the absence of further notifications. Default: never completes
     * this way. Only [QuietPeriod] overrides it.
     */
    fun isQuietNowMs(nowMs: Long): Boolean = false

    /** Reproduces BleEngine's original inline check: `bytes[offset] and 0xFF == value`. */
    data class FixedOffsetByte(val offset: Int, val value: Int) : EndOfStreamStrategy {
        override fun onStreamStart(opcode: Int) {}
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean =
            bytes.size > offset && (bytes[offset].toInt() and 0xFF) == value
    }

    /**
     * A standalone short sentinel packet, distinct in *shape* from normal data packets
     * so a terminatorByte value appearing inside a normal packet never matches.
     * matchOpcode=false: sentinel is exactly 1 byte == terminatorByte.
     * matchOpcode=true: sentinel is exactly 2 bytes [echoedOpcode, terminatorByte].
     *
     * NOTE: matchOpcode=false's "exactly 1 byte" rule is a new spec decision, not
     * derived from any existing driver or doc — no current driver exercises this
     * branch. Adjust if a real device's sentinel shape differs.
     */
    data class SentinelPacket(
        val terminatorByte: Int,
        val matchOpcode: Boolean = false,
    ) : EndOfStreamStrategy {
        @Volatile private var opcode: Int = 0
        override fun onStreamStart(opcode: Int) {
            this.opcode = opcode
        }
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean =
            if (matchOpcode) {
                bytes.size == 2 &&
                    (bytes[0].toInt() and 0xFF) == opcode &&
                    (bytes[1].toInt() and 0xFF) == terminatorByte
            } else {
                bytes.size == 1 && (bytes[0].toInt() and 0xFF) == terminatorByte
            }
    }

    /**
     * Completes when the notification's tail equals a terminator sequence (narrower
     * than "contains anywhere" — avoids false positives from the sequence appearing
     * legitimately inside binary payload data).
     *
     * matchOpcode=false (default): the tail must equal the static [terminatorBytes].
     * matchOpcode=true: the tail must equal `[echoedOpcode] + terminatorSuffix`, where
     * echoedOpcode is captured fresh on each [onStreamStart] — the same
     * per-command-instance pattern [SentinelPacket]'s matchOpcode=true already uses.
     * This lets one driver-authored `params` block serve every command that shares
     * the same trailing-terminator shape (e.g. `[opcode, 0xFF]`) without copy-pasting
     * a literal opcode into JSON per command. Safe only because a fresh instance is
     * constructed per command arm — see `EndOfStreamStrategyFactory.from`'s single
     * call site in `BleEngine.executeNextSyncCommand`, which is never cached/reused
     * across commands.
     *
     * Also completes on a short-packet signal for a fixed set of Hume Band opcodes
     * (0x52/0x66/0x55): a notification too short to hold one full record for that
     * opcode is itself treated as end-of-stream, independent of the terminator tail
     * check above. See [HUME_BAND_SHORT_PACKET_RECORD_SIZE].
     */
    class InStreamTerminator(
        private val terminatorBytes: ByteArray? = null,
        private val matchOpcode: Boolean = false,
        private val terminatorSuffix: ByteArray = ByteArray(0),
    ) : EndOfStreamStrategy {
        @Volatile private var opcode: Int = 0
        override fun onStreamStart(opcode: Int) {
            this.opcode = opcode
        }
        private fun effectiveTerminator(): ByteArray =
            if (matchOpcode) byteArrayOf(opcode.toByte()) + terminatorSuffix else (terminatorBytes ?: ByteArray(0))
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean {
            val term = effectiveTerminator()
            val terminatorMatch = term.isNotEmpty() && bytes.size >= term.size &&
                bytes.copyOfRange(bytes.size - term.size, bytes.size).contentEquals(term)
            // Short-packet completion only applies to the opcode-independent terminatorBytes
            // mode (matchOpcode=false) — that's the corrected Hume Band config this rule is
            // for. Scoping out matchOpcode=true keeps that separate, legacy [echoedOpcode,
            // suffix] mechanism (still exercised by its own tests/other configs) from picking
            // up this Hume-specific behavior just because it happens to reuse the same opcode
            // values.
            val recordSize = if (!matchOpcode) HUME_BAND_SHORT_PACKET_RECORD_SIZE[opcode] else null
            val shortPacketMatch = recordSize != null && bytes.size < recordSize
            return terminatorMatch || shortPacketMatch
        }
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InStreamTerminator) return false
            return matchOpcode == other.matchOpcode &&
                terminatorSuffix.contentEquals(other.terminatorSuffix) &&
                (terminatorBytes?.contentEquals(other.terminatorBytes ?: ByteArray(0)) ?: (other.terminatorBytes == null))
        }
        override fun hashCode(): Int {
            var result = matchOpcode.hashCode()
            result = 31 * result + terminatorSuffix.contentHashCode()
            result = 31 * result + (terminatorBytes?.contentHashCode() ?: 0)
            return result
        }

        private companion object {
            // Hume Band decompile (ResolveUtil.java): getDetailData (Steps)/getBloodoxygen
            // (SpO2)/getOnceHeartData (HR) additionally treat a notification too short to
            // hold one full record (length/recordSize == 0) as end-of-stream on its own.
            // Sleep/Temp/HRV's decompiled parse functions do not apply this rule, so they're
            // deliberately absent here. Record sizes mirror the do_steps/do_spo2/do_hr rawlen
            // guards in hume_band.wat.
            val HUME_BAND_SHORT_PACKET_RECORD_SIZE = mapOf(
                0x52 to 15, // GetSteps
                0x66 to 10, // GetSpO2
                0x55 to 10, // GetHR
            )
        }
    }

    sealed interface RecordCountSource {
        data class Fixed(val count: Int) : RecordCountSource
        data class FromField(val fieldName: String) : RecordCountSource
    }

    /**
     * Counts notifications on the armed characteristic; completes once the count
     * reaches [expectedCountSource]'s value. Only [RecordCountSource.Fixed] is
     * constructed by [EndOfStreamStrategyFactory] today — [RecordCountSource.FromField]
     * is schema-recognized but not yet resolvable (no cross-command data bus exists
     * for "read a value discovered in a prior response").
     *
     * Thread-safety: `received++` is a read-modify-write, which @Volatile alone does
     * not make atomic. This is safe only because Android's BluetoothGattCallback
     * invokes onCharacteristicChanged serially on a single callback thread per
     * connection, and [onStreamStart] (called from Main) happens-before the write
     * that triggers any notification — the same invariant BleEngine's existing
     * awaitReply/awaitEndOfStream deferred fields already rely on.
     */
    data class RecordCount(val expectedCountSource: RecordCountSource) : EndOfStreamStrategy {
        @Volatile private var received: Int = 0
        override fun onStreamStart(opcode: Int) {
            received = 0
        }
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean {
            received++
            val expected = (expectedCountSource as? RecordCountSource.Fixed)?.count ?: return false
            return received >= expected
        }
    }

    /**
     * Completes on a distinct opcode-tagged event packet. [characteristicUuid] is
     * accepted but not enforced — BleEngine still only ever calls a command's
     * strategy for the single characteristic named in `awaitEndOfStream.characteristic`.
     */
    data class ExplicitEvent(
        val eventOpcode: Int,
        val characteristicUuid: String? = null,
    ) : EndOfStreamStrategy {
        override fun onStreamStart(opcode: Int) {}
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean =
            bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == eventOpcode
    }

    /**
     * Completes after [quietMs] of silence since the last notification (or since
     * stream start if none arrived yet). Never completes via [onNotification] itself
     * — BleEngine polls [isQuietNowMs] from within its existing timeout wait.
     */
    data class QuietPeriod(val quietMs: Long) : EndOfStreamStrategy {
        @Volatile private var lastActivityMs: Long = 0L
        override fun onStreamStart(opcode: Int) {
            lastActivityMs = 0L
        }
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean {
            lastActivityMs = elapsedMs
            return false
        }
        override fun isQuietNowMs(nowMs: Long): Boolean = (nowMs - lastActivityMs) >= quietMs
    }

    /**
     * Delegates end-of-stream detection to a driver-exported WASM function, for devices
     * whose signaling doesn't fit any of the other strategies. [wasmExport] is the name of
     * the function the driver's WASM module must export; [invoke] bridges to the currently
     * loaded WASM instance and is supplied by the caller (BleEngine, via
     * [EndOfStreamStrategyFactory.from]'s `customInvoker` parameter) — this is the one
     * strategy that cannot be constructed with zero engine coupling, since it has to
     * actually run driver code.
     *
     * ABI the export must implement: `(ptr: i32, len: i32, opcode: i32, elapsedMs: i32) ->
     * i32`. `ptr`/`len` point to a scratch region of the module's own linear memory where
     * the engine has just written the raw bytes of the notification that arrived; `opcode`
     * is the byte[0] of the write that armed this strategy (captured in [onStreamStart]);
     * `elapsedMs` is time since that arm. Result `1` means the stream has ended, anything
     * else means keep waiting.
     *
     * [invoke]'s implementation (see `WasmDriverEngine.evaluateCustomPredicate`) is
     * responsible for catching every failure mode — trap, missing export, execution-budget
     * overrun, engine busy — and translating it to `false`. Nothing here can throw past this
     * class; a broken or slow driver predicate degrades to "still waiting" and the command's
     * ordinary `timeoutMs` remains the ultimate bound.
     */
    class Custom(
        val wasmExport: String,
        private val invoke: (wasmExport: String, bytes: ByteArray, opcode: Int, elapsedMs: Long) -> Boolean,
    ) : EndOfStreamStrategy {
        @Volatile private var opcode: Int = 0
        override fun onStreamStart(opcode: Int) {
            this.opcode = opcode
        }
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean =
            invoke(wasmExport, bytes, opcode, elapsedMs)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Custom) return false
            return wasmExport == other.wasmExport
        }
        override fun hashCode(): Int = wasmExport.hashCode()
    }

    /**
     * Fallback for missing/unrecognized strategy declarations. Never completes on
     * its own; relies entirely on the command's timeoutMs safety net.
     */
    object TimeoutOnly : EndOfStreamStrategy {
        override fun onStreamStart(opcode: Int) {}
        override fun onNotification(bytes: ByteArray, elapsedMs: Long): Boolean = false
    }
}

/**
 * Parses a command's [AwaitEndOfStream] into an [EndOfStreamStrategy]. Pure function,
 * no BleEngine coupling — easily unit-testable in isolation.
 */
object EndOfStreamStrategyFactory {

    fun from(
        aes: AwaitEndOfStream,
        customInvoker: ((wasmExport: String, bytes: ByteArray, opcode: Int, elapsedMs: Long) -> Boolean)? = null,
    ): EndOfStreamStrategy {
        val params = aes.params
        return when (aes.strategy) {
            null, "FIXED_OFFSET_BYTE" -> {
                val endByte = aes.endByte
                if (endByte == null) {
                    Timber.w("EndOfStreamStrategyFactory: FIXED_OFFSET_BYTE requires endByte — falling back to TimeoutOnly")
                    EndOfStreamStrategy.TimeoutOnly
                } else {
                    EndOfStreamStrategy.FixedOffsetByte(endByte.offset, endByte.value)
                }
            }

            "SENTINEL_PACKET" -> {
                val terminatorByte = params?.get("terminatorByte")?.jsonPrimitive?.intOrNull
                if (terminatorByte == null) {
                    Timber.w("EndOfStreamStrategyFactory: SENTINEL_PACKET missing terminatorByte — falling back to TimeoutOnly")
                    EndOfStreamStrategy.TimeoutOnly
                } else {
                    val matchOpcode = params.get("matchOpcode")?.jsonPrimitive?.booleanOrNull ?: false
                    EndOfStreamStrategy.SentinelPacket(terminatorByte, matchOpcode)
                }
            }

            "IN_STREAM_TERMINATOR" -> {
                val matchOpcode = params?.get("matchOpcode")?.jsonPrimitive?.booleanOrNull ?: false
                if (matchOpcode) {
                    val suffixHex = params?.get("terminatorSuffix")?.jsonPrimitive?.contentOrNull
                    val suffix = suffixHex?.let { runCatching { parseHexBytes(it) }.getOrNull() }
                    if (suffix == null) {
                        Timber.w("EndOfStreamStrategyFactory: IN_STREAM_TERMINATOR matchOpcode=true missing/invalid terminatorSuffix — falling back to TimeoutOnly")
                        EndOfStreamStrategy.TimeoutOnly
                    } else {
                        EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = suffix)
                    }
                } else {
                    val hex = params?.get("terminatorBytes")?.jsonPrimitive?.contentOrNull
                    val bytes = hex?.let { runCatching { parseHexBytes(it) }.getOrNull() } // degrade to null on malformed hex, matching every sibling branch
                    if (bytes == null || bytes.isEmpty()) {
                        Timber.w("EndOfStreamStrategyFactory: IN_STREAM_TERMINATOR missing/invalid terminatorBytes — falling back to TimeoutOnly")
                        EndOfStreamStrategy.TimeoutOnly
                    } else {
                        EndOfStreamStrategy.InStreamTerminator(terminatorBytes = bytes)
                    }
                }
            }

            "RECORD_COUNT" -> {
                when (params?.get("source")?.jsonPrimitive?.contentOrNull) {
                    "fixed" -> {
                        val count = params.get("count")?.jsonPrimitive?.intOrNull
                        if (count == null) {
                            Timber.w("EndOfStreamStrategyFactory: RECORD_COUNT fixed missing count — falling back to TimeoutOnly")
                            EndOfStreamStrategy.TimeoutOnly
                        } else {
                            EndOfStreamStrategy.RecordCount(EndOfStreamStrategy.RecordCountSource.Fixed(count))
                        }
                    }
                    "fromField" -> {
                        Timber.w("EndOfStreamStrategyFactory: RECORD_COUNT source 'fromField' not yet supported — falling back to TimeoutOnly")
                        EndOfStreamStrategy.TimeoutOnly
                    }
                    else -> {
                        Timber.w("EndOfStreamStrategyFactory: RECORD_COUNT missing/unknown source — falling back to TimeoutOnly")
                        EndOfStreamStrategy.TimeoutOnly
                    }
                }
            }

            "EXPLICIT_EVENT" -> {
                val eventOpcode = params?.get("eventOpcode")?.jsonPrimitive?.intOrNull
                if (eventOpcode == null) {
                    Timber.w("EndOfStreamStrategyFactory: EXPLICIT_EVENT missing eventOpcode — falling back to TimeoutOnly")
                    EndOfStreamStrategy.TimeoutOnly
                } else {
                    val charUuid = params.get("characteristicUuid")?.jsonPrimitive?.contentOrNull
                    EndOfStreamStrategy.ExplicitEvent(eventOpcode, charUuid)
                }
            }

            "QUIET_PERIOD" -> {
                val quietMs = params?.get("quietMs")?.jsonPrimitive?.longOrNull
                if (quietMs == null) {
                    Timber.w("EndOfStreamStrategyFactory: QUIET_PERIOD missing quietMs — falling back to TimeoutOnly")
                    EndOfStreamStrategy.TimeoutOnly
                } else {
                    EndOfStreamStrategy.QuietPeriod(quietMs)
                }
            }

            "CUSTOM" -> {
                val wasmExport = params?.get("wasmExport")?.jsonPrimitive?.contentOrNull
                if (wasmExport.isNullOrBlank()) {
                    Timber.w("EndOfStreamStrategyFactory: CUSTOM missing wasmExport — falling back to TimeoutOnly")
                    EndOfStreamStrategy.TimeoutOnly
                } else if (customInvoker == null) {
                    Timber.w("EndOfStreamStrategyFactory: CUSTOM requires a customInvoker, none supplied — falling back to TimeoutOnly")
                    EndOfStreamStrategy.TimeoutOnly
                } else {
                    EndOfStreamStrategy.Custom(wasmExport, customInvoker)
                }
            }

            else -> {
                Timber.w("EndOfStreamStrategyFactory: unrecognized strategy '${aes.strategy}' — falling back to TimeoutOnly")
                EndOfStreamStrategy.TimeoutOnly
            }
        }
    }

    // Deliberately duplicated rather than calling BleEngine.parseHexBytes (private) — the
    // factory has zero BleEngine coupling by design. Same hex format as SyncCommand.Write.bytes.
    private fun parseHexBytes(hex: String): ByteArray =
        hex.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            .map { it.removePrefix("0x").removePrefix("0X").toInt(16).toByte() }
            .toByteArray()
}
