package io.casehub.devtown.review;

public interface ReviewerAgent {
    /**
     * Returns the ReviewDomain capability constant this agent handles.
     * Used as the COMMAND target and CDI dispatch key.
     */
    String capability();

    /**
     * Handle a review request. Called synchronously; Layer 3 stubs are in-process.
     * Intentional divergence from AML's AgentBehaviour.handle(Message command): we pass
     * the typed domain object to avoid a nullable Message parameter and a qhorus type
     * leak into the port interface.
     */
    ReviewerOutcome handle(PrPayload pr);
}
