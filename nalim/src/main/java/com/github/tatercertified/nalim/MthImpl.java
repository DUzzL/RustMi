package com.github.tatercertified.nalim;

import com.github.tatercertified.nalim.rust.RustmiLib;
import com.github.tatercertified.rustmi.math.JavaMth;

/** Backend hook for the native colour packer; every other operation is the shared vanilla-exact {@link JavaMth}. */
public class MthImpl extends JavaMth {
    @Override
    public int packRgb(float r, float g, float b) {
        return RustmiLib.pack_rgb(r, g, b);
    }
}
