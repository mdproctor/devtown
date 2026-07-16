package io.casehub.devtown.github;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;
import java.util.Map;

@RegisterRestClient(configKey = "github-api")
@Path("/repos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GitHubPullRequestApi {

    @GET
    @Path("/{owner}/{repo}/pulls")
    List<Map<String, Object>> listPullRequests(@PathParam("owner") String owner,
                                                @PathParam("repo") String repo,
                                                @QueryParam("head") String head,
                                                @QueryParam("base") String base,
                                                @QueryParam("state") String state);

    @POST
    @Path("/{owner}/{repo}/pulls")
    Map<String, Object> createPullRequest(@PathParam("owner") String owner,
                                          @PathParam("repo") String repo,
                                          Map<String, Object> body);
}
