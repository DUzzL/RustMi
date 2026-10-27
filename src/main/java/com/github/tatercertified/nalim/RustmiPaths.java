package com.github.tatercertified.nalim;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Locations of Rustmi's persisted toggles. Safe from {@code RustmiMixinPlugin.onLoad}: no Minecraft types. */
public final class RustmiPaths {

    private RustmiPaths() {}

    /**
     * {@code <loader config dir>/first/more...}. The loader's directory follows
     * {@code -Dfabric.gameDir}; outside Fabric (the Java test mains) it is {@code config/} in
     * the working directory.
     */
    public static Path config(String first, String... more) {
        Path base = null;
        try {
            base = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        } catch (Throwable ignored) {
            // loader not initialised
        }
        if (base == null) {
            base = Paths.get("config");
        }
        return base.resolve(Paths.get(first, more));
    }
}
