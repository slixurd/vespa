/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.http;

import com.yahoo.container.jdisc.HttpResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * @author gjoranv
 */
class JsonResponse extends HttpResponse {
    private final byte[] data;

    JsonResponse(int code, String data) {
        super(code);
        this.data = data.getBytes(Charset.forName(DEFAULT_CHARACTER_ENCODING));
    }

    @Override
    public String getContentType() {
        return "application/json";
    }

    @Override
    public void render(OutputStream outputStream) throws IOException {
        outputStream.write(data);
    }
}
