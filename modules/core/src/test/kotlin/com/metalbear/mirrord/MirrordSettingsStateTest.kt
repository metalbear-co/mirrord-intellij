package com.metalbear.mirrord

import org.junit.jupiter.api.Test

class MirrordSettingsStateTest {

    @Test
    fun troubleshootingLoggingKeepsRustLogOutOfTheUserProcess() {
        val settings = MirrordSettingsState.MirrordState().apply {
            troubleshootingLogsEnabled = true
            troubleshootingLogsPath = " C:/logs "
        }

        assert(
            settings.troubleshootingLayerEnvVars() ==
                mapOf(
                    "MIRRORD_LOG" to "trace",
                    "MIRRORD_LAYER_LOG_PATH" to "C:/logs"
                )
        )
        assert(settings.troubleshootingCliEnvVars() == mapOf("RUST_LOG" to "trace"))
    }

    @Test
    fun troubleshootingLoggingIsEmptyWhenDisabled() {
        val settings = MirrordSettingsState.MirrordState()

        assert(settings.troubleshootingLayerEnvVars().isEmpty())
        assert(settings.troubleshootingCliEnvVars().isEmpty())
    }
}
