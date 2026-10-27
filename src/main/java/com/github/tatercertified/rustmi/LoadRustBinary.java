package com.github.tatercertified.rustmi;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;
import com.github.tatercertified.rustmi.utils.HashUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Extracts this platform's native library from the jar (every supported platform ships in
 * it) to {@code libraries/rustmi/} and publishes its absolute path as
 * {@code rustmi.native.path}; both native backends load it from there.
 */
public class LoadRustBinary {
    private static boolean prepared;

    public static synchronized void onPreLaunch() {
        if (prepared) {
            return;
        }
        String osName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);

        String binaryName;
        String outputName;

        if (SystemUtils.IS_OS_WINDOWS) {
            binaryName = switch (arch) {
                case "amd64", "x86_64" -> "rustmi_windows_x86.dll";
                case "aarch64", "arm64" -> "rustmi_windows_arm64.dll";
                default -> throw new IllegalStateException("Unsupported architecture: " + arch);
            };
            outputName = "rustmi";
        } else if (SystemUtils.IS_OS_LINUX) {
            binaryName = switch (arch) {
                case "amd64", "x86_64" -> "librustmi_linux_x86.so";
                case "arm64", "aarch64" -> "librustmi_linux_arm64.so";
                default -> throw new IllegalStateException("Unsupported architecture: " + arch);
            };
            requireGlibc();
            outputName = "librustmi";
        } else if (SystemUtils.IS_OS_MAC) {
            binaryName = switch (arch) {
                case "x86_64" -> "librustmi_mac_x86.dylib";
                case "arm64", "aarch64" -> "librustmi_mac_arm64.dylib";
                default -> throw new IllegalStateException("Unsupported architecture: " + arch);
            };
            outputName = "librustmi";
        } else {
            throw new IllegalStateException("Unsupported OS: " + osName);
        }

        copyNativeLib(binaryName, outputName, FilenameUtils.removeExtension(binaryName));
        prepared = true;
    }

    /** The Linux libraries link against glibc; on a musl JVM they would fail cryptically. */
    private static void requireGlibc() {
        try {
            // The libc this JVM itself runs on is the one dlopen will resolve against.
            // ISO-8859-1 reads every byte: a non-UTF-8 mapped path must not skip the check.
            for (String line : Files.readAllLines(Paths.get("/proc/self/maps"),
                    java.nio.charset.StandardCharsets.ISO_8859_1)) {
                if (line.contains("ld-musl-")) {
                    throw new IllegalStateException("RustMI needs a glibc-based JVM; this one runs on musl "
                            + "(e.g. Alpine). Use a glibc image or JDK build");
                }
            }
        } catch (IOException e) {
            // No procfs: nothing to check.
        }
    }

    private static Path getLibrariesDir() {
        Path libsDir = Paths.get("").toAbsolutePath().resolve("libraries").resolve("rustmi");
        try {
            Files.createDirectories(libsDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create libraries/rustmi directory", e);
        }
        return libsDir;
    }

    private static void copyNativeLib(String binaryName, String outputName, String binaryNameNoExt) {
        try (InputStream inputStream = LoadRustBinary.class.getResourceAsStream("/" + binaryName)) {
            if (inputStream == null) {
                throw new RuntimeException("Resource not found: /" + binaryName);
            }

            String extension = binaryName.substring(binaryName.lastIndexOf('.') + 1);
            Path destinationPath = getLibrariesDir().resolve(outputName + "." + extension);

            if (Files.notExists(destinationPath) || !HashUtils.checkHash(destinationPath, binaryNameNoExt)) {
                // Write a private temp file, verify it, then rename it into place: a second
                // server starting in the same directory never sees (or loads) a half-written
                // library, and a crash mid-copy leaves no truncated one behind.
                Path tmp = Files.createTempFile(destinationPath.getParent(), outputName, ".part");
                try {
                    Files.copy(inputStream, tmp, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        // createTempFile makes the file owner-only (0600); a library written by
                        // Files.copy before was world-readable, and a server started later by
                        // another user must still be able to hash and load it.
                        Files.setPosixFilePermissions(tmp,
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                    } catch (UnsupportedOperationException ignored) {
                        // not a POSIX file system (Windows): default ACLs apply
                    }
                    if (!HashUtils.checkHash(tmp, binaryNameNoExt)) {
                        throw new IOException("extracted " + binaryName + " does not match its bundled SHA-256");
                    }
                    try {
                        Files.move(tmp, destinationPath, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        // E.g. Windows refusing to replace a library another process has loaded:
                        // fine if that file is already this build's library.
                        if (!HashUtils.checkHash(destinationPath, binaryNameNoExt)) {
                            throw e;
                        }
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }

            // Both backends load the library by this absolute path: Nalim through
            // Linker.loadLibrary (registered under its own classloader, where it later looks
            // symbols up), FFM through SymbolLookup.libraryLookup. No -Djava.library.path needed.
            System.setProperty("rustmi.native.path", destinationPath.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
