package com.campus.timetable.schooladapters

import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.engine.ConflictDetectionEngine
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.model.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.charset.Charset
import java.time.LocalDate

class SchoolAdaptersTest {

    private lateinit var mockServer: MockWebServer
    private val zhengfang = ZhengfangSchoolAdapter()
    private val qiangzhi = QiangzhiSchoolAdapter()
    private val newUrp = NewUrpSchoolAdapter()
    private val oldUrp = OldUrpSchoolAdapter()
    private val eams = EamsSchoolAdapter()
    private val registry = SchoolAdapterRegistry(zhengfang, qiangzhi, newUrp, oldUrp, eams)
    private lateinit var webParser: WebTimetableParser

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        webParser = WebTimetableParser(OkHttpClient(), zhengfang, qiangzhi, newUrp, oldUrp, eams)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `unverified school must never be reported as supported`() {
        val school = School(
            code = "10001",
            name = "测试大学",
            province = "北京市",
            city = "北京",
            schoolLevel = SchoolLevel.UNDERGRADUATE,
            systemType = EduSystemType.ZHENGFANG,
            adapterId = "zhengfang_standard",
            verified = false
        )
        val lookup = registry.resolveForSchool(school)
        assertTrue(lookup is AdapterLookupResult.UnverifiedFramework)
        val msg = (lookup as AdapterLookupResult.UnverifiedFramework).message
        assertTrue(msg.contains(SchoolSupportStatus.UNSUPPORTED.label))
        assertTrue(msg.contains("Excel"))
    }

    @Test
    fun `zhengfang json adapter parses courses weeks and pe type accurately`() {
        val payload = """
            {
              "kbList": [
                {
                  "kcmc": "高等数学A",
                  "xm": "李明",
                  "cdmc": "教A-301",
                  "xqj": "1",
                  "jcs": "1-2",
                  "zcd": "1-16周"
                },
                {
                  "kcmc": "大学体育(羽毛球)",
                  "xm": "王教练",
                  "cdmc": "综合体育馆",
                  "xqj": "3",
                  "jcs": "5-6",
                  "zcd": "1-15周(单)"
                }
              ]
            }
        """.trimIndent()
        val result = zhengfang.parseTimetablePayload(payload, isJson = true)
        assertTrue(result is AdapterParseResult.Success)
        val list = (result as AdapterParseResult.Success).candidates
        assertEquals(2, list.size)
        assertEquals("高等数学A", list[0].name)
        assertEquals(WeekRuleType.ODD, list[1].weekRuleType)
        assertEquals(CourseType.PHYSICAL_EDUCATION, list[1].courseType)
    }

    @Test
    fun `qiangzhi html adapter parses kbtable cells`() {
        val html = """
            <html><body>
            <table id="kbtable">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr>
                <td>第1-2节</td>
                <td><div class="kbcontent">操作系统<font title="老师">陈教授</font><font title="周次(节次)">1-16周(双)[01-02节]</font><font title="教室">计科楼201</font></div></td>
                <td></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()
        val result = qiangzhi.parseTimetablePayload(html, isJson = false)
        assertTrue(result is AdapterParseResult.Success)
        val list = (result as AdapterParseResult.Success).candidates
        assertEquals(1, list.size)
        assertEquals("操作系统", list.first().name)
        assertEquals("陈教授", list.first().teacher)
        assertEquals("计科楼201", list.first().location)
        assertEquals(WeekRuleType.EVEN, list.first().weekRuleType)
    }

    @Test
    fun `web parser handles GBK encoding, login wall, and SPA JS pages accurately`() = runTest {
        // 1. GBK 编码公开网页课表
        val gbkHtml = """
            <html><head><meta charset="GBK"></head><body>
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>第3-4节</td><td>计算机网络<br>张老师<br>1-16周<br>主楼405</td><td></td><td></td></tr>
            </table>
            </body></html>
        """.trimIndent()
        val gbkBytes = gbkHtml.toByteArray(Charset.forName("GBK"))
        mockServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=GBK")
                .setBody(Buffer().write(gbkBytes))
        )
        val okOutcome = webParser.fetchAndParseUrl(mockServer.url("/kb.html").toString())
        assertTrue(okOutcome is WebParseOutcome.Success)
        val okCourses = (okOutcome as WebParseOutcome.Success).candidates
        assertEquals(1, okCourses.size)
        assertEquals("计算机网络", okCourses.first().name)
        assertEquals("张老师", okCourses.first().teacher)
        assertEquals("主楼405", okCourses.first().location)

        // 2. 需要登录的教务页面
        mockServer.enqueue(
            MockResponse().setBody("<html><body><form><input type='text' name='user'/><input type='password' name='pwd'/></form></body></html>")
        )
        val loginOutcome = webParser.fetchAndParseUrl(mockServer.url("/login").toString())
        assertTrue(loginOutcome is WebParseOutcome.LoginRequired)

        // 3. 纯 JS 动态渲染 SPA 页面
        mockServer.enqueue(
            MockResponse().setBody("<html><body><div id='app'></div><script src='/a.js'></script><script src='/b.js'></script></body></html>")
        )
        val jsOutcome = webParser.fetchAndParseUrl(mockServer.url("/spa").toString())
        assertTrue(jsOutcome is WebParseOutcome.DynamicJsPageHint)
    }

    @Test
    fun `non timetable page with stray weekday text must not produce fake courses`() {
        val homepage = """
            <html><body>
            <h1>教务处首页</h1>
            <table>
              <tr><td>通知公告</td><td>2026-11-27 关于选课的通知（星期一 第1节 调整）</td></tr>
              <tr><td>快速链接</td><td>学生课表</td></tr>
            </table>
            <p>教务管理系统登录入口。办公时间：星期一至星期五。</p>
            </body></html>
        """.trimIndent()
        val outcome = webParser.parseRenderedHtml(homepage, "https://jwc.example.edu.cn/", fromWebView = true)
        assertTrue("教务处首页不应被识别出课程：$outcome", outcome is WebParseOutcome.Failed)
        val message = (outcome as WebParseOutcome.Failed).message
        assertTrue(message.contains("学生课表"))
    }

    @Test
    fun `colspan day headers map cells to correct weekdays`() {
        val html = """
            <html><body>
            <table>
              <tr><th>节次</th><th colspan="2">星期一</th><th colspan="2">星期二</th><th colspan="2">星期三</th></tr>
              <tr>
                <td>第1-2节</td>
                <td>高等数学<br>1-16周</td><td></td>
                <td>大学英语<br>1-16周</td><td></td>
                <td>大学体育<br>1-16周</td><td></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()
        val outcome = webParser.parseRenderedHtml(html, "https://example.edu.cn/kb", fromWebView = true)
        assertTrue(outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(3, courses.size)
        assertEquals(1, courses.first { it.name == "高等数学" }.dayOfWeek)
        assertEquals(2, courses.first { it.name == "大学英语" }.dayOfWeek)
        assertEquals(3, courses.first { it.name == "大学体育" }.dayOfWeek)
    }

    @Test
    fun `rowspan course cell is parsed once with its full period range`() {
        val html = """
            <html><body>
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr>
                <td>第1-2节</td>
                <td rowspan="2">数据结构<br>1-16周</td>
                <td>高等数学<br>1-16周</td><td></td>
              </tr>
              <tr>
                <td>第3-4节</td>
                <td>大学英语<br>1-16周</td><td></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()
        val outcome = webParser.parseRenderedHtml(html, "https://example.edu.cn/kb", fromWebView = true)
        assertTrue(outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(3, courses.size)
        val ds = courses.single { it.name == "数据结构" }
        assertEquals(1, ds.dayOfWeek)
        assertEquals(1, ds.startPeriod)
        assertEquals(4, ds.endPeriod)

        // 确认第二行在 rowspan 之后的课程列号不发生错位（必须仍在星期二第 3-4 节）
        val eng = courses.single { it.name == "大学英语" }
        assertEquals(2, eng.dayOfWeek)
        assertEquals(3, eng.startPeriod)
        assertEquals(4, eng.endPeriod)
    }

    @Test
    fun `one-period-per-row table with morning header column and multi-rowspan courses maps weekdays and periods accurately`() {
        val html = """
            <html><body>
            <table>
              <tr><th colspan="2">节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr>
                <td rowspan="4">上午</td>
                <td>第1节</td>
                <td rowspan="2">高等数学<br>张老师<br>1-16周<br>教A101</td>
                <td></td>
                <td rowspan="2">大学物理<br>李老师<br>1-16周<br>理楼202</td>
              </tr>
              <tr>
                <td>第2节</td>
                <td></td>
              </tr>
              <tr>
                <td>第3节</td>
                <td></td>
                <td rowspan="2">线性代数<br>王老师<br>1-16周<br>教B305</td>
                <td></td>
              </tr>
              <tr>
                <td>第4节</td>
                <td></td>
                <td></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()
        val outcome = webParser.parseRenderedHtml(html, "https://jwxt.example.edu.cn/eams/courseTable", fromWebView = true)
        assertTrue(outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(3, courses.size)

        val math = courses.single { it.name == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startPeriod)
        assertEquals(2, math.endPeriod)

        val physics = courses.single { it.name == "大学物理" }
        assertEquals(3, physics.dayOfWeek)
        assertEquals(1, physics.startPeriod)
        assertEquals(2, physics.endPeriod)

        val la = courses.single { it.name == "线性代数" }
        assertEquals(2, la.dayOfWeek)
        assertEquals(3, la.startPeriod)
        assertEquals(4, la.endPeriod)
    }

    @Test
    fun `parseRenderedDocuments extracts timetable from nested same-origin iframe`() {
        val outerShell = """
            <html><body>
            <div class="nav">学生选课中心</div>
            <iframe id="iframeautoheight" src="/xskbcx.aspx"></iframe>
            </body></html>
        """.trimIndent()
        val iframeHtml = """
            <html><body>
            <table id="Table1">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>第1-2节</td><td>编译原理<br>陈老师<br>1-16周<br>主楼301</td><td></td><td></td></tr>
            </table>
            </body></html>
        """.trimIndent()
        val outcome = webParser.parseRenderedDocuments(
            listOf(
                "https://jwxt.example.edu.cn/xs_main.aspx" to outerShell,
                "https://jwxt.example.edu.cn/xskbcx.aspx" to iframeHtml
            ),
            fromWebView = true
        )
        assertTrue("应能从内嵌 iframe 提取出课表：$outcome", outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(1, courses.size)
        assertEquals("编译原理", courses.first().name)
        assertEquals(1, courses.first().dayOfWeek)
    }

    @Test
    fun `only the most timetable like table is used`() {
        val html = """
            <html><body>
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>第1-2节</td><td>导航课程</td><td></td><td></td></tr>
            </table>
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>第1-2节</td><td>高等数学<br>1-16周</td><td></td><td></td></tr>
              <tr><td>第3-4节</td><td></td><td>大学英语<br>1-16周</td><td></td></tr>
              <tr><td>第5-6节</td><td></td><td></td><td>大学物理<br>1-16周</td></tr>
            </table>
            </body></html>
        """.trimIndent()
        val outcome = webParser.parseRenderedHtml(html, "https://example.edu.cn/kb", fromWebView = true)
        assertTrue(outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(3, courses.size)
        assertTrue(courses.none { it.name == "导航课程" })
    }

    @Test
    fun `eams runtime table0 snapshot merges contiguous periods and parses marshaled weeks`() {
        val activities = MutableList(84) { "[]" }
        val marshals = MutableList(84) { "\"\"" }
        // 周一(day=0) 第1-2节(unit=0,1): 高等数学A 1-16周
        val mathAct = """[{"teacherName":"张明","courseName":"高等数学A(10101)","roomName":"一教201","vaildWeeks":"01111111111111111000000000000000000000000000000000000"}]"""
        activities[0] = mathAct
        activities[1] = mathAct
        marshals[0] = "\"高等数学A(10101) (张明)<br><br><br>(1-16,一教201)\""
        marshals[1] = "\"高等数学A(10101) (张明)<br><br><br>(1-16,一教201)\""
        // 周三(day=2) 第5-6节(unit=4,5): 大学体育 单1-15周
        val peAct = """[{"teacherName":"王教练","courseName":"大学体育(篮球)","roomName":"田径场","vaildWeeks":"01010101010101010000000000000000000000000000000000000"}]"""
        activities[2 * 12 + 4] = peAct
        activities[2 * 12 + 5] = peAct
        marshals[2 * 12 + 4] = "\"大学体育(篮球) (王教练)<br><br><br>(单1-15,田径场)\""
        marshals[2 * 12 + 5] = "\"大学体育(篮球) (王教练)<br><br><br>(单1-15,田径场)\""

        val runtimeJson = """
            {
              "__eamsRuntimeTable": true,
              "url": "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
              "unitCounts": 84,
              "unitsPerDay": 12,
              "marshalContents": [${marshals.joinToString(",")}],
              "activities": [${activities.joinToString(",")}]
            }
        """.trimIndent()

        val outcome = webParser.parseRenderedDocuments(
            listOf(
                "eams-runtime://https://jwxt.example.edu.cn/eams/courseTableForStd.action" to runtimeJson,
                "https://jwxt.example.edu.cn/eams/courseTableForStd.action" to "<html><body><div id='empty'></div></body></html>"
            ),
            fromWebView = true
        )
        assertTrue("应精准解析 EAMS 运行时课表对象：$outcome", outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(2, courses.size)

        val math = courses.single { it.name == "高等数学A" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startPeriod)
        assertEquals(2, math.endPeriod)
        assertEquals("张明", math.teacher)
        assertEquals("一教201", math.location)
        assertEquals(WeekRuleType.ALL, math.weekRuleType)
        assertEquals(1, math.startWeek)
        assertEquals(16, math.endWeek)

        val pe = courses.single { it.name.contains("大学体育") }
        assertEquals(3, pe.dayOfWeek)
        assertEquals(5, pe.startPeriod)
        assertEquals(6, pe.endPeriod)
        assertEquals(WeekRuleType.ODD, pe.weekRuleType)
        assertEquals(1, pe.startWeek)
        assertEquals(15, pe.endWeek)
        assertEquals(CourseType.PHYSICAL_EDUCATION, pe.courseType)
    }

    @Test
    fun `eams script parser decodes TaskActivity with autumn calendar from-offset and wrap-around weeks`() {
        // 构造 from=36 的秋季学期 53 位自然周位图：第 35..50 下标对应学期第 1..16 周（i - 36 + 2）
        val autumn1To16 = CharArray(53) { '0' }.apply {
            for (i in 35..50) this[i] = '1'
        }.concatToString()
        // 双周 2-16：对应下标 36,38,40,42,44,46,48,50
        val autumnEven2To16 = CharArray(53) { '0' }.apply {
            for (w in 2..16 step 2) this[w + 36 - 2] = '1'
        }.concatToString()

        val html = """
            <html><body>
            <table id="manualArrangeCourseTable"></table>
            <script type="text/javascript">
              var table0 = new CourseTable(2026, 84);
              var unitCount = 12;
              var index = 0;
              var activity = null;
              var actTeachers = [{id:101,name:"李教授",lab:false}];
              var actTeacherName = ["李教授"];
              activity = new TaskActivity("101", actTeacherName.join(','), "C01", "数据结构(20101)", "R1", "科技楼302", "$autumn1To16", null, null, "", "", "");
              index = 1 * unitCount + 2;
              table0.activities[index][table0.activities[index].length] = activity;
              index = 1 * unitCount + 3;
              table0.activities[index][table0.activities[index].length] = activity;

              activity = new TaskActivity("102", "赵老师", "C02", "离散数学", "R2", "教A401", "$autumnEven2To16", null, null, "", "", "");
              index = 3 * unitCount + 6;
              table0.activities[index][table0.activities[index].length] = activity;
              index = 3 * unitCount + 7;
              table0.activities[index][table0.activities[index].length] = activity;

              table0.marshalTable(36, 1, 20);
              table0.fill("manualArrangeCourseTable", 7, 12);
            </script>
            </body></html>
        """.trimIndent()

        val outcome = webParser.parseRenderedHtml(
            html = html,
            sourceUrl = "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
            fromWebView = true
        )
        assertTrue("应解析 EAMS TaskActivity 脚本：$outcome", outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(2, courses.size)

        val ds = courses.single { it.name == "数据结构" }
        assertEquals(2, ds.dayOfWeek)
        assertEquals(3, ds.startPeriod)
        assertEquals(4, ds.endPeriod)
        assertEquals("李教授", ds.teacher)
        assertEquals("科技楼302", ds.location)
        assertEquals(WeekRuleType.ALL, ds.weekRuleType)
        assertEquals(1, ds.startWeek)
        assertEquals(16, ds.endWeek)

        val dm = courses.single { it.name == "离散数学" }
        assertEquals(4, dm.dayOfWeek)
        assertEquals(7, dm.startPeriod)
        assertEquals(8, dm.endPeriod)
        assertEquals(WeekRuleType.EVEN, dm.weekRuleType)
        assertEquals(2, dm.startWeek)
        assertEquals(16, dm.endWeek)
    }

    @Test
    fun `eams rendered horizontal manualArrangeCourseTable with colspan and triple br parses cleanly`() {
        // 模拟树维 TaskActivity.js fillTable 渲染完成后的横置表（行=星期一..星期日，列=第1..12节，colspan合并，且 jQuery 已移除 <script>）
        val html = """
            <html><body>
            <table id="manualArrangeCourseTable">
              <tr><th>星期/节次</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>6</th><th>7</th><th>8</th><th>9</th><th>10</th><th>11</th><th>12</th></tr>
              <tr>
                <td>星期一</td>
                <td id="TD0" colspan="2" class="infoTitle">线性代数(0102) (周老师)<br><br><br>(1-16,理学馆102)</td>
                <td id="TD2"></td><td id="TD3"></td><td id="TD4"></td><td id="TD5"></td>
                <td id="TD6" colspan="2" class="infoTitle">大学物理 (孙老师)<br>(单1-15,教B201)<br>物理实验 (钱老师)<br>(2-16双,实验楼305)</td>
                <td id="TD8"></td><td id="TD9"></td><td id="TD10"></td><td id="TD11"></td>
              </tr>
              <tr>
                <td>星期二</td>
                <td id="TD12"></td><td id="TD13"></td>
                <td id="TD14" colspan="2" class="infoTitle">面向对象程序设计 (吴老师)<br><br><br>(1-8 10-16,一教403)</td>
                <td id="TD16"></td><td id="TD17"></td><td id="TD18"></td><td id="TD19"></td>
                <td id="TD20"></td><td id="TD21"></td><td id="TD22"></td><td id="TD23"></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val outcome = webParser.parseRenderedHtml(
            html = html,
            sourceUrl = "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
            fromWebView = true
        )
        assertTrue("应解析 EAMS 横置渲染课表：$outcome", outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(4, courses.size)

        val la = courses.single { it.name == "线性代数" }
        assertEquals(1, la.dayOfWeek)
        assertEquals(1, la.startPeriod)
        assertEquals(2, la.endPeriod)
        assertEquals("周老师", la.teacher)
        assertEquals("理学馆102", la.location)
        assertEquals(WeekRuleType.ALL, la.weekRuleType)
        assertEquals(1, la.startWeek)
        assertEquals(16, la.endWeek)

        val phy = courses.single { it.name == "大学物理" }
        assertEquals(1, phy.dayOfWeek)
        assertEquals(7, phy.startPeriod)
        assertEquals(8, phy.endPeriod)
        assertEquals(WeekRuleType.ODD, phy.weekRuleType)
        assertEquals(1, phy.startWeek)
        assertEquals(15, phy.endWeek)

        val exp = courses.single { it.name == "物理实验" }
        assertEquals(1, exp.dayOfWeek)
        assertEquals(7, exp.startPeriod)
        assertEquals(8, exp.endPeriod)
        assertEquals(WeekRuleType.EVEN, exp.weekRuleType)
        assertEquals(2, exp.startWeek)
        assertEquals(16, exp.endWeek)

        val oop = courses.single { it.name == "面向对象程序设计" }
        assertEquals(2, oop.dayOfWeek)
        assertEquals(3, oop.startPeriod)
        assertEquals(4, oop.endPeriod)
        assertEquals(WeekRuleType.CUSTOM, oop.weekRuleType)
        assertEquals((1..8).toList() + (10..16).toList(), oop.customWeeks)
    }

    @Test
    fun `logged-in eams homeExt page produces targeted shortcut hint instead of asking to log in again`() {
        val homeExtHtml = """
            <html><body>
            <div>退出</div>
            <table><tr><td>快捷入口</td><td>Previous Next</td></tr></table>
            <table><tr><td>任务列表</td></tr></table>
            <table><tr><td>最新通知</td><td>2026-2027第秋季学期 13 教学周</td></tr></table>
            </body></html>
        """.trimIndent()
        val outcome = webParser.parseRenderedHtml(
            html = homeExtHtml,
            sourceUrl = "https://jwxt.example.edu.cn/eams/homeExt.action",
            fromWebView = true
        )
        assertTrue(outcome is WebParseOutcome.Failed)
        val msg = (outcome as WebParseOutcome.Failed).message
        assertTrue("应提示用户已登录且需前往我的课表页：$msg", msg.contains("已登录树维 EAMS") && msg.contains("前往我的课表页"))
        assertFalse("已登录首页不应再误提示请先登录：$msg", msg.contains("请先登录"))
    }

    @Test
    fun `edu portal shortcut resolver derives timetable urls accurately for EAMS and Qiangzhi`() {
        val eamsHome = EduPortalShortcutResolver.resolveTimetableShortcut("https://jwxt.example.edu.cn/eams/homeExt.action")
        assertNotNull(eamsHome)
        assertEquals("https://jwxt.example.edu.cn/eams/courseTableForStd.action", eamsHome!!.targetUrl)
        assertEquals("前往我的课表页", eamsHome.label)

        // 登录页与已在课表页不应展示快捷直达按钮
        assertNull(EduPortalShortcutResolver.resolveTimetableShortcut("https://jwxt.example.edu.cn/eams/localLogin.action"))
        assertNull(EduPortalShortcutResolver.resolveTimetableShortcut("https://jwxt.example.edu.cn/eams/courseTableForStd.action"))
        assertNull(EduPortalShortcutResolver.resolveTimetableShortcut("https://ids.example.edu.cn/authserver/login?service=https://jwxt.example.edu.cn/eams/homeExt.action"))

        // 强智首页框架直达学期理论课表
        val qzHome = EduPortalShortcutResolver.resolveTimetableShortcut("https://jwxt.example.edu.cn/jsxsd/framework/xsMain.jsp")
        assertNotNull(qzHome)
        assertEquals("https://jwxt.example.edu.cn/jsxsd/xskb/xskb_list.do", qzHome!!.targetUrl)
    }

    @Test
    fun `registry resolves EAMS school to eams_standard adapter without fabricating verified status`() {
        val sampleEamsSchool = School(
            code = "4100000001",
            name = "示例理工大学",
            province = "江苏省",
            city = "南京市",
            schoolLevel = SchoolLevel.UNDERGRADUATE,
            systemType = EduSystemType.EAMS,
            adapterId = "eams_standard",
            portalUrl = "https://jwxt.example.edu.cn/eams/localLogin.action",
            supportStatus = SchoolSupportStatus.GENERIC_ADAPTER,
            verified = false
        )
        val lookup = registry.resolveForSchool(sampleEamsSchool)
        assertTrue(lookup is AdapterLookupResult.GenericOrAssisted)
        val result = lookup as AdapterLookupResult.GenericOrAssisted
        assertEquals("eams_standard", result.adapter?.adapterId)
        assertEquals("https://jwxt.example.edu.cn/eams/courseTableForStd.action", result.authSpec.timetableHintUrl)
    }

    @Test
    fun `chinese parser merges adjacent period headers cleanly`() {
        assertEquals(1 to 2, ChineseCourseTextParser.parsePeriods("第1节 第2节"))
        assertEquals(3 to 4, ChineseCourseTextParser.parsePeriods("第3小节\n第4小节"))
        assertEquals(5 to 6, ChineseCourseTextParser.parsePeriods("第五、六节"))
        assertEquals(1 to 2, ChineseCourseTextParser.parsePeriods("第一、二节"))
    }

    @Test
    fun `eams multi-segment comma weeks parse as custom weeks without cutting location`() {
        val parsed1 = ChineseCourseTextParser.parseEamsScheduleParenLine("(1-4,6-12,一教201)")
        assertNotNull(parsed1)
        assertEquals(WeekRuleType.CUSTOM, parsed1!!.first.ruleType)
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12), parsed1.first.customWeeks)
        assertEquals("一教201", parsed1.second)

        val parsed2 = ChineseCourseTextParser.parseEamsScheduleParenLine("(8,10,12,物理实验中心)")
        assertNotNull(parsed2)
        assertEquals(WeekRuleType.EVEN, parsed2!!.first.ruleType)
        assertEquals(8, parsed2.first.startWeek)
        assertEquals(12, parsed2.first.endWeek)
        assertEquals("物理实验中心", parsed2.second)

        val parsed3 = ChineseCourseTextParser.parseEamsScheduleParenLine("(7,10,13,物理实验中心)")
        assertNotNull(parsed3)
        assertEquals(WeekRuleType.CUSTOM, parsed3!!.first.ruleType)
        assertEquals(listOf(7, 10, 13), parsed3.first.customWeeks)
        assertEquals("物理实验中心", parsed3.second)
    }

    @Test
    fun `autumn eams runtime snapshot infers calendar fromOffset and separates finished and active courses cleanly`() {
        // 模拟秋季学期 from=36：index = from + semWeek - 2 = 34 + semWeek
        fun maskForWeeks(weeks: IntRange): String {
            val arr = CharArray(53) { '0' }
            for (w in weeks) {
                val idx = 36 + w - 2
                if (idx in arr.indices) arr[idx] = '1'
            }
            return arr.concatToString()
        }

        val activities = MutableList(84) { "[]" }
        val marshals = MutableList(84) { "\"\"" }

        // 周一(day=0) 第1-2节(unit=0,1)：高数已结课(1-8周) + 线代进行中(9-16周) 同格共存
        val actJson = """[
            {"teacherName":"张老师","courseName":"数学先修(0101)","roomName":"一教101","vaildWeeks":"${maskForWeeks(1..8)}"},
            {"teacherName":"李老师","courseName":"线性代数(0201)","roomName":"一教102","vaildWeeks":"${maskForWeeks(9..16)}"}
        ]"""
        activities[0] = actJson
        activities[1] = actJson
        marshals[0] = "\"数学先修(0101) (张老师)<br><br><br>(1-8,一教101)<br>线性代数(0201) (李老师)<br><br><br>(9-16,一教102)\""
        marshals[1] = marshals[0]

        // 另外放入一条单课记录用于可靠反推 fromOffset = 36
        val engJson = """[{"teacherName":"王老师","courseName":"大学英语","roomName":"外语楼201","vaildWeeks":"${maskForWeeks(1..16)}"}]"""
        activities[2 * 12 + 2] = engJson
        activities[2 * 12 + 3] = engJson
        marshals[2 * 12 + 2] = "\"大学英语 (王老师)<br><br><br>(1-16,外语楼201)\""
        marshals[2 * 12 + 3] = marshals[2 * 12 + 2]

        val payload = """
            {
              "__eamsRuntimeTable": true,
              "url": "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
              "unitCounts": 84,
              "unitsPerDay": 12,
              "marshalContents": [${marshals.joinToString(",")}],
              "activities": [${activities.joinToString(",")}]
            }
        """.trimIndent()

        val outcome = webParser.parseRenderedDocuments(listOf("eams-runtime://eams-demo" to payload), fromWebView = true)
        assertTrue(outcome is WebParseOutcome.Success)
        val courses = (outcome as WebParseOutcome.Success).candidates
        assertEquals(3, courses.size)

        val finished = courses.single { it.name == "数学先修" }
        assertEquals(1, finished.startWeek)
        assertEquals(8, finished.endWeek)
        assertEquals(1, finished.startPeriod)
        assertEquals(2, finished.endPeriod)

        val active = courses.single { it.name == "线性代数" }
        assertEquals(9, active.startWeek)
        assertEquals(16, active.endWeek)
        assertEquals(1, active.startPeriod)
        assertEquals(2, active.endPeriod)
    }

    @Test
    fun `parseRenderedDocuments prefers structured eams runtime over noisy generic html table`() {
        val runtimePayload = """
            {
              "__eamsRuntimeTable": true,
              "url": "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
              "unitCounts": 84,
              "unitsPerDay": 12,
              "marshalContents": ["高等数学 (张老师)<br><br><br>(1-16,教A101)", "高等数学 (张老师)<br><br><br>(1-16,教A101)"${",\"\"".repeat(82)}],
              "activities": [
                [{"teacherName":"张老师","courseName":"高等数学","roomName":"教A101","vaildWeeks":"01111111111111111000000000000000000000000000000000000"}],
                [{"teacherName":"张老师","courseName":"高等数学","roomName":"教A101","vaildWeeks":"01111111111111111000000000000000000000000000000000000"}]
                ${",[]".repeat(82)}
              ]
            }
        """.trimIndent()
        val noisyGenericHtml = """
            <html><body>
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>第1节</td><td>(未知行)<br>1-16周</td><td></td><td></td></tr>
              <tr><td>第2节</td><td>(另一个未知行)<br>1-16周</td><td>(课程B)<br>1-16周</td><td>(课程C)<br>1-16周</td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val outcome = webParser.parseRenderedDocuments(
            listOf(
                "https://jwxt.example.edu.cn/eams/courseTableForStd.action" to noisyGenericHtml,
                "eams-runtime://https://jwxt.example.edu.cn/eams/courseTableForStd.action" to runtimePayload
            ),
            fromWebView = true
        )
        assertTrue(outcome is WebParseOutcome.Success)
        val success = outcome as WebParseOutcome.Success
        assertEquals(3, success.qualityTier)
        assertEquals(1, success.candidates.size)
        assertEquals("高等数学", success.candidates.first().name)
    }

    @Test
    fun `finished courses in week 13 never leak into week 13 schedule or trigger fake conflicts`() {
        // 复现用户真机截图场景：含已结课(1-8周 / 1-12周)、进行中(9-16周)、第13周实验课(带括号实验室行与3连br)、以及 TD0_0 和「第1节 第2节」表头
        val eamsTdHtml = """
            <html><body>
            <table id="manualArrangeCourseTable_2026">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th></tr>
              <tr>
                <td>第1节<br>第2节</td>
                <td id="TD0_0" rowspan="2">工程制图(1001) (陈老师)<br><br><br>(1-8,一教201)<br>高级程序设计 (刘老师)<br><br><br>(9-16,信息楼302)</td>
                <td></td><td></td><td></td>
              </tr>
              <tr><td></td><td></td><td></td><td></td></tr>
              <tr>
                <td>第3节<br>第4节</td>
                <td id="TD0_2" rowspan="2">概率论与数理统计 (周老师)<br><br><br>(1-12,理学馆105)</td>
                <td></td>
                <td></td>
                <td id="TD3_2" rowspan="2">物理实验 (赵老师)<br>(力学综合实验室)<br><br><br>(13,力学综合实验室)</td>
              </tr>
              <tr><td></td><td></td><td></td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val outcome = webParser.parseRenderedHtml(
            html = eamsTdHtml,
            sourceUrl = "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
            fromWebView = true
        )
        assertTrue("应正确解析下划线 TD 树维课表：$outcome", outcome is WebParseOutcome.Success)
        val candidates = (outcome as WebParseOutcome.Success).candidates

        // 1. 绝不允许产生以 '(' 开头的假课程，也不允许教室带外层括号
        assertTrue("不应有括号开头假课: $candidates", candidates.none { it.name.startsWith("(") || it.name.startsWith("（") })
        assertTrue("教室不应残余外层括号: $candidates", candidates.none { it.location.startsWith("(") || it.location.startsWith("（") })

        // 2. 全部均为 2 节连堂（1-2 节、3-4 节），不能被截成单节
        assertEquals(4, candidates.size)
        val drawing = candidates.single { it.name == "工程制图" }
        assertEquals(1 to 2, drawing.startPeriod to drawing.endPeriod)
        assertEquals(1 to 8, drawing.startWeek to drawing.endWeek)

        val cpp = candidates.single { it.name == "高级程序设计" }
        assertEquals(1 to 2, cpp.startPeriod to cpp.endPeriod)
        assertEquals(9 to 16, cpp.startWeek to cpp.endWeek)

        val prob = candidates.single { it.name == "概率论与数理统计" }
        assertEquals(3 to 4, prob.startPeriod to prob.endPeriod)
        assertEquals(1 to 12, prob.startWeek to prob.endWeek)

        val exp = candidates.single { it.name == "物理实验" }
        assertEquals(3 to 4, exp.startPeriod to exp.endPeriod)
        assertEquals(13 to 13, exp.startWeek to exp.endWeek)
        assertEquals("力学综合实验室", exp.location)

        // 3. 用领域引擎实际解析第 13 周课表并检测冲突：1-8 与 1-12 已结课课程必须消失，第 13 周 0 冲突！
        val semester = Semester(id = "sem-1", userId = "u-1", name = "2026-2027 第一学期", startDate = "2026-08-31", totalWeeks = 20)
        val details = candidates.mapIndexed { idx, c ->
            CourseWithDetails(
                course = Course(id = "c-$idx", semesterId = semester.id, name = c.name, teacher = c.teacher, location = c.location),
                schedules = listOf(
                    CourseSchedule(
                        id = "s-$idx",
                        courseId = "c-$idx",
                        dayOfWeek = c.dayOfWeek,
                        startPeriod = c.startPeriod,
                        endPeriod = c.endPeriod,
                        weekRuleType = c.weekRuleType,
                        startWeek = c.startWeek,
                        endWeek = c.endWeek,
                        customWeeks = c.customWeeks
                    )
                ),
                exceptions = emptyList()
            )
        }

        // 第 13 周周一（2026-11-23）：只应出现「高级程序设计」，绝不能出现已结课的「工程制图」「概率论与数理统计」
        val (_, monWeek13) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 11, 23), details, emptyList())
        assertEquals(listOf("高级程序设计"), monWeek13.map { it.course.name })

        // 第 13 周周四（2026-11-26）：只应出现「物理实验」
        val (_, thuWeek13) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 11, 26), details, emptyList())
        assertEquals(listOf("物理实验"), thuWeek13.map { it.course.name })

        val week13Conflicts = ConflictDetectionEngine.detectConflictsInSemester(
            semester = semester,
            courses = details,
            periods = emptyList(),
            fromWeek = 13,
            toWeek = 13
        )
        assertTrue("第 13 周不应有冲突: $week13Conflicts", week13Conflicts.isEmpty())
    }

    @Test
    fun `eams horizontal TD table preserves 2-period and 4-period blocks plus multi-teacher same course and official bell times`() {
        // 模拟真实通用树维 EAMS 横置课表第 13 周场景：
        // - 横置表 TD{slot}_0，fillTable 删除了被 colspan 合并掉的后续 TD（同时测试无 colspan 属性靠 TD 缺口兜底）
        // - 包含 1-2, 3-4, 5-6, 7-8 两节连堂及周四 1-4 四节连堂电工学（含嵌套括号教室与参数内含分号）
        // - 同格单 <br> 混排已结课课程与在读电工学
        // - 官网表头标注紧凑作息时间：08:00-08:45 / 08:50-09:35 / 10:05-10:50 / 10:55-11:40 / 14:00-14:45 / 14:50-15:35 / 16:05-16:50 / 16:55-17:40 / 18:40-19:25
        val eamsHorizontalHtml = """
            <html><body>
            <table id="manualArrangeCourseTable_demo">
              <tr>
                <th>节次</th>
                <th title="08:00-08:45">1<br>08:00-08:45</th>
                <th title="08:50-09:35">2<br>08:50-09:35</th>
                <th title="10:05-10:50">3<br>10:05-10:50</th>
                <th title="10:55-11:40">4<br>10:55-11:40</th>
                <th title="14:00-14:45">5<br>14:00-14:45</th>
                <th title="14:50-15:35">6<br>14:50-15:35</th>
                <th title="16:05-16:50">7<br>16:05-16:50</th>
                <th title="16:55-17:40">8<br>16:55-17:40</th>
                <th title="18:40-19:25">9<br>18:40-19:25</th>
                <th title="19:30-20:15">10<br>19:30-20:15</th>
                <th title="20:25-21:10">11<br>20:25-21:10</th>
                <th title="21:15-22:00">12<br>21:15-22:00</th>
              </tr>
              <tr>
                <th>星期一</th>
                <td id="TD0_0" colspan="2">毛泽东思想和中国特色社会主义理论体系概论<br>(1-16,一教411(主校区))</td>
                <td id="TD2_0" colspan="2">大学物理-热学<br>(1-16,一教230(主校区))</td>
                <td id="TD4_0" data-agy-colspan="2">先行结课实训 (陈老师)<br>(1-8,实验中心)<br>电工学 (李老师)<br>(9-16,一教122(主校区))</td>
                <td id="TD6_0">[体]体育(三)武术 (陈教练)<br><br><br>(1-16,体育馆(主校区))</td>
                <td id="TD8_0"></td><td id="TD9_0"></td><td id="TD10_0"></td><td id="TD11_0"></td>
              </tr>
              <tr>
                <th>星期二</th>
                <td id="TD12_0"></td><td id="TD13_0"></td>
                <td id="TD14_0" colspan="2">英语阅读与写作<br>(1-16,科技楼202(新校区))</td>
                <td id="TD16_0"></td><td id="TD17_0"></td><td id="TD18_0"></td><td id="TD19_0"></td>
                <td id="TD20_0"></td><td id="TD21_0"></td><td id="TD22_0"></td><td id="TD23_0"></td>
              </tr>
              <tr>
                <th>星期三</th>
                <td id="TD24_0" colspan="2">毛泽东思想和中国特色社会主义理论体系概论<br>(1-16,一教411(主校区))</td>
                <td id="TD26_0"></td><td id="TD27_0"></td><td id="TD28_0"></td><td id="TD29_0"></td><td id="TD30_0"></td><td id="TD31_0"></td>
                <td id="TD32_0"></td><td id="TD33_0"></td><td id="TD34_0"></td><td id="TD35_0"></td>
              </tr>
              <tr>
                <th>星期四</th>
                <td id="TD36_0" colspan="4">电工学 (王老师)<br><br><br>(1-16,综合楼305(主校区))</td>
                <td id="TD40_0" colspan="2">大学物理-热学<br>(1-16,一教230(主校区))</td>
                <td id="TD42_0" colspan="2">电工学 (李老师)<br><br><br>(1-16,一教122(主校区))</td>
                <td id="TD44_0"></td><td id="TD45_0"></td><td id="TD46_0"></td><td id="TD47_0"></td>
              </tr>
              <tr>
                <th>星期五</th>
                <td id="TD48_0"></td><td id="TD49_0"></td><td id="TD50_0"></td><td id="TD51_0"></td>
                <td id="TD52_0" colspan="2">毛泽东思想和中国特色社会主义理论体系概论<br>(1-16,一教411(主校区))</td>
                <td id="TD54_0" colspan="2">创业基础 (赵老师)<br><br><br>(1-16,一教116(主校区))</td>
                <td id="TD56_0"></td><td id="TD57_0"></td><td id="TD58_0"></td><td id="TD59_0"></td>
              </tr>
              <tr><th>星期六</th>${(60..71).joinToString("") { "<td id=\"TD${it}_0\"></td>" }}</tr>
              <tr><th>星期日</th>${(72..83).joinToString("") { "<td id=\"TD${it}_0\"></td>" }}</tr>
            </table>
            <div>2026-2027第秋季学期 13 教学周</div>
            </body></html>
        """.trimIndent()

        val outcome = webParser.parseRenderedDocuments(
            listOf("https://jwxt.example.edu.cn/eams/courseTableForStd.action" to eamsHorizontalHtml),
            fromWebView = true
        )
        assertTrue(outcome is WebParseOutcome.Success)
        val success = outcome as WebParseOutcome.Success
        val candidates = success.candidates

        // 校验官网作息时间与当前教学周提示提取
        assertEquals(13, success.detectedCurrentWeek)
        assertEquals(12, success.detectedPeriods.size)
        val bellMap = success.detectedPeriods.associateBy { it.periodNumber }
        assertEquals("08:00" to "08:45", bellMap.getValue(1).startTime to bellMap.getValue(1).endTime)
        assertEquals("08:50" to "09:35", bellMap.getValue(2).startTime to bellMap.getValue(2).endTime)
        assertEquals("10:05" to "10:50", bellMap.getValue(3).startTime to bellMap.getValue(3).endTime)
        assertEquals("16:05" to "16:50", bellMap.getValue(7).startTime to bellMap.getValue(7).endTime)
        assertEquals("18:40" to "19:25", bellMap.getValue(9).startTime to bellMap.getValue(9).endTime)

        // 校验第 13 周在读的 11 节课一个不少且连堂节次 100% 精确
        val monPe = candidates.single { it.dayOfWeek == 1 && it.name.contains("体育") }
        assertEquals(7 to 8, monPe.startPeriod to monPe.endPeriod)
        assertEquals(CourseType.PHYSICAL_EDUCATION, monPe.courseType)

        val monElec = candidates.single { it.dayOfWeek == 1 && it.name == "电工学" }
        assertEquals(5 to 6, monElec.startPeriod to monElec.endPeriod)
        assertEquals("李老师", monElec.teacher)
        assertEquals("一教122(主校区)", monElec.location)

        val thuElecMorning = candidates.single { it.dayOfWeek == 4 && it.name == "电工学" && it.startPeriod == 1 }
        assertEquals(1 to 4, thuElecMorning.startPeriod to thuElecMorning.endPeriod)
        assertEquals("王老师", thuElecMorning.teacher)
        assertEquals("综合楼305(主校区)", thuElecMorning.location)

        val thuElecAfternoon = candidates.single { it.dayOfWeek == 4 && it.name == "电工学" && it.startPeriod == 7 }
        assertEquals(7 to 8, thuElecAfternoon.startPeriod to thuElecAfternoon.endPeriod)
        assertEquals("李老师", thuElecAfternoon.teacher)

        // 验证脚本写法中参数含分号(&nbsp;)及 index++ 时绝不丢电工学、不丢失第 2 节
        val weirdScriptHtml = """
            <html><body>
            <table id="manualArrangeCourseTable"></table>
            <script>
              var table0 = new CourseTable(2026, 84);
              var unitCount = 12;
              var index = 0;
              var actTeachers = [{name:"王老师"}];
              var activity = new TaskActivity("1", actTeacherName.join(','), "E1", "电工学&nbsp;(01)", "R1", "综合楼305(主校区)", "${"0".repeat(35) + "1".repeat(16) + "00"}", null, null, "", "备注;含分号", "");
              index = 3 * unitCount + 0;
              table0.activities[index][0] = activity;
              index++;
              table0.activities[index][0] = activity;
              index++;
              table0.activities[index][0] = activity;
              index++;
              table0.activities[index][0] = activity;
              table0.marshalTable(36, 1, 20);
            </script>
            </body></html>
        """.trimIndent()
        val scriptOutcome = webParser.parseRenderedHtml(weirdScriptHtml, "https://jwxt.example.edu.cn/eams/courseTableForStd.action")
        assertTrue(scriptOutcome is WebParseOutcome.Success)
        val scriptCourse = (scriptOutcome as WebParseOutcome.Success).candidates.single()
        assertEquals(4, scriptCourse.dayOfWeek)
        assertEquals(1 to 4, scriptCourse.startPeriod to scriptCourse.endPeriod)
        assertEquals("王老师", scriptCourse.teacher)
    }
}
