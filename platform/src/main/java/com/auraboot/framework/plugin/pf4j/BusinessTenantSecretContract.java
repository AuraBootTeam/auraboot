package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.TenantSecretReference;
import com.auraboot.framework.plugin.extension.TenantSecretAccessor;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;

/** Immutable owner lower bound for private tenant inputs; metadata cannot widen the selector set. */
public final class BusinessTenantSecretContract {
    private BusinessTenantSecretContract() {}
    public static Set<TenantSecretReference> references(BusinessResourceAuthorization owner) {
        Set<TenantSecretReference> result;
        try {result=Set.copyOf(owner.businessTenantSecrets());}
        catch(NullPointerException | ClassCastException invalid){throw denied();}
        if(result.size()>8 || ((Set<?>)result).stream().anyMatch(value->!(value instanceof TenantSecretReference)))throw denied();
        return result;
    }
    public static void requireMetadata(Map<String,Object> settings,Set<TenantSecretReference> references) {
        if(references.isEmpty())return;
        if(settings==null || !Boolean.TRUE.equals(settings.get(TenantSecretAccessor.OPT_IN_HANDLER_PARAM)))throw denied();
        var services=new HashSet<String>();var fields=new HashSet<String>();
        for(var reference:references){services.add(reference.serviceType());fields.add(reference.field());}
        if(!exact(settings.get(TenantSecretAccessor.SERVICE_TYPES_HANDLER_PARAM),services)
                || !exact(settings.get(TenantSecretAccessor.FIELDS_HANDLER_PARAM),fields))throw denied();
    }
    private static boolean exact(Object raw,Set<String> expected) {
        return raw instanceof List<?> values && values.size()==expected.size() && new HashSet<>(values).equals(expected);
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("Private tenant inputs differ from the business owner contract");}
}
