package io.casehub.devtown.app;

import io.casehub.devtown.domain.sla.DefaultSlaBreachPolicy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SlaBreachPolicyBean extends DefaultSlaBreachPolicy {}
