package io.stratakv.admin;

import io.stratakv.EngineStats;
import io.stratakv.StrataKvEngine;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class KvController {

    private final StrataKvEngine engine;

    public KvController(StrataKvEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/stats")
    @Operation(summary = "Engine statistics")
    public StatsResponse stats() throws Exception {
        EngineStats s = engine.stats();
        return new StatsResponse(s.sstableCount(), s.sstableBytes(), s.memtableBytes(),
                s.immutableMemtableBytes(), s.bloomFalsePositiveRate(), s.writeAmplification(),
                s.compactionCount(), s.flushCount());
    }

    @PostMapping("/compact")
    @Operation(summary = "Trigger manual compaction")
    public ResponseEntity<Void> compact() throws Exception {
        engine.compact();
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/kv/{key}")
    public ResponseEntity<String> get(@PathVariable("key") String key) throws Exception {
        byte[] value = engine.get(key.getBytes());
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new String(value));
    }

    @PutMapping("/kv/{key}")
    public ResponseEntity<Void> put(@PathVariable("key") String key, @RequestBody String value) throws Exception {
        engine.put(key.getBytes(), value.getBytes());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/kv/{key}")
    public ResponseEntity<Void> delete(@PathVariable("key") String key) throws Exception {
        engine.delete(key.getBytes());
        return ResponseEntity.noContent().build();
    }
}