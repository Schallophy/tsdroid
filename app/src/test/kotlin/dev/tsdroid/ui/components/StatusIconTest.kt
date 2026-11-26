package dev.tsdroid.ui.components

import dev.tsdroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The member list, the user sheet and the self-view bar all ask "can this client
 * speak / can I hear them", and they must answer with the same mark. These
 * helpers are the single copy of that order, so this test pins it: switched-off
 * device first, mute button second.
 */
class StatusIconTest {

    @Test
    fun `the switched-off device outranks the mute button`() {
        // Both are wrong at once: the device wins, because it is the harder fact
        // — nothing can arrive while the device is off, button or no button.
        assertEquals(
            R.drawable.icon_hardware_input_muted,
            inputStatusIcon(muted = true, hardwareEnabled = false),
        )
        assertEquals(
            R.drawable.icon_hardware_output_muted,
            outputStatusIcon(deafened = true, hardwareEnabled = false),
        )
    }

    @Test
    fun `a switched-off device is its own mark`() {
        assertEquals(
            R.drawable.icon_hardware_input_muted,
            inputStatusIcon(muted = false, hardwareEnabled = false),
        )
        assertEquals(
            R.drawable.icon_hardware_output_muted,
            outputStatusIcon(deafened = false, hardwareEnabled = false),
        )
    }

    @Test
    fun `the output side claims the slot before the input side`() {
        // Everyone is muted: the speakers/headphones mark wins.
        assertEquals(
            R.drawable.icon_output_muted,
            memberStatusIcon(
                away = false, deafened = true, muted = true,
                inputHardwareEnabled = true, outputHardwareEnabled = true,
            ),
        )
        // Even a switched-off microphone yields to a muted speaker: the legend
        // puts speakers/headphones above the microphone.
        assertEquals(
            R.drawable.icon_output_muted,
            memberStatusIcon(
                away = false, deafened = true, muted = false,
                inputHardwareEnabled = false, outputHardwareEnabled = true,
            ),
        )
        // A switched-off speaker outranks every input-side state.
        assertEquals(
            R.drawable.icon_hardware_output_muted,
            memberStatusIcon(
                away = false, deafened = false, muted = true,
                inputHardwareEnabled = false, outputHardwareEnabled = false,
            ),
        )
        // Nothing wrong on the output side: the input mark takes over,
        // device off before mute.
        assertEquals(
            R.drawable.icon_hardware_input_muted,
            memberStatusIcon(
                away = false, deafened = false, muted = true,
                inputHardwareEnabled = false, outputHardwareEnabled = true,
            ),
        )
        assertEquals(
            R.drawable.icon_input_muted,
            memberStatusIcon(
                away = false, deafened = false, muted = true,
                inputHardwareEnabled = true, outputHardwareEnabled = true,
            ),
        )
        assertNull(
            memberStatusIcon(
                away = false, deafened = false, muted = false,
                inputHardwareEnabled = true, outputHardwareEnabled = true,
            ),
        )
    }

    @Test
    fun `the reader text follows the same order as the mark`() {
        assertEquals(
            "已关闭听筒",
            memberStatusDescription(
                away = false, deafened = true, muted = true,
                inputHardwareEnabled = false, outputHardwareEnabled = true,
            ),
        )
        assertEquals(
            "麦克风设备已关闭",
            memberStatusDescription(
                away = false, deafened = false, muted = true,
                inputHardwareEnabled = false, outputHardwareEnabled = true,
            ),
        )
        assertEquals(
            "麦克风已静音",
            memberStatusDescription(
                away = false, deafened = false, muted = true,
                inputHardwareEnabled = true, outputHardwareEnabled = true,
            ),
        )
    }

    @Test
    fun `null means nothing is wrong`() {
        // null is the signal for the caller to draw the speaking ball instead.
        assertNull(inputStatusIcon(muted = false, hardwareEnabled = true))
        assertNull(outputStatusIcon(deafened = false, hardwareEnabled = true))
    }
}
