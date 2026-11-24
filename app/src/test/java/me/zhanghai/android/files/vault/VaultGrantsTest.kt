/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** VaultProvider serves only what the unlocked vault screen granted, and nothing after a lock. */
class VaultGrantsTest {
    private val a = VaultEntry("1-a", "/storage/emulated/0/DCIM/a.jpg", 10, 1)

    @Test
    fun onlyGrantedEntriesUntilRevoked() {
        val grants = VaultGrants()
        assertNull(grants.entry(a.id))
        val token = grants.grant(a)
        assertSame(a, grants.entry(token))
        assertNull(grants.entry(a.id))
        assertNull(grants.entry(null))
        grants.revokeAll()
        assertNull(grants.entry(token))
    }

    // An app that kept the URI from an earlier opening cannot read the entry when it is opened again.
    @Test
    fun reopeningGivesANewTokenAndNoNameOrId() {
        val grants = VaultGrants()
        val first = grants.grant(a)
        grants.revokeAll()
        // Tokens are UUIDs (hex digits and dashes), so an id with other characters can never occur by chance.
        val entry = VaultEntry("id_xyz", a.originalPath, a.size, a.addedAt)
        val second = grants.grant(entry)
        assertNotEquals(first, second)
        assertNull(grants.entry(first))
        assertFalse(second.contains(entry.id) || second.contains("a.jpg"))
    }
}
