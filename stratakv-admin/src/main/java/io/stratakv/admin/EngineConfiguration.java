package io.stratakv.admin;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class EngineConfiguration {

    @Bean(destroyMethod = "close")
    StrataKvEngine strataKvEngine(@Value("${stratakv.data-dir:./data}") String dataDir) throws Exception {
        EngineConfig config = EngineConfig.builder(Path.of(dataDir)).build();
        return StrataKvEngine.open(config);
    }
}