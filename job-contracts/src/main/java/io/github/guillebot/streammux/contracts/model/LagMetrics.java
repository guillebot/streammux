package io.github.guillebot.streammux.contracts.model;

import com.fasterxml.jackson.annotation.JsonAlias;

public record LagMetrics(
    long inputLag,
    long inputRatePerSecond,
    @JsonAlias("processedCount") long inputCount,
    long outputRatePerSecond,
    long outputCount
) {}
