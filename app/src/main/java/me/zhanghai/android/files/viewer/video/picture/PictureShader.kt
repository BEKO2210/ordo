/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The sharpening is a port of AMD FidelityFX CAS (ffx_cas.h, CasFilter, no-scaling path),
 * https://github.com/GPUOpen-Effects/FidelityFX-CAS, under this licence:
 *   Copyright (c) 2020 Advanced Micro Devices, Inc. All rights reserved.
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 * The debanding follows libplacebo's pl_shader_deband (src/shaders/sampling.c), https://github.com/haasn/libplacebo,
 * by Niklas Haas and the libplacebo contributors, GNU LGPL 2.1 or later; this port is distributed under
 * GPL-3.0-or-later as LGPL-2.1 section 3 permits.
 */

package me.zhanghai.android.files.viewer.video.picture

/**
 * Single-pass SDR picture processing shader. [FRAGMENT] samples `uTexSampler` using
 * `uTexelSize`, controls CAS with `uSharpness`, controls debanding with
 * `uDebandIterations`, `uDebandThreshold`, and `uDebandRadius`, adds `uGrain`, applies
 * final TPDF noise scaled by `uDitherAmplitude`, and varies noise over `uFrame`.
 */
internal object PictureShader {
    const val VERTEX = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}"""

    const val FRAGMENT = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
precision mediump int;

uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uSharpness;
uniform float uDebandIterations;
uniform float uDebandThreshold;
uniform float uDebandRadius;
uniform float uGrain;
uniform float uDitherAmplitude;
uniform float uFrame;
varying vec2 vTexSamplingCoord;

float random(vec2 offset) {
  vec2 p = gl_FragCoord.xy + offset + vec2(uFrame * 5.588238);
  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float casAmplitude(float mn, float mx) {
  if (mx == 0.0) return 0.0;
  return sqrt(clamp(min(mn, 2.0 - mx) / mx, 0.0, 1.0));
}

void main() {
  // One 3x3 fetch feeds CAS; debanding adds four taps per step.
  vec2 t = uTexelSize;
  vec2 p = vTexSamplingCoord;
  vec3 a = texture2D(uTexSampler, p + vec2(-t.x, -t.y)).rgb;
  vec3 b = texture2D(uTexSampler, p + vec2( 0.0, -t.y)).rgb;
  vec3 c = texture2D(uTexSampler, p + vec2( t.x, -t.y)).rgb;
  vec3 d = texture2D(uTexSampler, p + vec2(-t.x,  0.0)).rgb;
  vec3 e = texture2D(uTexSampler, p).rgb;
  vec3 f = texture2D(uTexSampler, p + vec2( t.x,  0.0)).rgb;
  vec3 g = texture2D(uTexSampler, p + vec2(-t.x,  t.y)).rgb;
  vec3 h = texture2D(uTexSampler, p + vec2( 0.0,  t.y)).rgb;
  vec3 i = texture2D(uTexSampler, p + vec2( t.x,  t.y)).rgb;

  // Preserve detail with CAS's diagonal-aware, per-channel adaptive kernel.
  vec3 cas = e;
  if (uSharpness > 0.0) {
    vec3 mn = min(min(d, e), min(f, min(b, h)));
    vec3 mx = max(max(d, e), max(f, max(b, h)));
    mn += min(mn, min(min(a, c), min(g, i)));
    mx += max(mx, max(max(a, c), max(g, i)));
    vec3 amp = vec3(casAmplitude(mn.r, mx.r), casAmplitude(mn.g, mx.g),
        casAmplitude(mn.b, mx.b));
    float peak = -1.0 / mix(8.0, 5.0, clamp(uSharpness, 0.0, 1.0));
    vec3 w = amp * peak;
    cas = clamp((w * (b + d + f + h) + e) / (1.0 + 4.0 * w), 0.0, 1.0);
  }

  // Replace only locally smooth quantisation steps with randomized radial averages.
  vec3 deb = e;
  float smoothness = 0.0;
  for (int j = 0; j < 2; ++j) {
    float iteration = float(j + 1);
    if (iteration > uDebandIterations) break;
    float dist = random(vec2(17.0, 59.0) + iteration * vec2(31.0, 11.0));
    float angle = 6.28318530718 * random(vec2(83.0, 29.0) + iteration * vec2(7.0, 47.0));
    vec2 q = iteration * uDebandRadius * dist * vec2(cos(angle), sin(angle));
    vec3 avg = texture2D(uTexSampler, p + t * vec2( q.x,  q.y)).rgb;
    avg += texture2D(uTexSampler, p + t * vec2(-q.x,  q.y)).rgb;
    avg += texture2D(uTexSampler, p + t * vec2(-q.x, -q.y)).rgb;
    avg += texture2D(uTexSampler, p + t * vec2( q.x, -q.y)).rgb;
    avg *= 0.25;
    vec3 diff = abs(deb - avg);
    float bound = uDebandThreshold / iteration;
    if (j == 0) {
      float maxDiff = max(diff.r, max(diff.g, diff.b));
      smoothness = 1.0 - smoothstep(0.5 * bound, 1.5 * bound, maxDiff);
    }
    deb = mix(avg, deb, step(vec3(bound), diff));
  }

  // Keep sharpening away from the smooth gradients selected for debanding.
  vec3 res = mix(cas, deb, smoothness);

  // Hide residual steps without lifting true black.
  vec3 strength = min(abs(res), vec3(uGrain));
  res += strength * (random(vec2(193.0, 71.0)) - 0.5);

  // Decorrelated TPDF dither prevents the final 8-bit store from restoring bands.
  float r1 = random(vec2(239.0, 113.0));
  float r2 = random(vec2(307.0, 181.0));
  res += (r1 + r2 - 1.0) * uDitherAmplitude;
  gl_FragColor = vec4(clamp(res, 0.0, 1.0), 1.0);
}"""
}
