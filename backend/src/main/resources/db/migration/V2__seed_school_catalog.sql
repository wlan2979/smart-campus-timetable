-- 初始化中国高校教务适配器目录样本（兼容 PostgreSQL 16 与 H2 PostgreSQL 模式）
-- 重要声明：所有未经真实校园账号与实网联调验证的学校均严格标记 verified = FALSE，严禁冒充已支持。

INSERT INTO schools (id, code, name, province, city, school_level, system_type, adapter_id, portal_url, verified, status_note, updated_at)
SELECT 'sch-zf-template', 'STD_ZHENGFANG', '正方教务系统（通用标准适配框架·待分校实测）', '全国', '通用', 'UNDERGRADUATE', 'ZHENGFANG', 'zhengfang_standard', '', FALSE, '已具备正方新版 JSON/HTML 解析框架，具体高校需完成实网登录验证后才可标记为已适配；当前请使用 Excel/PDF 或网页辅助导入。', 1735689600000
WHERE NOT EXISTS (SELECT 1 FROM schools WHERE code = 'STD_ZHENGFANG');

INSERT INTO schools (id, code, name, province, city, school_level, system_type, adapter_id, portal_url, verified, status_note, updated_at)
SELECT 'sch-qz-template', 'STD_QIANGZHI', '强智教务系统（通用标准适配框架·待分校实测）', '全国', '通用', 'UNDERGRADUATE', 'QIANGZHI', 'qiangzhi_standard', '', FALSE, '已具备强智课表 HTML 表格解析框架，具体高校需完成实网登录验证后才可标记为已适配；当前请使用 Excel/PDF 或网页辅助导入。', 1735689600000
WHERE NOT EXISTS (SELECT 1 FROM schools WHERE code = 'STD_QIANGZHI');

INSERT INTO schools (id, code, name, province, city, school_level, system_type, adapter_id, portal_url, verified, status_note, updated_at)
SELECT 'sch-voc-template', 'STD_VOCATIONAL', '高职院校通用教务系统（待分校实测）', '全国', '通用', 'VOCATIONAL', 'UNKNOWN', NULL, '', FALSE, '暂未完成实校授权验证，推荐使用教务导出 Excel / PDF 课表导入或受控网页登录导入。', 1735689600000
WHERE NOT EXISTS (SELECT 1 FROM schools WHERE code = 'STD_VOCATIONAL');
