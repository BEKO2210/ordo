/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package android.text;

/**
 * JVM unit tests only: Media3's Format normalises every language through TextUtils.isEmpty, which the android.jar
 * stub throws on, so no extractor that sets a language (MP4) could run in a test. Same behaviour as the platform.
 */
public class TextUtils {
    public static boolean isEmpty(CharSequence text) {
        return text == null || text.length() == 0;
    }
}
