package com.github.tatercertified.rustmi.utils.backend;

public interface Backend {
    BackendType backendType();
     default void onStartup() {}
}
