/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.tencent.devops.process.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.enums.RepositoryType
import com.tencent.devops.common.api.enums.ScmType
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.pipeline.enums.CodeTargetAction
import com.tencent.devops.common.webhook.pojo.code.CodeWebhookEvent
import com.tencent.devops.common.webhook.pojo.code.git.GitEvent
import com.tencent.devops.common.webhook.pojo.code.git.GitReviewEvent
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.engine.dao.PipelineYamlInfoDao
import com.tencent.devops.process.pojo.pipeline.PipelineYamlVersion
import com.tencent.devops.process.pojo.pipeline.PipelineYamlVo
import com.tencent.devops.process.pojo.pipeline.PushPipelineResult
import com.tencent.devops.process.pojo.trigger.PipelineTriggerEvent
import com.tencent.devops.process.trigger.PipelineTriggerEventService
import com.tencent.devops.process.webhook.WebhookEventFactory
import com.tencent.devops.process.yaml.actions.EventActionFactory
import com.tencent.devops.process.yaml.actions.data.PacRepoSetting
import com.tencent.devops.process.yaml.actions.data.YamlTriggerPipeline
import com.tencent.devops.process.yaml.actions.pacActions.data.PipelineYamlManualEvent
import com.tencent.devops.process.yaml.mq.PipelineYamlEnableEvent
import com.tencent.devops.process.yaml.mq.PipelineYamlTriggerEvent
import com.tencent.devops.process.yaml.v2.enums.StreamObjectKind
import com.tencent.devops.repository.api.ServiceRepositoryPacResource
import com.tencent.devops.repository.api.ServiceRepositoryResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class PipelineYamlFacadeService @Autowired constructor(
    private val client: Client,
    private val eventActionFactory: EventActionFactory,
    private val dslContext: DSLContext,
    private val pipelineYamlInfoDao: PipelineYamlInfoDao,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val objectMapper: ObjectMapper,
    private val pipelineYamlSyncService: PipelineYamlSyncService,
    private val webhookEventFactory: WebhookEventFactory,
    private val pipelineTriggerEventService: PipelineTriggerEventService,
    private val pipelineYamlService: PipelineYamlService,
    @Lazy
    private val pipelineYamlRepositoryService: PipelineYamlRepositoryService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineYamlFacadeService::class.java)
    }

    fun enablePac(userId: String, projectId: String, repoHashId: String, scmType: ScmType) {
        logger.info("enable pac|$userId|$projectId|$repoHashId|$scmType")
        val repository = client.get(ServiceRepositoryResource::class).get(
            projectId = projectId,
            repositoryId = repoHashId,
            repositoryType = RepositoryType.ID
        ).data ?: return
        val setting = PacRepoSetting(repository = repository)
        val event = PipelineYamlManualEvent(
            userId = userId,
            projectId = projectId,
            repoHashId = repoHashId,
            scmType = scmType
        )
        val action = eventActionFactory.loadManualEvent(setting = setting, event = event)

        val yamlPathList = action.getYamlPathList()
        pipelineYamlSyncService.initPacSyncDetail(
            projectId = projectId,
            repoHashId = repoHashId,
            yamlPathList = yamlPathList
        )
        // 如果没有Yaml文件则不初始化
        if (yamlPathList.isEmpty()) {
            logger.warn("enable pac,not found ci yaml from git|$projectId|$repoHashId")
            return
        }
        val path2PipelineExists = pipelineYamlInfoDao.getAllByRepo(
            dslContext = dslContext, projectId = projectId, repoHashId = repoHashId
        ).associate {
            it.filePath to YamlTriggerPipeline(
                projectId = it.projectId,
                repoHashId = it.repoHashId,
                filePath = it.filePath,
                pipelineId = it.pipelineId,
                userId = userId
            )
        }
        yamlPathList.forEach {
            action.data.context.pipeline = path2PipelineExists[it.yamlPath]
            action.data.context.yamlFile = it
            pipelineEventDispatcher.dispatch(
                PipelineYamlEnableEvent(
                    projectId = projectId,
                    yamlPath = it.yamlPath,
                    userId = userId,
                    eventStr = objectMapper.writeValueAsString(event),
                    metaData = action.metaData,
                    actionCommonData = action.data.eventCommon,
                    actionContext = action.data.context,
                    actionSetting = action.data.setting
                )
            )
        }
    }

    fun trigger(
        eventObject: CodeWebhookEvent,
        scmType: ScmType,
        requestId: String,
        eventTime: LocalDateTime
    ) {
        try {
            logger.info("pipeline yaml trigger|$requestId|$scmType")
            val action = eventActionFactory.load(eventObject)
            if (action == null) {
                logger.warn("pipeline yaml trigger|request event not support|$eventObject")
                return
            }
            // 初始化setting
            if (!action.data.isSettingInitialized) {
                val externalId = action.data.eventCommon.gitProjectId
                val repository = client.get(ServiceRepositoryPacResource::class).getPacRepository(
                    externalId = externalId, scmType = scmType
                ).data ?: run {
                    logger.info("pipeline yaml trigger|repository not enable pac|$externalId|$scmType")
                    return
                }
                val setting = PacRepoSetting(repository = repository)
                action.data.setting = setting
            }

            action.initCacheData()

            val projectId = action.data.setting.projectId
            val repoHashId = action.data.setting.repoHashId
            val yamlPathList = action.getYamlPathList()
            // 如果没有Yaml文件则不初始化
            if (yamlPathList.isEmpty()) {
                logger.warn("pipeline yaml trigger not found ci yaml from git|$projectId|$repoHashId")
                return
            }
            val matcher = webhookEventFactory.createScmWebHookMatcher(scmType = scmType, event = action.data.event)
            val eventId = pipelineTriggerEventService.getEventId(
                projectId = projectId,
                requestId = requestId,
                eventSource = repoHashId
            )
            val triggerEvent = PipelineTriggerEvent(
                projectId = projectId,
                eventId = eventId,
                triggerType = scmType.name,
                eventSource = repoHashId,
                eventType = matcher.getEventType().name,
                triggerUser = matcher.getUsername(),
                eventDesc = matcher.getEventDesc(),
                requestId = requestId,
                createTime = eventTime
            )
            pipelineTriggerEventService.saveTriggerEvent(triggerEvent)
            action.data.context.eventId = eventId
            val path2PipelineExists = pipelineYamlInfoDao.getAllByRepo(
                dslContext = dslContext, projectId = projectId, repoHashId = repoHashId
            ).associate {
                it.filePath to YamlTriggerPipeline(
                    projectId = it.projectId,
                    repoHashId = it.repoHashId,
                    filePath = it.filePath,
                    pipelineId = it.pipelineId,
                    userId = it.creator
                )
            }
            val eventStr = if (action.metaData.streamObjectKind == StreamObjectKind.REVIEW) {
                objectMapper.writeValueAsString(
                    (action.data.event as GitReviewEvent).copy(
                        objectKind = GitReviewEvent.classType
                    )
                )
            } else {
                objectMapper.writeValueAsString(action.data.event as GitEvent)
            }
            yamlPathList.forEach {
                action.data.context.pipeline = path2PipelineExists[it.yamlPath]
                action.data.context.yamlFile = it
                action.data.context.eventId = eventId
                pipelineEventDispatcher.dispatch(
                    PipelineYamlTriggerEvent(
                        projectId = projectId,
                        yamlPath = it.yamlPath,
                        userId = action.data.getUserId(),
                        eventStr = eventStr,
                        metaData = action.metaData,
                        actionCommonData = action.data.eventCommon,
                        actionContext = action.data.context,
                        actionSetting = action.data.setting,
                        scmType = scmType
                    )
                )
            }
        } catch (ignored: Throwable) {
            logger.warn("pipeline yaml trigger", ignored)
        }
    }

    fun disablePac(userId: String, projectId: String, repoHashId: String, scmType: ScmType) {
        logger.info("disable pac|$userId|$projectId|$repoHashId|$scmType")
        pipelineYamlRepositoryService.disablePac(
            userId = userId,
            projectId = projectId,
            repoHashId = repoHashId
        )
    }

    fun getPipelineYamlInfo(
        projectId: String,
        pipelineId: String,
        version: Int
    ): PipelineYamlVo? {
        return pipelineYamlService.getPipelineYamlVo(
            projectId = projectId,
            pipelineId = pipelineId,
            version = version
        )
    }

    fun getPipelineYamlVersion(
        projectId: String,
        pipelineId: String,
        version: Int
    ): PipelineYamlVersion? {
        return pipelineYamlService.getPipelineYamlVersion(
            projectId = projectId,
            pipelineId = pipelineId,
            version = version
        )
    }

    fun pushYamlFile(
        userId: String,
        projectId: String,
        pipelineId: String,
        version: Int,
        versionName: String?,
        repoHashId: String,
        scmType: ScmType,
        filePath: String,
        content: String,
        commitMessage: String,
        targetAction: CodeTargetAction
    ): PushPipelineResult {
        logger.info("upload yaml file|$userId|$projectId|$repoHashId|$scmType|$version|$versionName")
        val repository = client.get(ServiceRepositoryResource::class).get(
            projectId = projectId,
            repositoryId = repoHashId,
            repositoryType = RepositoryType.ID
        ).data ?: throw ErrorCodeException(
            errorCode = ProcessMessageCode.GIT_NOT_FOUND,
            params = arrayOf(repoHashId)
        )
        if (targetAction != CodeTargetAction.COMMIT_TO_MASTER && versionName.isNullOrBlank()) {
            throw ErrorCodeException(
                errorCode = CommonMessageCode.PARAMETER_IS_NULL,
                params = arrayOf("versionName")
            )
        }
        val setting = PacRepoSetting(repository = repository)
        val event = PipelineYamlManualEvent(
            userId = userId,
            projectId = projectId,
            repoHashId = repoHashId,
            scmType = scmType
        )
        val action = eventActionFactory.loadManualEvent(setting = setting, event = event)
        val gitPushResult = action.pushYamlFile(
            pipelineId = pipelineId,
            filePath = filePath,
            content = content,
            commitMessage = commitMessage,
            targetAction = targetAction,
            versionName = versionName
        )
        pipelineYamlRepositoryService.releaseYamlPipeline(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            version = version,
            action = action,
            gitPushResult = gitPushResult
        )
        return PushPipelineResult(
            projectId = projectId,
            repoHashId = repoHashId,
            filePath = gitPushResult.filePath,
            branch = gitPushResult.branch
        )
    }

    fun deleteBeforeCheck(
        userId: String,
        projectId: String,
        pipelineId: String
    ) {
        val pipelineYamlInfo =
            pipelineYamlService.getPipelineYamlInfo(projectId = projectId, pipelineId = pipelineId) ?: return
        with(pipelineYamlInfo) {
            val repository = client.get(ServiceRepositoryResource::class).get(
                projectId = projectId,
                repositoryId = repoHashId,
                repositoryType = RepositoryType.ID
            ).data ?: throw ErrorCodeException(
                errorCode = ProcessMessageCode.GIT_NOT_FOUND,
                params = arrayOf(repoHashId)
            )
            val setting = PacRepoSetting(repository = repository)
            val event = PipelineYamlManualEvent(
                userId = userId,
                projectId = projectId,
                repoHashId = repoHashId,
                scmType = repository.getScmType()
            )
            val action = eventActionFactory.loadManualEvent(setting = setting, event = event)
            pipelineYamlService.getBranchFilePath(
                projectId = projectId,
                repoHashId = pipelineYamlInfo.repoHashId,
                branch = action.data.context.defaultBranch!!,
                filePath = pipelineYamlInfo.filePath
            )?.let {
                throw ErrorCodeException(
                    errorCode = ProcessMessageCode.ERROR_DELETE_YAML_PIPELINE_IN_DEFAULT_BRANCH
                )
            }
        }
    }
}
