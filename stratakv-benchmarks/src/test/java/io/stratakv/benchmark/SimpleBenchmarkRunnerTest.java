package io.stratakv.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class SimpleBenchmarkRunnerTest {

    @Test
    void runsSmallWorkload() {
        assertThatCode(() -> SimpleBenchmarkRunner.main(new String[]{"50"})).doesNotThrowAnyException();
    }
}