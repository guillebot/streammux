package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.JobDefinition;

public interface JobCommandPublisher {
    void publishDefinition(JobDefinition definition);
    void publishCommand(JobCommand command);
    void publishEvent(JobEvent event);
}
