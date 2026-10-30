package com.bocbang.backend.runtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Exposes Linux {@code /proc} memory gauges so operators can watch native C++ GGUF/GGML model allocations
 * (1.3 GB ASR, 2.5 GB LLM refiner) alongside JVM heap and board-wide free RAM.
 */
@Component
public class ProcessMemoryMetrics implements MeterBinder {

    private static final Path PROC_SELF_STATUS = Path.of("/proc/self/status");
    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");

    @Override
    public void bindTo(MeterRegistry registry) {
        if (Files.isReadable(PROC_SELF_STATUS)) {
            Gauge.builder("process.memory.rss", () -> readProcKilobytes(PROC_SELF_STATUS, "VmRSS:") * 1024.0)
                    .baseUnit("bytes")
                    .description("Resident physical memory of this process including JVM and native C++ models")
                    .register(registry);
            Gauge.builder("process.memory.hwm", () -> readProcKilobytes(PROC_SELF_STATUS, "VmHWM:") * 1024.0)
                    .baseUnit("bytes")
                    .description("Peak resident physical memory reached by this process")
                    .register(registry);
        }
        if (Files.isReadable(PROC_MEMINFO)) {
            Gauge.builder("system.memory.total", () -> readProcKilobytes(PROC_MEMINFO, "MemTotal:") * 1024.0)
                    .baseUnit("bytes")
                    .description("Total usable physical RAM on the machine")
                    .register(registry);
            Gauge.builder("system.memory.available", () -> readProcKilobytes(PROC_MEMINFO, "MemAvailable:") * 1024.0)
                    .baseUnit("bytes")
                    .description("Estimated physical RAM available for new allocations without swapping")
                    .register(registry);
        }
    }

    static long readProcKilobytes(Path file, String prefix) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.startsWith(prefix)) {
                    int start = prefix.length();
                    int len = line.length();
                    while (start < len && !Character.isDigit(line.charAt(start))) {
                        start++;
                    }
                    int end = start;
                    while (end < len && Character.isDigit(line.charAt(end))) {
                        end++;
                    }
                    if (end > start) {
                        return Long.parseLong(line.substring(start, end));
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return 0L;
    }
}
