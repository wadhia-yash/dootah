package dev.dootah.adapters;

/** Names shared with bytecode tooling. Deliberately free of parser dependencies. */
public final class AdapterNames {
    private AdapterNames() {}
    public static final String INDEX_PACKAGE = "dev.dootah.generated", INDEX = "DootahAdapterIndex";
    public static final String METHOD_PREFIX = "dootahAdapter_", FACADE_PREFIX = "DootahAdaptersKt_", REGISTRY_PREFIX = "DootahAdapters_";
}
