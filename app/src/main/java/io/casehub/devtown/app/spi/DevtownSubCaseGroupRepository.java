package io.casehub.devtown.app.spi;

import io.casehub.persistence.memory.MemorySubCaseGroupRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DevtownSubCaseGroupRepository extends MemorySubCaseGroupRepository {}
