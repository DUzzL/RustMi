package com.github.tatercertified.rustmi_tester;

import com.github.tatercertified.rustmi.Config;
import com.github.tatercertified.rustmi_tester.test.TestingGUI;
import imgui.app.Application;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class RustmiTesterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Not MappingTranslator.IS_DEV: unobfuscated 26.x production runs in the
        // "official" namespace, which that flag reports as a dev environment.
        if (Config.getInstance().test() || FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Application.launch(new TestingGUI());
        }
    }
}
