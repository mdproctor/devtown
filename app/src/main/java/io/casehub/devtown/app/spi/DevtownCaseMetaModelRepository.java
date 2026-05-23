package io.casehub.devtown.app.spi;

import io.casehub.persistence.memory.InMemoryCaseMetaModelRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DevtownCaseMetaModelRepository extends InMemoryCaseMetaModelRepository {}
