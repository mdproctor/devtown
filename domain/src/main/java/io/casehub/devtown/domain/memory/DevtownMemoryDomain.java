package io.casehub.devtown.domain.memory;

import io.casehub.neocortex.memory.MemoryDomain;

public final class DevtownMemoryDomain {
    public static final MemoryDomain SOFTWARE_REVIEW = new MemoryDomain("software-review");

    public static final String CONTRIBUTOR_PREFIX = "contributor:";
    public static final String REVIEWER_PREFIX = "reviewer:";
    public static final String MODULE_PREFIX = "module:";

    private DevtownMemoryDomain() {}
}
