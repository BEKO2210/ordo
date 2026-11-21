// Copyright (c) 2026 Ordo contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DeepFilterNet3 for Ordo's "Dialog freistellen plus": one mono stream (the mid signal), 48 kHz, one hop at a time.
// Errors and panics come back as null or NaN, never as an abort of the app.
use df::tract::{DfParams, DfTract, ReduceMask, RuntimeParams};
use ndarray::{ArrayView2, ArrayViewMut2};
use std::ffi::{c_char, CStr};
use std::panic::{catch_unwind, AssertUnwindSafe};

pub struct OrdoDf(DfTract);

/// Loads the model archive (DeepFilterNet3_onnx.tar.gz) at `path`; null if it cannot.
#[no_mangle]
pub unsafe extern "C" fn ordo_df_create(path: *const c_char) -> *mut OrdoDf {
    if path.is_null() {
        return std::ptr::null_mut();
    }
    let path = match CStr::from_ptr(path).to_str() {
        Ok(p) => p.to_owned(),
        Err(_) => return std::ptr::null_mut(),
    };
    catch_unwind(|| {
        // The runtime parameters of libDF's own C API (capi.rs), made for real-time use.
        let params = RuntimeParams::default_with_ch(1)
            .with_atten_lim(100.)
            .with_thresholds(-15., 35., 35.)
            .with_post_filter(0.)
            .with_mask_reduce(ReduceMask::MAX);
        let model = DfTract::new(DfParams::new(path.into()).ok()?, &params).ok()?;
        Some(Box::into_raw(Box::new(OrdoDf(model))))
    })
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

/// Samples per hop (480 at 48 kHz).
#[no_mangle]
pub unsafe extern "C" fn ordo_df_hop(st: *const OrdoDf) -> usize {
    (*st).0.hop_size
}

/// Sample rate the model expects.
#[no_mangle]
pub unsafe extern "C" fn ordo_df_sample_rate(st: *const OrdoDf) -> usize {
    (*st).0.sr
}

/// Output delay in samples: STFT plus model lookahead, as libDF's enhance_wav.rs computes it.
#[no_mangle]
pub unsafe extern "C" fn ordo_df_delay(st: *const OrdoDf) -> usize {
    let m = &(*st).0;
    m.fft_size - m.hop_size + m.lookahead * m.hop_size
}

/// One hop: `input` and `output` hold ordo_df_hop() samples. Returns the local SNR, NaN on error.
#[no_mangle]
pub unsafe extern "C" fn ordo_df_process(st: *mut OrdoDf, input: *const f32, output: *mut f32) -> f32 {
    let m = &mut (*st).0;
    let hop = m.hop_size;
    let input = ArrayView2::from_shape_ptr((1, hop), input);
    let output = ArrayViewMut2::from_shape_ptr((1, hop), output);
    catch_unwind(AssertUnwindSafe(|| m.process(input, output).unwrap_or(f32::NAN))).unwrap_or(f32::NAN)
}

#[no_mangle]
pub unsafe extern "C" fn ordo_df_free(st: *mut OrdoDf) {
    if !st.is_null() {
        drop(Box::from_raw(st));
    }
}
