package io.github.guillebot.streammux.contracts.model;

public record LagMetrics(long inputLag, long outputRatePerSecond, long processedCount) {}
