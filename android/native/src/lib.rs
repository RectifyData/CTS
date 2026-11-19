//! Deterministic frame math shared by Android preview and export through JNI.
//! No network, filesystem, third-party SDK or runtime dependencies.
use std::ffi::c_void;

fn unit(t: f32) -> f32 { t.clamp(0.0, 1.0) }

pub fn ease(input: f32, style: i32) -> f32 {
    let t = unit(input);
    let q = 1.0 - t;
    match style {
        1 => t * t * (3.0 - 2.0 * t),
        2 => t * t * t,
        3 => 1.0 - q * q * q,
        4 => t * t,
        5 => 1.0 - q * q,
        6 => 0.0,
        _ => t,
    }
}

fn cubic(t: f32, a: f32, b: f32) -> f32 {
    let q = 1.0 - t;
    3.0 * q * q * t * a + 3.0 * q * t * t * b + t * t * t
}

pub fn material(input: f32, x1: f32, y1: f32, x2: f32, y2: f32) -> f32 {
    let x = unit(input);
    if x <= 0.0 || x >= 1.0 { return x; }
    let (mut low, mut high) = (0.0_f32, 1.0_f32);
    for _ in 0..12 {
        let t = (low + high) / 2.0;
        if cubic(t, x1, x2) < x { low = t; } else { high = t; }
    }
    cubic((low + high) / 2.0, y1, y2)
}

/// Returns the local asset index or -1 if the finite sequence part is exhausted.
/// Pauses hold the last asset. Arithmetic uses i64 to avoid overflow.
pub fn sequence_index(frame: i32, frames: i32, pause: i32, count: i32) -> i32 {
    if frame < 0 || frames <= 0 || pause < 0 || count < 0 { return -1; }
    let length = i64::from(frames) + i64::from(pause);
    let frame = i64::from(frame);
    if count != 0 && frame >= length * i64::from(count) { return -1; }
    (frame % length).min(i64::from(frames) - 1) as i32
}

// JNI primitive-only ABI. No references, callbacks, allocations or unsafe dereferences.
#[no_mangle]
pub extern "system" fn Java_io_github_retrofrost_cts_android_nativecore_RustFrameCore_nativeAbiVersion(
    _: *mut c_void, _: *mut c_void,
) -> i32 { 1 }

#[no_mangle]
pub extern "system" fn Java_io_github_retrofrost_cts_android_nativecore_RustFrameCore_nativeEase(
    _: *mut c_void, _: *mut c_void, value: f32, style: i32,
) -> f32 { ease(value, style) }

#[no_mangle]
pub extern "system" fn Java_io_github_retrofrost_cts_android_nativecore_RustFrameCore_nativeMaterial(
    _: *mut c_void, _: *mut c_void, value: f32, x1: f32, y1: f32, x2: f32, y2: f32,
) -> f32 { material(value, x1, y1, x2, y2) }

#[no_mangle]
pub extern "system" fn Java_io_github_retrofrost_cts_android_nativecore_RustFrameCore_nativeSequenceIndex(
    _: *mut c_void, _: *mut c_void, frame: i32, frames: i32, pause: i32, count: i32,
) -> i32 { sequence_index(frame, frames, pause, count) }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sequence_boundaries() {
        assert_eq!(sequence_index(-1, 3, 2, 2), -1);
        assert_eq!(sequence_index(0, 3, 2, 2), 0);
        assert_eq!(sequence_index(2, 3, 2, 2), 2);
        assert_eq!(sequence_index(4, 3, 2, 2), 2);
        assert_eq!(sequence_index(5, 3, 2, 2), 0);
        assert_eq!(sequence_index(9, 3, 2, 2), 2);
        assert_eq!(sequence_index(10, 3, 2, 2), -1);
        assert_eq!(sequence_index(i32::MAX, 3, 2, 0), 2);
        assert_eq!(sequence_index(i32::MAX, i32::MAX, i32::MAX, i32::MAX), i32::MAX - 1);
        assert_eq!(sequence_index(0, 0, 0, 1), -1);
    }
    #[test]
    fn easing_endpoints_and_monotonicity() {
        for style in 0..=6 {
            assert_eq!(ease(-1.0, style), 0.0);
            assert_eq!(ease(2.0, style), if style == 6 {0.0} else {1.0});
            let mut previous = 0.0;
            for i in 0..=1000 {
                let now = ease(i as f32 / 1000.0, style);
                assert!(now >= previous && now <= 1.0);
                previous = now;
            }
        }
        assert_eq!(ease(0.5, 1), 0.5);
        assert_eq!(ease(0.5, 2), 0.125);
        assert_eq!(ease(0.5, 3), 0.875);
    }
    #[test]
    fn material_curve() {
        assert_eq!(material(0.0, 0.4, 0.0, 0.2, 1.0), 0.0);
        assert_eq!(material(1.0, 0.4, 0.0, 0.2, 1.0), 1.0);
        let mut previous = 0.0;
        for i in 0..=1000 {
            let now = material(i as f32 / 1000.0, 0.4, 0.0, 0.2, 1.0);
            assert!(now >= previous && now <= 1.0);
            previous = now;
        }
    }
}
