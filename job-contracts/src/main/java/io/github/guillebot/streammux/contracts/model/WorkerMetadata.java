package io.github.guillebot.streammux.contracts.model;

import java.util.Map;

public record WorkerMetadata(String workerId, String topologyName, String localState, Map<String, Object> attributes) {}
