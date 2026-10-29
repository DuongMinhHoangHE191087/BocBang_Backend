package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.SpeechRecognitionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Loads {@code libbocbang_*.so} from an explicit directory, {@code native/dist/*}, or {@code java.library.path}. */
public final class NativeLoader {

    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private NativeLoader() {
    }

    public static void load(String baseName, String configuredDir) {
        String key = (configuredDir == null ? "" : configuredDir) + ":" + baseName;
        if (LOADED.contains(key)) {
            return;
        }
        synchronized (NativeLoader.class) {
            if (LOADED.contains(key)) {
                return;
            }
            String fileName = System.mapLibraryName(baseName);
            List<Path> candidates = candidateDirs(configuredDir).stream()
                    .map(dir -> dir.resolve(fileName))
                    .toList();
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    System.load(candidate.toAbsolutePath().toString());
                    LOADED.add(key);
                    return;
                }
            }
            try {
                System.loadLibrary(baseName);
                LOADED.add(key);
            } catch (UnsatisfiedLinkError e) {
                throw new SpeechRecognitionException("Native JNI library '" + fileName
                        + "' not found. Checked " + candidates + " and java.library.path. "
                        + "Build it with ./scripts/build-native.sh and set stt.native-lib-dir.", e);
            }
        }
    }

    private static List<Path> candidateDirs(String configuredDir) {
        List<Path> dirs = new ArrayList<>();
        if (configuredDir != null && !configuredDir.isBlank()) {
            dirs.add(Path.of(configuredDir));
        }
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            dirs.add(Path.of("native/dist/android-arm64"));
            dirs.add(Path.of("native/dist/linux-arm64"));
        } else {
            dirs.add(Path.of("native/dist/host"));
        }
        return dirs;
    }
}
