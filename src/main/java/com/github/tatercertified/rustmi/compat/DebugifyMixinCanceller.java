package com.github.tatercertified.rustmi.compat;

import com.bawnorton.mixinsquared.api.MixinCanceller;
import com.github.tatercertified.rustmi.Rustmi;

import java.util.List;

public class DebugifyMixinCanceller implements MixinCanceller {
    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        if ("dev.isxander.debugify.mixins.basic.mc199467.MthMixin".equals(mixinClassName)) {
            Rustmi.LOGGER.warn("Disabling Debugify's mc199467 patch; Rustmi keeps vanilla Mth.sin/cos for exact parity");
            return true;
        }
        return false;
    }
}
