package com.auraboot.framework.branding;

/** Provides the immutable brand identity selected when the deployment starts. */
public interface BrandingProvider {

    BrandingIdentity current();
}
