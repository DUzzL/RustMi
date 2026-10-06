//! Scalar `Mth` helpers. Every hot `Mth` operation runs as vanilla-exact Java (a JVM body is
//! cheaper than any native call for them); only the legacy colour packer of
//! `Mth.color(float, float, float)` (Minecraft 1.19.4–1.21.1) remains native.

/// Compresses an RGB value into a single int
#[no_mangle]
pub extern "C" fn pack_rgb(red: f32, green: f32, blue: f32) -> u32 {
    (((red * 255.0_f32) as u32) << 16)
        | (((green * 255.0_f32) as u32) << 8)
        | (blue * 255.0_f32) as u32
}
