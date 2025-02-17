// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.athenz;

import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.jdisc.http.HttpRequest.Method;
import com.yahoo.restapi.Path;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.identifiers.Property;
import com.yahoo.vespa.hosted.controller.api.identifiers.PropertyId;
import com.yahoo.vespa.hosted.controller.api.integration.entity.EntityService;
import com.yahoo.vespa.hosted.controller.athenz.impl.AthenzFacade;
import com.yahoo.vespa.hosted.controller.restapi.ErrorResponse;
import com.yahoo.vespa.hosted.controller.restapi.ResourceResponse;
import com.yahoo.vespa.hosted.controller.restapi.SlimeJsonResponse;
import com.yahoo.yolean.Exceptions;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This API proxies requests to an Athenz server.
 * 
 * @author jonmv
 */
@SuppressWarnings("unused") // Handler
public class AthenzApiHandler extends LoggingRequestHandler {

    private final static Logger log = Logger.getLogger(AthenzApiHandler.class.getName());

    private final AthenzFacade athenz;
    private final EntityService properties;

    public AthenzApiHandler(Context parentCtx, AthenzFacade athenz, Controller controller) {
        super(parentCtx);
        this.athenz = athenz;
        this.properties = controller.serviceRegistry().entityService();
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        Method method = request.getMethod();
        try {
            switch (method) {
                case GET: return get(request);
                default: return ErrorResponse.methodNotAllowed("Method '" + method + "' is unsupported");
            }
        }
        catch (IllegalArgumentException|IllegalStateException e) {
            return ErrorResponse.badRequest(Exceptions.toMessageString(e));
        }
        catch (RuntimeException e) {
            log.log(Level.WARNING, "Unexpected error handling '" + request.getUri() + "'", e);
            return ErrorResponse.internalServerError(Exceptions.toMessageString(e));
        }
    }

    private HttpResponse get(HttpRequest request) {
        Path path = new Path(request.getUri());
        if (path.matches("/athenz/v1")) return root(request);
        if (path.matches("/athenz/v1/domains")) return domainList(request);
        if (path.matches("/athenz/v1/properties")) return properties();

        return ErrorResponse.notFoundError(String.format("No '%s' handler at '%s'", request.getMethod(),
                                                         request.getUri().getPath()));
    }

    private HttpResponse root(HttpRequest request) {
        return new ResourceResponse(request, "domains", "properties");
    }


    private HttpResponse properties() {
        Slime slime = new Slime();
        Cursor response = slime.setObject();
        Cursor array = response.setArray("properties");
        for (Map.Entry<PropertyId, Property> entry : properties.listProperties().entrySet()) {
            Cursor propertyObject = array.addObject();
            propertyObject.setString("propertyid", entry.getKey().id());
            propertyObject.setString("property", entry.getValue().id());
        }
        return new SlimeJsonResponse(slime);
    }

    private HttpResponse domainList(HttpRequest request) {
        Slime slime = new Slime();
        Cursor array = slime.setObject().setArray("data");
        for (AthenzDomain athenzDomain : athenz.getDomainList(request.getProperty("prefix")))
            array.addString(athenzDomain.getName());

        return new SlimeJsonResponse(slime);
    }

}
