package com.github.tatercertified.rustmi.utils;

import com.github.tatercertified.rustmi.Config;
import com.github.tatercertified.rustmi.Rustmi;

import java.io.*;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtils {

    public static boolean checkHash(Path libPath, String currentOSLibName) {
        File libRustmi = new File(libPath.toUri());
        String correctHash = getLibHash(currentOSLibName);
        String presentHash = getFileHash(libRustmi);
        if (!correctHash.equals(presentHash)) {
            // Null-safe: this runs from mixin-plugin onLoad where Config may
            // not be initialised yet; a debug-gated log must never throw.
            if (Config.getInstance() != null && Config.getInstance().debug()) {
                Rustmi.LOGGER.info("Outdated Rustmi Binary Found. Updating...");
            }
            return false;
        }
        return true;
    }

    private static String getLibHash(String binaryName) {
        try (InputStream inputStream = HashUtils.class.getResourceAsStream("/" + binaryName + ".hash")) {
            if (inputStream == null) {
                throw new IOException("Hash file not found in resources.");
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                return reader.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + binaryName + ".hash", e);
        }
    }

    private static String getFileHash(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             DigestInputStream dis = new DigestInputStream(fis, MessageDigest.getInstance("SHA-256"))) {

            byte[] buffer = new byte[8192];
            while (dis.read(buffer) != -1) {}

            byte[] hashBytes = dis.getMessageDigest().digest();
            return bytesToHex(hashBytes);

        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to compute hash for file: " + file, e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
}
