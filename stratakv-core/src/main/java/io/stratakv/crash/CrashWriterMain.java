package io.stratakv.crash;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

public final class CrashWriterMain {

    private CrashWriterMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: CrashWriterMain <dataDir>");
            System.exit(2);
        }
        Path dataDir = Path.of(args[0]);
        EngineConfig config = EngineConfig.builder(dataDir)
                .memtableThresholdBytes(4 * 1024)
                .build();
        try (StrataKvEngine engine = StrataKvEngine.open(config)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if ("QUIT".equalsIgnoreCase(line)) {
                    break;
                }
                String key = line;
                String value = "payload-" + key;
                engine.put(key.getBytes(), value.getBytes());
                System.out.println("ACK:" + key);
                System.out.flush();
            }
        }
    }
}