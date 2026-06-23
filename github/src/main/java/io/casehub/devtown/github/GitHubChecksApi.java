package io.casehub.devtown.github;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
@Path("/repos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GitHubChecksApi {

    @GET
    @Path("/{owner}/{repo}/commits/{ref}/check-suites")
    CheckSuitesResponse listCheckSuites(
        @PathParam("owner") String owner,
        @PathParam("repo") String repo,
        @PathParam("ref") String ref,
        @QueryParam("per_page") int perPage);
}
