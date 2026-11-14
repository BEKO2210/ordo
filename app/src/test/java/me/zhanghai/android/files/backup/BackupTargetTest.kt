/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTargetTest {
    private fun split(vararg labels: String, massStorage: Boolean = false) =
        BackupFragment.splitRemovable(labels.toList(), massStorage).let { it.usb to it.sd }

    @Test
    fun tabletWithSdCardOnly() {
        // Galaxy Tab with a card inserted and nothing plugged in: SD row only, USB waits.
        assertEquals(null to 0, split("SD-Karte"))
    }

    @Test
    fun sdCardAndUsbDriveAreToldApart() {
        assertEquals(1 to 0, split("SD-Karte", "SanDisk USB-Laufwerk", massStorage = true))
        assertEquals(0 to 1, split("Generic USB drive", "SD card", massStorage = true))
    }

    @Test
    fun unlabelledVolumeIsUsbOnlyWhileADriveIsPluggedIn() {
        assertEquals(0 to null, split("Volume", massStorage = true))
        assertEquals(null to 0, split("Volume"))
    }

    @Test
    fun microSdIsNotMistakenForUsb() {
        assertEquals(null to 0, split("Samsung microSD"))
    }

    @Test
    fun nothingMounted() {
        assertEquals(null to null, split())
    }
}
