package io.stratakv.recovery;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CrashRecoveryTest {

    private static final int ITERATIONS = Integer.getInteger("stratakv.crash.iterations", 3);

    @Test
    void killNinePreservesAckedWrites(@TempDir Path dir) throws Exception {
        Path cp = Path.of(System.getProperty("java.class.path"));
        String main = "io.stratakv.crash.CrashWriterMain";
        for (int i = 0; i < ITERATIONS; i++) {
            Path dataDir = dir.resolve("iter-" + i);
            Files.createDirectories(dataDir);
            List<String> acked = new ArrayList<>();
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-cp", cp.toString(), main, dataDir.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("ACK:")) {
                            synchronized (acked) {
                                acked.add(line.substring(4));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            for (int k = 0; k < 150; k++) {
                proc.getOutputStream().write(("key-" + k + "\n").getBytes(StandardCharsets.UTF_8));
                proc.getOutputStream().flush();
                Thread.sleep(6);
                synchronized (acked) {
                    if (acked.size() >= 20) {
                        break;
                    }
                }
            }
            proc.destroyForcibly();
            assertThat(proc.waitFor(5, TimeUnit.SECONDS)).isTrue();
            reader.join(2000);
            assertThat(acked).isNotEmpty();

            EngineConfig config = EngineConfig.builder(dataDir).build();
            try (StrataKvEngine engine = RecoveryService.recover(config)) {
                synchronized (acked) {
                    for (String key : acked) {
                        byte[] value = engine.get(key.getBytes());
                        assertThat(value).isNotNull();
                        assertThat(new String(value)).isEqualTo("payload-" + key);
                    }
                }
            }
        }
    }
}