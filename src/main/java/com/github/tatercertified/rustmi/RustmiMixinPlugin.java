package com.github.tatercertified.rustmi;

import com.github.tatercertified.nalim.NativeBackend;
import com.github.tatercertified.nalim.cramming.CrammingHooks;
import com.github.tatercertified.nalim.spawncap.SpawnCapHooks;
import com.moulberry.mixinconstraints.MixinConstraints;
import com.moulberry.mixinconstraints.mixin.MixinConstraintsBootstrap;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Plugin of {@code rustmi.mixins.json} and Rustmi's startup: Mixin calls {@link #onLoad}
 * once, before it transforms any class, which is the earliest point a mod's code runs.
 * Mixin constraints ({@code @IfModLoaded} etc.) are evaluated here as well.
 */
public class RustmiMixinPlugin implements IMixinConfigPlugin {
    private String mixinPackage;

    @Override
    public void onLoad(String s) {
        try {
            // Order matters: the native library must be extracted before a backend links it,
            // and the backend chosen before anything calls RustmiLib.
            LoadRustBinary.onPreLaunch();
            NativeBackend.select();
            CrammingHooks.register();
            SpawnCapHooks.register();
        } catch (RuntimeException e) {
            // Mixin downgrades an Exception here to a warning and still applies the mixins,
            // which then crash in tick code; an Error stops startup with the cause.
            throw new Error("RustMI cannot start: " + e.getMessage(), e);
        }
        this.mixinPackage = s;
        MixinConstraintsBootstrap.init(mixinPackage);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (this.mixinPackage != null && !mixinClassName.startsWith(this.mixinPackage)) {
            return true;
        }
        return MixinConstraints.shouldApplyMixin(mixinClassName);
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode classNode, String mixinClassName, IMixinInfo iMixinInfo) {
    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {
    }
}
