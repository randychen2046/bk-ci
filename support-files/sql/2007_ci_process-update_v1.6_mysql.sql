USE devops_ci_process;
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS ci_process_schema_update;

DELIMITER <CI_UBF>

CREATE PROCEDURE ci_process_schema_update()
BEGIN

    DECLARE db VARCHAR(100);
    SET AUTOCOMMIT = 0;
    SELECT DATABASE() INTO db;

	IF NOT EXISTS(SELECT 1
                      FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = db
                        AND TABLE_NAME = 'T_TEMPLATE_INSTANCE_BASE'
                        AND COLUMN_NAME = 'TEMPLATE_ID') THEN
        ALTER TABLE T_TEMPLATE_INSTANCE_BASE ADD COLUMN `TEMPLATE_ID` VARCHAR(32) DEFAULT '' COMMENT '模板ID';
    END IF;

    IF NOT EXISTS(SELECT 1
                      FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = db
                        AND TABLE_NAME = 'T_TEMPLATE_PIPELINE'
                        AND COLUMN_NAME = 'DELETED') THEN
        ALTER TABLE T_TEMPLATE_PIPELINE ADD COLUMN `DELETED` bit(1) DEFAULT b'0' COMMENT '流水线已被软删除';
    END IF;

    IF NOT EXISTS(SELECT 1
                      FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = db
                        AND TABLE_NAME = 'T_PROJECT_PIPELINE_CALLBACK'
                        AND COLUMN_NAME = 'ENABLE') THEN
        ALTER TABLE T_PROJECT_PIPELINE_CALLBACK ADD COLUMN `ENABLE` bit(1) NOT NULL DEFAULT b'1' COMMENT '启用';
    END IF;

    COMMIT;
END <CI_UBF>
DELIMITER ;
COMMIT;
CALL ci_process_schema_update();
