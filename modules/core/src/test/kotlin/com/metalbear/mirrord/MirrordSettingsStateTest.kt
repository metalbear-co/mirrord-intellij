package com.metalbear.mirrord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MirrordSettingsStateTest {

    @Test
    fun troubleshootingLoggingKeepsRustLogOutOfTheUserProcess() {
        val settings = MirrordSettingsState.MirrordState().apply {
            troubleshootingLogsEnabled = true
            troubleshootingLogsPath = " C:/logs "
        }

        assertEquals(
            mapOf(
                "MIRRORD_LOG" to "trace",
                "MIRRORD_LAYER_LOG_PATH" to "C:/logs"
            ),
            settings.troubleshootingLayerEnvVars()
        )
        assertEquals(
            mapOf("RUST_LOG" to "trace"),
            settings.troubleshootingCliEnvVars()
        )
    }

    @Test
    fun troubleshootingLoggingIsEmptyWhenDisabled() {
        val settings = MirrordSettingsState.MirrordState()

        assertTrue(settings.troubleshootingLayerEnvVars().isEmpty())
        assertTrue(settings.troubleshootingCliEnvVars().isEmpty())
    }

    /**
     * WSL runs through EEL by default; the legacy `wsl.exe` integration is an opt-out escape
     * hatch for users hitting a regression. If this default ever flips silently, WSL users stop
     * exercising the path we actually maintain.
     */
    @Test
    fun legacyWslIsOptOutNotOptIn() {
        assertEquals(false, MirrordSettingsState.MirrordState().useLegacyWsl)
    }

    @Test
    fun troubleshootingLogPathCanBeTranslatedOrOmitted() {
        val settings = MirrordSettingsState.MirrordState().apply {
            troubleshootingLogsEnabled = true
            troubleshootingLogsPath = "C:/logs"
        }

        assertEquals(
            mapOf(
                "MIRRORD_LOG" to "trace",
                "MIRRORD_LAYER_LOG_PATH" to "/mnt/c/logs"
            ),
            settings.troubleshootingLayerEnvVars { "/mnt/c/logs" }
        )
        assertEquals(
            mapOf("MIRRORD_LOG" to "trace"),
            settings.troubleshootingLayerEnvVars { "" }
        )
    }
}
