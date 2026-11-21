/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * JNI for me.zhanghai.android.files.viewer.video.sound.DeepFilterNative over ordo_df (ordo_df/src/lib.rs).
 */

#include <jni.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>

void *ordo_df_create(const char *path);
size_t ordo_df_hop(const void *st);
size_t ordo_df_sample_rate(const void *st);
size_t ordo_df_delay(const void *st);
float ordo_df_process(void *st, const float *input, float *output);
void ordo_df_free(void *st);

#define FN(name) Java_me_zhanghai_android_files_viewer_video_sound_DeepFilterNative_##name

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jobject self, jstring path) {
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (chars == NULL) return 0;
    void *st = ordo_df_create(chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return (jlong) (intptr_t) st;
}

JNIEXPORT jint JNICALL FN(hop)(JNIEnv *env, jobject self, jlong st) {
    return (jint) ordo_df_hop((void *) (intptr_t) st);
}

JNIEXPORT jint JNICALL FN(sampleRate)(JNIEnv *env, jobject self, jlong st) {
    return (jint) ordo_df_sample_rate((void *) (intptr_t) st);
}

JNIEXPORT jint JNICALL FN(delay)(JNIEnv *env, jobject self, jlong st) {
    return (jint) ordo_df_delay((void *) (intptr_t) st);
}

/* input and output are FloatArrays of hop() samples. Copied, not pinned: the model must not run inside a JNI
 * critical region (it would hold up the garbage collector). */
JNIEXPORT jfloat JNICALL FN(process)(JNIEnv *env, jobject self, jlong st, jfloatArray input, jfloatArray output) {
    size_t hop = ordo_df_hop((void *) (intptr_t) st);
    if ((size_t) (*env)->GetArrayLength(env, input) != hop || (size_t) (*env)->GetArrayLength(env, output) != hop) {
        return NAN;
    }
    float in[hop], out[hop];
    (*env)->GetFloatArrayRegion(env, input, 0, (jsize) hop, in);
    float snr = ordo_df_process((void *) (intptr_t) st, in, out);
    (*env)->SetFloatArrayRegion(env, output, 0, (jsize) hop, out);
    return snr;
}

JNIEXPORT void JNICALL FN(free)(JNIEnv *env, jobject self, jlong st) {
    ordo_df_free((void *) (intptr_t) st);
}
