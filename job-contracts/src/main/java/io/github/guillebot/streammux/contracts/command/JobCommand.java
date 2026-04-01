package io.github.guillebot.streammux.contracts.command;

import io.github.guillebot.streammux.contracts.model.CommandType;
import java.time.Instant;
import java.util.Map;

public record JobCommand(String commandId, String jobId, long jobVersion, CommandType commandType, Instant issuedAt, String issuedBy, Map<String, String> arguments) {}
