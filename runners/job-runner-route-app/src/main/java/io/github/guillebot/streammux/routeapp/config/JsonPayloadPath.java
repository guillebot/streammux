package io.github.guillebot.streammux.routeapp.config;

import com.fasterxml.jackson.databind.JsonNode;

final class JsonPayloadPath {

    private JsonPayloadPath() {}

    static JsonNode resolve(JsonNode payload, String path) {
        if (path.startsWith("/")) {
            return payload.at(path);
        }

        JsonNode current = payload;
        int index = 0;
        while (index < path.length()) {
            int segmentStart = index;
            while (index < path.length() && path.charAt(index) != '.' && path.charAt(index) != '[') {
                index++;
            }

            if (segmentStart < index) {
                current = current.path(path.substring(segmentStart, index));
            }

            while (index < path.length() && path.charAt(index) == '[') {
                int endBracket = path.indexOf(']', index);
                if (endBracket < 0) {
                    return current.path("__invalid_path__");
                }
                int arrayIndex = Integer.parseInt(path.substring(index + 1, endBracket));
                current = current.path(arrayIndex);
                index = endBracket + 1;
            }

            if (index < path.length() && path.charAt(index) == '.') {
                index++;
            }
        }
        return current;
    }
}
