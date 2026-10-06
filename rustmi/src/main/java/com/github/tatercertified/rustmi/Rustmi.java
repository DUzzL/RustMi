package com.github.tatercertified.rustmi;

import com.github.tatercertified.rustmi.math.Mth;
import com.github.tatercertified.rustmi.utils.backend.Backend;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

public class Rustmi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Rustmi");
    // final: lets C2 constant-fold the receiver behind every Mth overwrite.
    public static final Mth MTH = ServiceLoader.load(Mth.class).iterator().next();
    public static final Backend BACKEND = ServiceLoader.load(Backend.class).iterator().next();

    @Override
    public void onInitialize() {
    }
}
