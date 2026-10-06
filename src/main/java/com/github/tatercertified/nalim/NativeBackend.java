package com.github.tatercertified.nalim;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.FfmBindings;
import com.github.tatercertified.nalim.rust.NalimBindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Chooses how RustMI calls its native library, once, before any feature runs.
 *
 * <ul>
 *   <li><b>Nalim</b> (JVMCI): the fastest call path. Enabled automatically only where it is
 *       proven — Linux x86_64 on Java 25/26 with the C2 JIT — and only when the launch flags
 *       allow it ({@code --add-modules=jdk.internal.vm.ci}; on Java 26+ also
 *       {@code -XX:-UseCodeCacheFlushing}). Its failure modes elsewhere are JVM aborts or
 *       silent ABI corruption that no {@code catch} can turn into a fallback.</li>
 *   <li><b>FFM</b> (Foreign Function &amp; Memory API): everywhere else, same features, a few
 *       nanoseconds more per call.</li>
 * </ul>
 *
 * {@code -Drustmi.backend=nalim} forces Nalim (startup fails instead of falling back);
 * {@code -Drustmi.backend=ffm} forces FFM.
 */
public final class NativeBackend {

    public enum Kind { NALIM, FFM }

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-Backend");

    private static volatile Kind kind;
    private static volatile String detail = "";

    private NativeBackend() {}

    /** True for Nalim. Throws if called before {@link #select()} published the decision. */
    public static boolean useNalim() {
        Kind k = kind;
        if (k == null) {
            throw new IllegalStateException("RustMI native library used before the backend was selected");
        }
        return k == Kind.NALIM;
    }

    /** One line for logs and {@code /rustmi stats}. */
    public static String describe() {
        Kind k = kind;
        if (k == null) {
            return "not selected";
        }
        return k == Kind.NALIM ? "Nalim (JVMCI)" : "FFM (" + detail + ")";
    }

    public static synchronized void select() {
        if (kind != null) {
            return;
        }
        String mode = System.getProperty("rustmi.backend", "auto").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("auto") && !mode.equals("nalim") && !mode.equals("ffm")) {
            throw new IllegalStateException("-Drustmi.backend must be auto, nalim or ffm, not '" + mode + "'");
        }
        if (mode.equals("ffm")) {
            useFfm("forced by -Drustmi.backend=ffm");
            return;
        }
        boolean forced = mode.equals("nalim");
        String blocker = nalimBlocker(forced);
        if (blocker != null) {
            if (forced) {
                throw new IllegalStateException("-Drustmi.backend=nalim, but " + blocker);
            }
            useFfm(blocker);
            return;
        }
        try {
            linkNalim();
        } catch (Throwable t) {
            if (forced) {
                throw new IllegalStateException("-Drustmi.backend=nalim, but Nalim failed to initialize", t);
            }
            LOGGER.warn("Nalim failed to initialize; falling back to FFM", t);
            useFfm("Nalim failed to initialize: " + t);
            return;
        }
        publish(Kind.NALIM, "");
    }

    /** Why Nalim must not be tried on this JVM, or null when it may. Cheap, side-effect free. */
    private static String nalimBlocker(boolean forced) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        int feature = Runtime.version().feature();
        if (!forced) {
            if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
                return "Nalim is enabled automatically only on Linux x86_64 (this JVM: " + os + "/" + arch + ")";
            }
            if (feature < 25 || feature > 26) {
                return "Nalim is enabled automatically only on Java 25-26 (this JVM: " + feature + ")";
            }
            if ("true".equals(vmOption("UseJVMCICompiler"))
                    || System.getProperty("java.vm.version", "").contains("jvmci")) {
                return "this is a GraalVM/JVMCI-patched JDK (Nalim is verified on HotSpot C2 only)";
            }
            // Nalim is verified with Serial, Parallel, G1 and ZGC.
            if ("true".equals(vmOption("UseShenandoahGC")) || "true".equals(vmOption("UseEpsilonGC"))) {
                return "Nalim is enabled automatically only with the Serial, Parallel, G1 or Z collector";
            }
        }
        if (ModuleLayer.boot().findModule("jdk.internal.vm.ci").isEmpty()) {
            return "the launch flag --add-modules=jdk.internal.vm.ci is missing";
        }
        // JDK 26 removed the native-method exemption from nmethod::is_cold(): an idle
        // Nalim-installed method can be flushed and its next call throws
        // UnsatisfiedLinkError deep inside tick code.
        if (feature >= 26) {
            String flushing = vmOption("UseCodeCacheFlushing");
            if (flushing == null) {
                return "Java " + feature + " requires checking -XX:-UseCodeCacheFlushing, but the VM option "
                        + "is unreadable (module jdk.management missing)";
            }
            if (!flushing.equals("false")) {
                return "Java " + feature + " needs the launch flag -XX:-UseCodeCacheFlushing for Nalim";
            }
        }
        return null;
    }

    private static String vmOption(String name) {
        try {
            return java.lang.management.ManagementFactory
                    .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                    .getVMOption(name).getValue();
        } catch (LinkageError | RuntimeException e) {
            return null;   // jdk.management absent or option unknown
        }
    }

    private static void linkNalim() throws ReflectiveOperationException {
        ClassLoader loader = NativeBackend.class.getClassLoader();
        // JVMCI runtime first, then link every native entry before mixins can give the JIT a
        // chance to compile call sites against unresolved native stubs.
        Class.forName("one.nalim.Linker", true, loader);
        Class.forName("com.github.tatercertified.nalim.rust.NalimBindings", true, loader);
        checkLayout("Nalim",
                NalimBindings.oxi_cramming_input_size(),
                NalimBindings.oxi_cramming_output_size());
    }

    private static void useFfm(String reason) {
        try {
            Class.forName("com.github.tatercertified.nalim.rust.FfmBindings", true,
                    NativeBackend.class.getClassLoader());
            checkLayout("FFM",
                    FfmBindings.oxi_cramming_input_size(),
                    FfmBindings.oxi_cramming_output_size());
        } catch (Throwable t) {
            // FfmBindings' initializer binds the library; its failures arrive wrapped.
            Throwable cause = t instanceof ExceptionInInitializerError && t.getCause() != null ? t.getCause() : t;
            if (cause instanceof IllegalCallerException) {
                throw new IllegalStateException(
                        "Native access is denied; launch with --enable-native-access=ALL-UNNAMED", cause);
            }
            throw new IllegalStateException("Failed to bind the RustMI native library through FFM at "
                    + System.getProperty("rustmi.native.path"), cause);
        }
        publish(Kind.FFM, reason);
    }

    /**
     * Confirms the loaded library is this build's (record sizes) and that return values come
     * back intact. Argument passing is covered by the in-game shadow modes and parity tests.
     */
    private static void checkLayout(String backend, int input, int output) {
        if (input != RustmiCramming.INPUT_STRIDE || output != RustmiCramming.OUTPUT_STRIDE) {
            throw new IllegalStateException(backend + " ABI check failed: cramming record sizes "
                    + input + "/" + output + ", expected "
                    + RustmiCramming.INPUT_STRIDE + "/" + RustmiCramming.OUTPUT_STRIDE);
        }
    }

    private static void publish(Kind k, String why) {
        detail = why;
        kind = k;
        LOGGER.info("RustMI native backend: {}", describe());
    }
}
