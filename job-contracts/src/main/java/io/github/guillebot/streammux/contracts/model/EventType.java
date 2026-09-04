package io.github.guillebot.streammux.contracts.model;

public enum EventType {
    SESSION, CREATED, UPDATED, CLAIMED, STARTED, HEARTBEAT, PAUSED, RESUMED, FAILED, RELEASED, STOPPED, DELETED,
    LAG_ALERT, LAG_RECOVERED
}
