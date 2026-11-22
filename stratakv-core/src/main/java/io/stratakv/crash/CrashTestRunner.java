package io.stratakv.crash;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;
import io.stratakv.recovery.RecoveryService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class CrashTestRunner {

    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        Path cp = Path.of(System.getProperty("java.class.path"));
        String main = "io.stratakv.crash.CrashWriterMain";
        int pass = 0;
        int fail = 0;
        for (int i = 1; i <= iterations; i++) {
            Path dataDir = Files.createTempDirectory("stratakv-crash-");
            try {
                if (runOnce(cp, main, dataDir)) {
                    System.out.println("iter " + i + ": PASS");
                    pass++;
                } else {
                    System.out.println("iter " + i + ": FAIL");
                    fail++;
                }
            } finally {
                deleteRecursively(dataDir);
            }
        }
        System.out.println("Summary: " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static boolean runOnce(Path cp, String main, Path dataDir) throws Exception {
        List<String> acked = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("java", "-cp", cp.toString(), main, dataDir.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
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
        for (int k = 0; k < 200; k++) {
            proc.getOutputStream().write(("key-" + k + "\n").getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().flush();
            Thread.sleep(8);
            synchronized (acked) {
                if (acked.size() >= 25) {
                    break;
                }
            }
        }
        proc.destroyForcibly();
        proc.waitFor(5, TimeUnit.SECONDS);
        reader.join(1000);
        if (acked.isEmpty()) {
            return false;
        }
        EngineConfig config = EngineConfig.builder(dataDir).build();
        try (StrataKvEngine engine = RecoveryService.recover(config)) {
            synchronized (acked) {
                for (String key : acked) {
                    byte[] value = engine.get(key.getBytes());
                    if (value == null || !new String(value).equals("payload-" + key)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}