package com.github.tatercertified.nalim;

import com.github.tatercertified.rustmi.utils.backend.Backend;
import com.github.tatercertified.rustmi.utils.backend.BackendType;

public class BackendImpl implements Backend {
    @Override
    public BackendType backendType() {
        return NativeBackend.useNalim() ? BackendType.Nalim : BackendType.Ffm;
    }

    @Override
    public void onStartup() {
        // Publishes the decision (and binds the library) before anything can call it.
        NativeBackend.select();
        com.github.tatercertified.nalim.cramming.CrammingHooks.register();
        com.github.tatercertified.nalim.spawncap.SpawnCapHooks.register();
    }
}
