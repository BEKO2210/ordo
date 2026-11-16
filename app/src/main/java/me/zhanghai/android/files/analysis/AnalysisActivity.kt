/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.os.Bundle
import android.view.View
import androidx.fragment.app.add
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity

class AnalysisActivity : AppActivity() {
    companion object {
        const val EXTRA_ROOT = "root"
        const val EXTRA_PAGE = "page"

        /** [page] opens one cleanup list on top of the overview ("large" or "duplicates"). */
        fun createIntent(context: android.content.Context, root: String, page: String? = null): android.content.Intent =
            android.content.Intent(context, AnalysisActivity::class.java).putExtra(EXTRA_ROOT, root)
                .apply { if (page != null) putExtra(EXTRA_PAGE, page) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                add<AnalysisFragment>(android.R.id.content, args = intent.extras)
            }
        }
    }
}
