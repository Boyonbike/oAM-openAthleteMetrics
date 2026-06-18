package com.athletedata.openAthleteMetrics.ble.driver

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriverStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val validator = ManifestValidator()

    private val driversDir: File
        get() = File(context.filesDir, "drivers").also { it.mkdirs() }

    suspend fun saveDriver(sourceUri: Uri): DriverSaveResult = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: return@withContext DriverSaveResult.Error("Could not open file")
            val manifest = try {
                json.decodeFromString<WasmDriverManifest>(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return@withContext DriverSaveResult.Error("Invalid JSON: ${e.message}")
            }
            val errors = validator.validate(manifest)
            if (errors.isNotEmpty()) return@withContext DriverSaveResult.Invalid(errors)
            File(driversDir, "${manifest.id}.json").writeBytes(bytes)
            DriverSaveResult.Success(manifest)
        } catch (e: Exception) {
            DriverSaveResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun loadAllDrivers(): List<WasmDriverManifest> = withContext(Dispatchers.IO) {
        driversDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val manifest = json.decodeFromString<WasmDriverManifest>(file.readText())
                    val errors = validator.validate(manifest)
                    if (errors.isNotEmpty()) {
                        Timber.w("DriverStorage: skipping invalid driver ${file.name}: $errors")
                        return@mapNotNull null
                    }
                    manifest
                } catch (e: Exception) {
                    Timber.w(e, "DriverStorage: skipping unparseable driver ${file.name}")
                    null
                }
            }.orEmpty()
    }

    fun deleteDriver(driverId: String): Boolean =
        File(driversDir, "$driverId.json").delete()

    sealed class DriverSaveResult {
        data class Success(val manifest: WasmDriverManifest) : DriverSaveResult()
        data class Invalid(val errors: List<String>) : DriverSaveResult()
        data class Error(val message: String) : DriverSaveResult()
    }
}
