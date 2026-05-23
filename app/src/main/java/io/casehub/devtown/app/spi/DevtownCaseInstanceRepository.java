package io.casehub.devtown.app.spi;

import io.casehub.persistence.memory.InMemoryCaseInstanceRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DevtownCaseInstanceRepository extends InMemoryCaseInstanceRepository {}
