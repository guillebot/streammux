package io.github.guillebot.streammux.contracts.config;

import java.util.Map;

/**
 * @param rate Probability each input record is forwarded to the output topic, in the closed interval [0, 1]
 *             (e.g. {@code 0.01} ≈ 1%, not {@code 1} for 1%).
 */
public record RandomSamplerConfig(String inputTopic, String outputTopic, double rate, Map<String, String> streamProperties) {}
