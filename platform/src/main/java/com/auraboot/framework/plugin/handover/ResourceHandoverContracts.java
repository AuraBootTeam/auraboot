package com.auraboot.framework.plugin.handover;

import com.fasterxml.jackson.core.JsonParser;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.util.*;

/** Trusted classpath catalog, deliberately separate from globally retired v1 package identities. */
@Component
public class ResourceHandoverContracts {
    private final Map<String, ResourceHandoverContract> contracts;
    public ResourceHandoverContracts() throws IOException {
        this(new PathMatchingResourcePatternResolver().getResources("classpath*:META-INF/auraboot/resource-handovers/*.json"));
    }
    public ResourceHandoverContracts(Resource[] resources) throws IOException {
        var values = new TreeMap<String, ResourceHandoverContract>();
        var parser = HandoverJson.JSON.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        for (Resource resource : resources) {
            try (var input = resource.getInputStream()) {
                byte[] data = input.readNBytes(1_048_577);
                if (data.length > 1_048_576) throw new IllegalArgumentException("Handover contract exceeds size limit");
                var contract = parser.readValue(data, ResourceHandoverContract.class);
                if (values.putIfAbsent(contract.contractId(), contract) != null)
                    throw new IllegalArgumentException("Duplicate handover contract: " + contract.contractId());
            }
        }
        contracts = Collections.unmodifiableMap(values);
    }
    public Collection<ResourceHandoverContract> all() { return contracts.values(); }
    public ResourceHandoverContract require(String id) {
        var value = contracts.get(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown trusted handover contract");
        return value;
    }
}
