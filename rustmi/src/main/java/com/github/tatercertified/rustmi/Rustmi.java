package com.github.tatercertified.rustmi;

import com.github.tatercertified.rustmi.utils.backend.Backend;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

public class Rustmi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Rustmi");
    public static final Backend BACKEND = ServiceLoader.load(Backend.class).iterator().next();

    @Override
    public void onInitialize() {
    }
}
