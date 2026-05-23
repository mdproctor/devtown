package io.casehub.devtown.app.spi;

import io.casehub.persistence.memory.InMemoryEventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DevtownEventLogRepository extends InMemoryEventLogRepository {}
