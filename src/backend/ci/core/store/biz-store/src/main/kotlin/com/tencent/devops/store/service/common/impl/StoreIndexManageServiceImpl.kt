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
 */

package com.tencent.devops.store.service.common.impl

import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.model.store.tables.records.TStoreIndexBaseInfoRecord
import com.tencent.devops.model.store.tables.records.TStoreIndexElementDetailRecord
import com.tencent.devops.model.store.tables.records.TStoreIndexLevelInfoRecord
import com.tencent.devops.model.store.tables.records.TStoreIndexResultRecord
import com.tencent.devops.process.api.service.ServiceBuildResource
import com.tencent.devops.store.constant.StoreConstants.STORE_CODE
import com.tencent.devops.store.constant.StoreConstants.STORE_INDEX_CODE
import com.tencent.devops.store.constant.StoreConstants.STORE_INDEX_DESCRIPTION
import com.tencent.devops.store.constant.StoreConstants.STORE_INDEX_ICON_CSS_VALUE
import com.tencent.devops.store.constant.StoreConstants.STORE_INDEX_ICON_TIPS
import com.tencent.devops.store.constant.StoreConstants.STORE_INDEX_ICON_URL
import com.tencent.devops.store.constant.StoreConstants.STORE_INDEX_LEVEL_NAME
import com.tencent.devops.store.constant.StoreConstants.STORE_INDEX_NAME
import com.tencent.devops.store.dao.common.StoreIndexManageInfoDao
import com.tencent.devops.store.dao.common.StorePipelineRelDao
import com.tencent.devops.store.dao.common.StoreProjectRelDao
import com.tencent.devops.store.pojo.common.StoreIndexBaseInfo
import com.tencent.devops.store.pojo.common.StoreIndexInfo
import com.tencent.devops.store.pojo.common.enums.IndexOperationTypeEnum
import com.tencent.devops.store.pojo.common.enums.StorePipelineBusTypeEnum
import com.tencent.devops.store.pojo.common.enums.StoreTypeEnum
import com.tencent.devops.store.pojo.common.index.CreateIndexComputeDetailRequest
import com.tencent.devops.store.pojo.common.index.StoreIndexCreateRequest
import com.tencent.devops.store.pojo.common.index.StoreIndexPipelineInitRequest
import com.tencent.devops.store.service.common.StoreIndexManageService
import com.tencent.devops.store.service.common.StoreIndexPipelineService
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class StoreIndexManageServiceImpl @Autowired constructor(
    private val dslContext: DSLContext,
    private val storeIndexPipelineService: StoreIndexPipelineService,
    private val storePipelineRelDao: StorePipelineRelDao,
    private val storeIndexManageInfoDao: StoreIndexManageInfoDao,
    private val storeProjectRelDao: StoreProjectRelDao,
    private val client: Client,
    private val redisOperation: RedisOperation
) : StoreIndexManageService {

    override fun add(userId: String, storeIndexCreateRequest: StoreIndexCreateRequest): Result<Boolean> {
        //管理员权限校验

        val indexCode = storeIndexCreateRequest.indexCode
        // 验证指标代码是否已存在
        val validateResult = validateAddStoreIndexCreateReq(storeIndexCreateRequest)
        if (validateResult != null) {
            logger.info("the validateResult is :$validateResult")
            return validateResult
        }
        val storeIndexBaseInfoId = UUIDUtil.generate()
        val tStoreIndexBaseInfoRecord = TStoreIndexBaseInfoRecord()
        tStoreIndexBaseInfoRecord.id = storeIndexBaseInfoId
        tStoreIndexBaseInfoRecord.storeType = storeIndexCreateRequest.storeType.type.toByte()
        tStoreIndexBaseInfoRecord.indexCode = indexCode
        tStoreIndexBaseInfoRecord.indexName = storeIndexCreateRequest.indexName
        tStoreIndexBaseInfoRecord.iconUrl = storeIndexCreateRequest.iconUrl
        tStoreIndexBaseInfoRecord.description = storeIndexCreateRequest.description
        tStoreIndexBaseInfoRecord.operationType = storeIndexCreateRequest.operationType.name
        tStoreIndexBaseInfoRecord.executeTimeType = storeIndexCreateRequest.executeTimeType.name
        tStoreIndexBaseInfoRecord.creator = userId
        tStoreIndexBaseInfoRecord.modifier = userId
        tStoreIndexBaseInfoRecord.createTime = LocalDateTime.now()
        tStoreIndexBaseInfoRecord.updateTime = LocalDateTime.now()
        // 创建指标等级
        val indexLevelInfoRecords = storeIndexCreateRequest.levelInfos.map {
            val tStoreIndexLevelInfo = TStoreIndexLevelInfoRecord()
            tStoreIndexLevelInfo.id = UUIDUtil.generate()
            tStoreIndexLevelInfo.levelName = it.levelName
            tStoreIndexLevelInfo.iconCssValue = it.iconCssValue
            tStoreIndexLevelInfo.indexId = storeIndexBaseInfoId
            tStoreIndexLevelInfo.creator = userId
            tStoreIndexLevelInfo.modifier = userId
            tStoreIndexLevelInfo.createTime = LocalDateTime.now()
            tStoreIndexLevelInfo.updateTime = LocalDateTime.now()
            tStoreIndexLevelInfo
        }
        storeIndexManageInfoDao.batchCreateStoreIndexLevelInfo(dslContext, indexLevelInfoRecords)
        // 如果运算类型为插件则需要初始化流水线
        if (storeIndexCreateRequest.operationType == IndexOperationTypeEnum.ATOM) {
            tStoreIndexBaseInfoRecord.atomCode = storeIndexCreateRequest.atomCode
            storeIndexManageInfoDao.createStoreIndexBaseInfo(dslContext, tStoreIndexBaseInfoRecord)
            storeIndexPipelineService.initStoreIndexPipeline(
                userId = userId,
                storeIndexPipelineInitRequest = StoreIndexPipelineInitRequest(
                    indexCode = indexCode,
                    atomCode = storeIndexCreateRequest.atomCode!!,
                    executeTimeType = storeIndexCreateRequest.executeTimeType,
                    storeType = storeIndexCreateRequest.storeType
                )
            )
        } else {
            storeIndexManageInfoDao.createStoreIndexBaseInfo(dslContext, tStoreIndexBaseInfoRecord)
        }
        return Result(true)
    }

    override fun delete(userId: String, indexId: String): Result<Boolean> {
        //管理员权限校验

        val indexBaseInfo = storeIndexManageInfoDao.getStoreIndexBaseInfoById(dslContext, indexId) ?: return Result(false)
        val atomCode = indexBaseInfo.atomCode
        val storePipelineRelRecord = storePipelineRelDao.getStorePipelineRel(
            dslContext = dslContext,
            storeCode = atomCode,
            storeType = StoreTypeEnum.ATOM,
            busType = StorePipelineBusTypeEnum.INDEX
        )
        if (storePipelineRelRecord != null) {
            val pipelineId = storePipelineRelRecord.pipelineId
            // 查询插件对应的初始化项目
            val initProjectCode = storeProjectRelDao.getInitProjectCodeByStoreCode(
                dslContext = dslContext,
                storeCode = atomCode,
                storeType = StoreTypeEnum.ATOM.type.toByte()
            )!!
            val pipelineBuildInfo = client.get(ServiceBuildResource::class).getPipelineLatestBuildByIds(
                initProjectCode,
                listOf(pipelineId)
            ).data?.get(storePipelineRelRecord.pipelineId)
            pipelineBuildInfo?.let {
                if (it.status == BuildStatus.PREPARE_ENV.statusName || it.status == BuildStatus.RUNNING.statusName) {
                    client.get(ServiceBuildResource::class).manualShutdown(
                        userId = userId,
                        projectId = initProjectCode,
                        pipelineId = pipelineId,
                        buildId = it.buildId,
                        channelCode = ChannelCode.AM
                    )
                }
            }
        }
        dslContext.transaction { t ->
            val context = DSL.using(t)
            storeIndexManageInfoDao.deleteTStoreIndexLevelInfo(context, indexId)
            storeIndexManageInfoDao.deleteTStoreIndexBaseInfo(context, indexId)
        }
        // 考虑到数据量的问题，使用定时任务处理存量数据
        redisOperation.sadd("deleteStoreIndexResultKey", indexId)
        return Result(true)
    }


    override fun list(userId: String, keyWords: String?, page: Int, pageSize: Int): Page<StoreIndexBaseInfo> {
        //管理员权限校验


        val count = storeIndexManageInfoDao.count(dslContext, keyWords)
        val records = storeIndexManageInfoDao.list(dslContext, keyWords, page, pageSize)
        // 计算任务插件通过Redis实时上报计算进度
        records.forEach {
            val totalTaskNum = redisOperation.get("${it.indexCode}_totalTaskNum")
            val finishTaskNum = redisOperation.get("${it.indexCode}_finishTaskNum")
            if (totalTaskNum != null && finishTaskNum != null) {
                it.totalTaskNum = totalTaskNum.toInt()
                it.finishTaskNum = finishTaskNum.toInt()
            }
        }
        return Page(
            count = count,
            page = page,
            pageSize = pageSize,
            records = records
        )
    }

    override fun getStoreIndexInfosByStoreCodes(
        storeType: StoreTypeEnum,
        storeCodes: List<String>
    ): Map<String, List<StoreIndexInfo>> {
        val storeIndexInfosMap = mutableMapOf<String, List<StoreIndexInfo>>()
        val storeIndexInfosRecords =
            storeIndexManageInfoDao.getStoreIndexInfosByStoreCodes(dslContext, storeType, storeCodes)
        storeIndexInfosRecords.forEach {
            val storeCode = it[STORE_CODE] as String
            val storeIndexInfos =
                storeIndexInfosMap[storeCode]?.toMutableList() ?: emptyList<StoreIndexInfo>().toMutableList()
            storeIndexInfos.add(
                StoreIndexInfo(
                    indexCode = it[STORE_INDEX_CODE] as String,
                    indexName = it[STORE_INDEX_NAME] as String,
                    iconUrl = it[STORE_INDEX_ICON_URL] as String,
                    description = it[STORE_INDEX_DESCRIPTION] as String,
                    indexLevelName = it[STORE_INDEX_LEVEL_NAME] as String,
                    iconColor = it[STORE_INDEX_ICON_CSS_VALUE] as String,
                    hover = it[STORE_INDEX_ICON_TIPS].toString().replace("\\","")
                )
            )
            storeIndexInfosMap[storeCode] = storeIndexInfos
        }

        return storeIndexInfosMap
    }

    override fun getStoreIndexInfosByStoreCode(
        storeType: StoreTypeEnum,
        storeCode: String
    ): List<StoreIndexInfo> {
        return getStoreIndexInfosByStoreCodes(storeType, listOf(storeCode))[storeCode]!!
    }

    override fun createIndexComputeDetail(
        userId: String,
        createIndexComputeDetailRequest: CreateIndexComputeDetailRequest
    ): Result<Boolean> {
        val levelId = storeIndexManageInfoDao.getStoreIndexLevelInfo(
            dslContext,
            createIndexComputeDetailRequest.indexId,
            createIndexComputeDetailRequest.LevelName
        )?.id
        val tStoreIndexResultRecord = TStoreIndexResultRecord()
        tStoreIndexResultRecord.id = UUIDUtil.generate()
        tStoreIndexResultRecord.indexId = createIndexComputeDetailRequest.indexId
        tStoreIndexResultRecord.indexCode = createIndexComputeDetailRequest.indexCode
        tStoreIndexResultRecord.storeCode = createIndexComputeDetailRequest.storeCode
        tStoreIndexResultRecord.storeType = createIndexComputeDetailRequest.storeType.type.toByte()
        tStoreIndexResultRecord.iconTips = createIndexComputeDetailRequest.iconTips
        tStoreIndexResultRecord.levelId = levelId
        tStoreIndexResultRecord.creator = userId
        tStoreIndexResultRecord.modifier = userId
        tStoreIndexResultRecord.updateTime = LocalDateTime.now()
        tStoreIndexResultRecord.createTime = LocalDateTime.now()
        storeIndexManageInfoDao.batchCreateStoreIndexResult(dslContext, listOf(tStoreIndexResultRecord))
        val tStoreIndexElementDetailRecords = createIndexComputeDetailRequest.elementInfos.map {
            val tStoreIndexElementDetailRecord = TStoreIndexElementDetailRecord()
            tStoreIndexElementDetailRecord.id = UUIDUtil.generate()
            tStoreIndexElementDetailRecord.storeCode = createIndexComputeDetailRequest.storeCode
            tStoreIndexElementDetailRecord.storeType = createIndexComputeDetailRequest.storeType.type.toByte()
            tStoreIndexElementDetailRecord.indexId = createIndexComputeDetailRequest.indexId
            tStoreIndexElementDetailRecord.indexCode = createIndexComputeDetailRequest.indexCode
            tStoreIndexElementDetailRecord.elementName = it.elementName
            tStoreIndexElementDetailRecord.elementValue = it.elementValue
            tStoreIndexElementDetailRecord.remark = it.remark
            tStoreIndexElementDetailRecord.creator = userId
            tStoreIndexElementDetailRecord.modifier = userId
            tStoreIndexElementDetailRecord.updateTime = LocalDateTime.now()
            tStoreIndexElementDetailRecord.createTime = LocalDateTime.now()
            tStoreIndexElementDetailRecord
        }
        storeIndexManageInfoDao.batchCreateElementDetail(dslContext, tStoreIndexElementDetailRecords)
        return Result(true)
    }

    private fun validateAddStoreIndexCreateReq(
        storeIndexCreateRequest: StoreIndexCreateRequest
    ): Result<Boolean>? {
        val indexCode = storeIndexCreateRequest.indexCode
        // 判断指标代码是否存在
        val codeCount =
            storeIndexManageInfoDao.getStoreIndexBaseInfoByCode(dslContext, storeIndexCreateRequest.storeType, indexCode)
        if (codeCount > 0) {
            // 抛出错误提示
            return MessageCodeUtil.generateResponseDataObject(
                CommonMessageCode.PARAMETER_IS_EXIST,
                arrayOf(indexCode)
            )
        }
        val indexName = storeIndexCreateRequest.indexName
        // 判断指标名称是否存在
        val nameCount =
            storeIndexManageInfoDao.getStoreIndexBaseInfoByName(dslContext, storeIndexCreateRequest.storeType, indexName)
        if (nameCount > 0) {
            // 抛出错误提示
            return MessageCodeUtil.generateResponseDataObject(
                CommonMessageCode.PARAMETER_IS_EXIST,
                arrayOf(indexName)
            )
        }
        return null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(StoreIndexManageServiceImpl::class.java)
    }
}