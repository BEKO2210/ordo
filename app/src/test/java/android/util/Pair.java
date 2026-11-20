/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package android.util;

import java.util.Objects;

/**
 * JVM unit tests only: Media3's MP4 parser keeps edit lists in android.util.Pair, whose android.jar stub throws, so
 * the gapless test could not read an MP4 file. Same fields and behaviour as the platform class.
 */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) {
        return new Pair<>(a, b);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Pair && Objects.equals(((Pair<?, ?>) o).first, first)
            && Objects.equals(((Pair<?, ?>) o).second, second);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(first) ^ Objects.hashCode(second);
    }
}
