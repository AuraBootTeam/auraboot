package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.DataAccessor;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;

/** Restricts the registered result handler to the existing governed reader. */
public final class InstalledIntegrationReadOnly {
    private InstalledIntegrationReadOnly() {}
    public static DataAccessor wrap(DataAccessor delegate) {
        return (DataAccessor) Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args) -> {
                    if (!Set.of("getById","query","queryIn","queryPage").contains(method.getName()))
                        throw new SecurityException("Installed integration result is read-only");
                    try { return method.invoke(delegate,args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
}
