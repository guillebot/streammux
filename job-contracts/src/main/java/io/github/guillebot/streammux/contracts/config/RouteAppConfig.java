package io.github.guillebot.streammux.contracts.config;

import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import java.util.List;
import java.util.Map;

public record RouteAppConfig(String inputTopic, PayloadFormat inputFormat, PayloadFormat outputFormat, String protobufSchemaSubject, List<RouteDefinition> routes, Map<String, String> streamProperties, Map<String, String> serdeProperties) {}
