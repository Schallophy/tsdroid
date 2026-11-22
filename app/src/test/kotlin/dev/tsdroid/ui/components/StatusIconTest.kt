package dev.tsdroid.ui.components

import dev.tsdroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The member list, the user sheet and the self-view bar all ask "can this client
 * speak / can I hear them", and they must answer with the same mark. These
 * helpers are the single copy of that order, so this test pins it: mute button
 * first, switched-off device second.
 */
class StatusIconTest {

    @Test
    fun `the mute button outranks the device`() {
        // Both are wrong at once: the button wins, because that is the state the
        // self-view button toggles and a tap has to visibly flip the icon.
        assertEquals(
            R.drawable.ts_input_muted,
            inputStatusIcon(muted = true, hardwareEnabled = false),
        )
        assertEquals(
            R.drawable.ts_output_muted,
            outputStatusIcon(deafened = true, hardwareEnabled = false),
        )
    }

    @Test
    fun `a switched-off device is its own mark`() {
        assertEquals(
            R.drawable.ts_input_hardware_muted,
            inputStatusIcon(muted = false, hardwareEnabled = false),
        )
        assertEquals(
            R.drawable.ts_output_hardware_muted,
            outputStatusIcon(deafened = false, hardwareEnabled = false),
        )
    }

    @Test
    fun `null means nothing is wrong`() {
        // null is the signal for the caller to draw the speaking ball instead.
        assertNull(inputStatusIcon(muted = false, hardwareEnabled = true))
        assertNull(outputStatusIcon(deafened = false, hardwareEnabled = true))
    }
}
