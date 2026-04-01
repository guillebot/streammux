package io.github.guillebot.streammux.contracts.event;

import io.github.guillebot.streammux.contracts.model.EventType;
import java.time.Instant;
import java.util.Map;

public record JobEvent(String eventId, String jobId, long jobVersion, EventType eventType, Instant eventTime, String siteId, String instanceId, String message, Map<String, Object> attributes) {}
