package com.tencent.devops.repository.api.github

import com.tencent.devops.common.sdk.github.request.GetPullRequestRequest
import com.tencent.devops.common.sdk.github.request.ListPullRequestFileRequest
import com.tencent.devops.common.sdk.github.response.PullRequestFileResponse
import com.tencent.devops.common.sdk.github.response.PullRequestResponse
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.ws.rs.Consumes
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType

@Api(tags = ["SERVICE_PULL_REQUEST_GITHUB"], description = "服务-github-pull-request")
@Path("/service/github/pullRequest")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ServiceGithubPRResource {

    @ApiOperation("获取PR")
    @POST
    @Path("/getPullRequest")
    fun getPullRequest(
        @ApiParam("用户id", required = true)
        @QueryParam("userId")
        userId: String,
        request: GetPullRequestRequest
    ): PullRequestResponse

    @ApiOperation("PR文件列表")
    @POST
    @Path("/listPullRequestFiles")
    fun listPullRequestFiles(
        @ApiParam("用户id", required = true)
        @QueryParam("userId")
        userId: String,
        request: ListPullRequestFileRequest
    ): List<PullRequestFileResponse>
}
