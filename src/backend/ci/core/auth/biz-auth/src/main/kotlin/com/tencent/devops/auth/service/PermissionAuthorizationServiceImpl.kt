package com.tencent.devops.auth.service

import com.tencent.bk.sdk.iam.constants.ManagerScopesEnum
import com.tencent.devops.auth.constant.AuthI18nConstants
import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.AuthAuthorizationDao
import com.tencent.devops.auth.pojo.vo.ResourceTypeInfoVo
import com.tencent.devops.auth.service.iam.PermissionResourceValidateService
import com.tencent.devops.auth.service.iam.PermissionService
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.AuthResourceType
import com.tencent.devops.common.auth.api.pojo.ResetAllResourceAuthorizationReq
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationResponse
import com.tencent.devops.common.auth.enums.HandoverChannelCode
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.common.auth.rbac.utils.RbacAuthUtils
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.environment.api.ServiceEnvNodeAuthorizationResource
import com.tencent.devops.process.api.service.ServicePipelineAuthorizationResource
import com.tencent.devops.repository.api.ServiceRepositoryAuthorizationResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PermissionAuthorizationServiceImpl constructor(
    private val dslContext: DSLContext,
    private val authAuthorizationDao: AuthAuthorizationDao,
    private val client: Client,
    private val permissionResourceValidateService: PermissionResourceValidateService,
    private val deptService: DeptService,
    private val permissionService: PermissionService
) : PermissionAuthorizationService {
    companion object {
        private val logger = LoggerFactory.getLogger(PermissionAuthorizationServiceImpl::class.java)
        private val needToHandoverResourceTypes = listOf(
            AuthResourceType.PIPELINE_DEFAULT.value,
            AuthResourceType.ENVIRONMENT_ENV_NODE.value,
            AuthResourceType.CODE_REPERTORY.value
        )
    }

    override fun addResourceAuthorization(resourceAuthorizationList: List<ResourceAuthorizationDTO>): Boolean {
        logger.info("add resource authorization:$resourceAuthorizationList")
        addHandoverFromCnName(resourceAuthorizationList)
        authAuthorizationDao.batchAdd(
            dslContext = dslContext,
            resourceAuthorizationList = resourceAuthorizationList
        )
        return true
    }

    override fun migrateResourceAuthorization(resourceAuthorizationList: List<ResourceAuthorizationDTO>): Boolean {
        logger.info("migrate resource authorization:$resourceAuthorizationList")
        addHandoverFromCnName(resourceAuthorizationList)
        authAuthorizationDao.migrate(
            dslContext = dslContext,
            resourceAuthorizationList = resourceAuthorizationList
        )
        return true
    }

    override fun getResourceAuthorization(
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        executePermissionCheck: Boolean
    ): ResourceAuthorizationResponse {
        logger.info("get resource authorization:$projectCode|$resourceType|$resourceCode")
        val record = authAuthorizationDao.get(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        ) ?: throw ErrorCodeException(
            errorCode = AuthMessageCode.ERROR_RESOURCE_AUTHORIZATION_NOT_FOUND
        )
        // 流水线代持人可能会因为被移出用户组，导致失去执行权限。
        if (executePermissionCheck && resourceType == AuthResourceType.PIPELINE_DEFAULT.value) {
            val action = RbacAuthUtils.buildAction(
                authResourceType = AuthResourceType.PIPELINE_DEFAULT,
                authPermission = AuthPermission.EXECUTE
            )
            val isHandoverFromHasExecutePermission = permissionService.validateUserResourcePermissionByRelation(
                userId = record.handoverFrom,
                action = action,
                projectCode = projectCode,
                resourceCode = resourceCode,
                resourceType = resourceType,
                relationResourceType = null
            )
            return record.copy(executePermission = isHandoverFromHasExecutePermission)
        }
        return record
    }

    override fun checkAuthorizationWhenRemoveGroupMember(
        userId: String,
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        memberId: String
    ): Boolean {
        if (resourceType == AuthResourceType.PIPELINE_DEFAULT.value) {
            val record = getResourceAuthorization(
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode,
                executePermissionCheck = true
            )
            return memberId == record.handoverFrom && !record.executePermission!!
        }
        return true
    }

    override fun listResourceAuthorizations(
        condition: ResourceAuthorizationConditionRequest
    ): SQLPage<ResourceAuthorizationResponse> {
        logger.info("list resource authorizations:$condition")
        val record = authAuthorizationDao.list(
            dslContext = dslContext,
            condition = condition
        )
        val count = authAuthorizationDao.count(
            dslContext = dslContext,
            condition = condition
        )
        return SQLPage(
            count = count.toLong(),
            records = record
        )
    }

    override fun modifyResourceAuthorization(resourceAuthorizationList: List<ResourceAuthorizationDTO>): Boolean {
        logger.info("modify resource authorizations:$resourceAuthorizationList")
        addHandoverFromCnName(resourceAuthorizationList)
        authAuthorizationDao.batchUpdate(
            dslContext = dslContext,
            resourceAuthorizationHandoverList = resourceAuthorizationList
        )
        return true
    }

    override fun deleteResourceAuthorization(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        logger.info("delete resource authorizations:$projectCode|$resourceType|$resourceCode")
        authAuthorizationDao.delete(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
        return true
    }

    override fun fixResourceAuthorization(
        projectCode: String,
        resourceType: String,
        resourceAuthorizationIds: List<String>
    ): Boolean {
        logger.info("fix resource authorizations:$projectCode|$resourceType|$resourceAuthorizationIds")
        authAuthorizationDao.delete(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCodes = resourceAuthorizationIds
        )
        return true
    }

    override fun batchModifyHandoverFrom(
        resourceAuthorizationHandoverList: List<ResourceAuthorizationHandoverDTO>
    ): Boolean {
        logger.info("batch modify handoverFrom:$resourceAuthorizationHandoverList")
        addHandoverToCnName(resourceAuthorizationHandoverList)
        authAuthorizationDao.batchUpdate(
            dslContext = dslContext,
            resourceAuthorizationHandoverList = resourceAuthorizationHandoverList
        )
        return true
    }

    override fun resetResourceAuthorizationByResourceType(
        operator: String,
        projectCode: String,
        condition: ResourceAuthorizationHandoverConditionRequest
    ): Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationHandoverDTO>> {
        logger.info("user reset resource authorization|$operator|$projectCode|$condition")
        val result = mutableMapOf<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationHandoverDTO>>()
        if (condition.checkPermission) {
            validateOperatorPermission(
                operator = operator,
                condition = condition
            )
        }
        val resourceAuthorizationList = getResourceAuthorizationList(condition = condition)
        val handoverResult2Records = handoverResourceAuthorizations(
            projectId = projectCode,
            preCheck = condition.preCheck,
            resourceType = condition.resourceType,
            resourceAuthorizationHandoverDTOs = resourceAuthorizationList
        ) ?: return emptyMap()

        val successList = handoverResult2Records[ResourceAuthorizationHandoverStatus.SUCCESS]
        val failedList = handoverResult2Records[ResourceAuthorizationHandoverStatus.FAILED]

        if (!successList.isNullOrEmpty() && !condition.preCheck) {
            logger.info("batch modify handover from|$successList")
            batchModifyHandoverFrom(
                resourceAuthorizationHandoverList = successList
            )
        }
        if (!failedList.isNullOrEmpty()) {
            result[ResourceAuthorizationHandoverStatus.FAILED] = failedList
        }
        return result
    }

    override fun resetAllResourceAuthorization(
        operator: String,
        projectCode: String,
        condition: ResetAllResourceAuthorizationReq
    ): List<ResourceTypeInfoVo> {
        val result = mutableListOf<ResourceTypeInfoVo>()
        needToHandoverResourceTypes.map { resourceType ->
            val handoverResult = resetResourceAuthorizationByResourceType(
                operator = operator,
                projectCode = projectCode,
                condition = ResourceAuthorizationHandoverConditionRequest(
                    projectCode = projectCode,
                    resourceType = resourceType,
                    handoverFrom = condition.handoverFrom,
                    fullSelection = true,
                    preCheck = condition.preCheck,
                    handoverChannel = HandoverChannelCode.MANAGER,
                    handoverTo = condition.handoverTo,
                    checkPermission = condition.checkPermission
                )
            )
            if (!handoverResult[ResourceAuthorizationHandoverStatus.FAILED].isNullOrEmpty()) {
                result.add(
                    ResourceTypeInfoVo(
                        resourceType = resourceType,
                        name = I18nUtil.getCodeLanMessage(
                            messageCode = resourceType + AuthI18nConstants.RESOURCE_TYPE_NAME_SUFFIX
                        )
                    )
                )
            }
        }
        return result
    }

    private fun addHandoverFromCnName(
        resourceAuthorizationList: List<ResourceAuthorizationDTO>
    ) {
        val handoverFromList = resourceAuthorizationList.map { it.handoverFrom ?: "" }.distinct()
        val userId2UserInfo = deptService.listMemberInfos(
            memberIds = handoverFromList,
            memberType = ManagerScopesEnum.USER
        ).associateBy { it.name }
        resourceAuthorizationList.forEach {
            val handoverFrom = it.handoverFrom ?: ""
            it.copyHandoverFromCnName(
                handoverFromCnName = userId2UserInfo[handoverFrom]?.displayName ?: handoverFrom
            )
        }
    }

    private fun addHandoverToCnName(
        resourceAuthorizationList: List<ResourceAuthorizationHandoverDTO>
    ) {
        val handoverToList = resourceAuthorizationList.map { it.handoverTo ?: "" }.distinct()
        val userId2UserInfo = deptService.listMemberInfos(
            memberIds = handoverToList,
            memberType = ManagerScopesEnum.USER
        ).associateBy { it.name }
        resourceAuthorizationList.forEach {
            val handoverTo = it.handoverTo ?: ""
            it.copyHandoverToCnName(
                handoverToCnName = userId2UserInfo[handoverTo]?.displayName ?: handoverTo
            )
        }
    }

    private fun validateOperatorPermission(
        operator: String,
        condition: ResourceAuthorizationHandoverConditionRequest
    ) {
        // 若是在授权管理界面操作，则只要校验操作人是否为管理员即可
        if (condition.handoverChannel == HandoverChannelCode.MANAGER) {
            permissionResourceValidateService.hasManagerPermission(
                userId = operator,
                projectId = condition.projectCode,
                resourceType = AuthResourceType.PROJECT.value,
                resourceCode = condition.projectCode
            )
        } else {
            val record = condition.resourceAuthorizationHandoverList.first()
            permissionResourceValidateService.hasManagerPermission(
                userId = operator,
                projectId = record.projectCode,
                resourceType = record.resourceType,
                record.resourceCode
            )
        }
    }

    private fun getResourceAuthorizationList(
        condition: ResourceAuthorizationHandoverConditionRequest
    ): List<ResourceAuthorizationHandoverDTO> {
        return if (condition.fullSelection) {
            listResourceAuthorizations(
                condition = condition
            ).records.map {
                ResourceAuthorizationHandoverDTO(
                    projectCode = it.projectCode,
                    resourceType = it.resourceType,
                    resourceName = it.resourceName,
                    resourceCode = it.resourceCode,
                    handoverFrom = it.handoverFrom,
                    handoverTime = it.handoverTime,
                    handoverTo = condition.handoverTo
                )
            }
        } else {
            condition.resourceAuthorizationHandoverList
        }
    }

    private fun handoverResourceAuthorizations(
        projectId: String,
        preCheck: Boolean,
        resourceType: String,
        resourceAuthorizationHandoverDTOs: List<ResourceAuthorizationHandoverDTO>
    ): Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationHandoverDTO>>? {
        return when (resourceType) {
            AuthResourceType.PIPELINE_DEFAULT.value -> {
                client.get(ServicePipelineAuthorizationResource::class).resetPipelineAuthorization(
                    projectId = projectId,
                    preCheck = preCheck,
                    resourceAuthorizationHandoverDTOs = resourceAuthorizationHandoverDTOs
                ).data
            }
            AuthResourceType.CODE_REPERTORY.value -> {
                client.get(ServiceRepositoryAuthorizationResource::class).resetRepositoryAuthorization(
                    projectId = projectId,
                    preCheck = preCheck,
                    resourceAuthorizationHandoverDTOs = resourceAuthorizationHandoverDTOs
                ).data
            }
            AuthResourceType.ENVIRONMENT_ENV_NODE.value -> {
                client.get(ServiceEnvNodeAuthorizationResource::class).resetEnvNodeAuthorization(
                    projectId = projectId,
                    preCheck = preCheck,
                    resourceAuthorizationHandoverDTOs = resourceAuthorizationHandoverDTOs
                ).data
            }
            else -> {
                null
            }
        }
    }
}