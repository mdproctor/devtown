package io.casehub.devtown.github;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.Map;

@RegisterRestClient(configKey = "github-api")
@Path("/repos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GitHubMergeApi {

    @PUT
    @Path("/{owner}/{repo}/pulls/{pull_number}/merge")
    Map<String, Object> merge(
        @PathParam("owner") String owner,
        @PathParam("repo") String repo,
        @PathParam("pull_number") int pullNumber,
        Map<String, Object> body
    );
}
