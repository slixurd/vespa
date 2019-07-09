/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

/**
 * @author gjoranv
 */
class ErrorResponse extends JsonResponse {
    private static Logger log = Logger.getLogger(ErrorResponse.class.getName());

    ErrorResponse(int code, String message) {
        super(code, asErrorJson(message));
    }

    static String asErrorJson(String message) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException e) {
            log.log(WARNING, "Could not encode error message to json:", e);
            return "Could not encode error message to json, check the log for details.";
        }
    }
}
