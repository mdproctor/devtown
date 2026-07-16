package io.casehub.devtown.github;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
public interface GitHubGitApi {

    @GET
    @Path("/{owner}/{repo}/git/ref/{ref}")
    GitRef getRef(@PathParam("owner") String owner,
                  @PathParam("repo") String repo,
                  @PathParam("ref") String ref);

    @POST
    @Path("/{owner}/{repo}/git/refs")
    GitRef createRef(@PathParam("owner") String owner,
                     @PathParam("repo") String repo,
                     Map<String, String> body);

    @DELETE
    @Path("/{owner}/{repo}/git/refs/{ref}")
    void deleteRef(@PathParam("owner") String owner,
                   @PathParam("repo") String repo,
                   @PathParam("ref") String ref);

    @POST
    @Path("/{owner}/{repo}/merges")
    Map<String, Object> merge(@PathParam("owner") String owner,
                              @PathParam("repo") String repo,
                              Map<String, String> body);

    @GET
    @Path("/{owner}/{repo}/git/commits/{sha}")
    GitCommit getCommit(@PathParam("owner") String owner,
                        @PathParam("repo") String repo,
                        @PathParam("sha") String sha);

    @POST
    @Path("/{owner}/{repo}/git/commits")
    GitCommit createCommit(@PathParam("owner") String owner,
                           @PathParam("repo") String repo,
                           Map<String, Object> body);


}
