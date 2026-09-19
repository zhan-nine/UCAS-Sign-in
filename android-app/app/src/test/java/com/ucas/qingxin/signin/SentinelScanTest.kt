package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureNaming
import com.ucas.qingxin.signin.lecture.SentinelScan
import com.ucas.qingxin.signin.lecture.ForwardWalk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 哨兵扫描策略的回归测试。
 *
 * ## 为什么这些用例值得写
 * 这套逻辑**只有在校内网络里才会真正跑**（要访问课程注册表），而它一旦出错，
 * 表现是「什么都没发现」—— 与「确实还没有新讲座」完全无法区分。
 * 用户不会知道是自己的扫描坏了还是真的没有讲座，所以这里用假的注册表把
 * 每一条停止规则、以及最关键的「占号后填名」场景全部钉住。
 */
class SentinelScanTest {

    /** 假注册表：编号 → 名称（不在表里 = 该编号还没有名称）。 */
    private class FakeRegistry(private val names: MutableMap<Int, String> = mutableMapOf()) {
        var requests = 0
            private set

        /** 让某个编号「后来被填上名称」—— 即真实注册表的占号/填名两阶段。 */
        fun publish(cId: Int, name: String) {
            names[cId] = name
        }

        suspend fun fetch(cId: Int): String? {
            requests++
            return names[cId]
        }
    }

    private val m1167 = "M1167雁栖湖会场：夯实科技发展的文化基础"
    private val m1168 = "M1168雁栖湖会场：未来的科学与技术"

    // ---------------------------------------------------------------- 首次运行

    @Test
    fun `首次运行自基点向前扫，连续无名即停在分配末端`() = runTest {
        val registry = FakeRegistry()
        registry.publish(1001, m1167)
        registry.publish(1002, "明德讲堂M1167中关村会场：夯实科技发展的文化基础")
        registry.publish(1003, "明德讲堂M1167玉泉路会场：夯实科技发展的文化基础")

        val result = SentinelScan.run(anchor = 1000, frontier = 0, pending = emptyList()) { registry.fetch(it) }

        assertEquals(setOf(1001, 1002, 1003), result.named.keys)
        // 1004 起连续 30 个无名 ⇒ 认定到达分配末端，请求数 = 3 个有名称 + 30 个无名。
        assertEquals(SentinelScan.EMPTY_LIMIT, result.stillNameless.size)
        assertEquals("待查集从第一个无名编号开始", 1004, result.stillNameless.first())
        assertEquals(3 + SentinelScan.EMPTY_LIMIT, result.requests)
        assertTrue(result.haltReason.contains("分配末端"))
        // 三个会场算三个编号，但期次号只有一个 —— 去重以后不会重复报。
        assertEquals(setOf("M1167"), result.lectureSessions.values.map(LectureNaming::sessionLabel).toSet())
    }

    @Test
    fun `无名的编号会进入待查集，供下一轮复查`() = runTest {
        val registry = FakeRegistry()
        val result = SentinelScan.run(anchor = 1000, frontier = 0, pending = emptyList()) { registry.fetch(it) }

        // 从 1001 起全是无名：全部进待查集，而不是被永远丢弃。
        assertTrue(result.stillNameless.isNotEmpty())
        assertEquals(1001, result.stillNameless.first())
        assertEquals(0, result.highestNamed)
    }

    // ---------------------------------------------------------------- 核心：占号后填名

    @Test
    fun `占号早、填名晚的场次会被下一轮复查发现`() = runTest {
        val registry = FakeRegistry()

        // 第一轮：1001 已被占号，但还没有名称（实测形态：页面存在、无标题）。
        val first = SentinelScan.run(anchor = 1000, frontier = 0, pending = emptyList()) { registry.fetch(it) }
        assertTrue("占号行应进入待查集", first.stillNameless.contains(1001))
        assertEquals("此刻还读不到讲座", 0, first.lectureSessions.size)

        // 之后名称被填上（讲座临近时才会发生）。
        registry.publish(1001, m1168)

        // 第二轮：待查集里有 1001，必须复查到它 —— 只往前扫是发现不了它的，
        // 因为此时前沿早已越过 1001。
        val second = SentinelScan.run(
            anchor = 1000,
            frontier = first.highestNamed.coerceAtLeast(1000),
            pending = first.stillNameless,
        ) { registry.fetch(it) }

        assertTrue("复查应发现这场讲座", second.lectureSessions.containsKey(1001))
        assertEquals(m1168, second.lectureSessions[1001])
        assertFalse("填名后应从待查集移除", second.stillNameless.contains(1001))
    }

    // ---------------------------------------------------------------- 停止规则

    @Test
    fun `预算用尽时停止并把剩余编号留给下一轮`() = runTest {
        val registry = FakeRegistry()
        for (cId in 1001..1400) registry.publish(cId, "普通课程$cId")

        val result = SentinelScan.run(anchor = 1000, frontier = 0, pending = emptyList(), budget = 50) {
            registry.fetch(it)
        }

        assertEquals(50, result.requests)
        assertEquals(ForwardWalk.REASON_BUDGET, result.haltReason)
        assertEquals("碰到预算就停，不能扫完", 50, result.named.size)
    }

    @Test
    fun `分配区内零星夹杂的占位行不会让扫描提前停止`() = runTest {
        val registry = FakeRegistry()
        registry.publish(1001, "普通课程A")
        // 1002 是占位行（无名），但后面还有课程与讲座 —— 不能就此停住。
        registry.publish(1003, "普通课程B")
        registry.publish(1004, m1168)

        val result = SentinelScan.run(anchor = 1000, frontier = 0, pending = emptyList()) { registry.fetch(it) }

        assertTrue("占位行之后的内容仍应被扫到", result.lectureSessions.containsKey(1004))
        assertTrue(result.stillNameless.contains(1002))
    }

    @Test
    fun `前沿存在时从它之后继续，不重扫基点之后的已知区间`() = runTest {
        val registry = FakeRegistry()
        registry.publish(9001, "普通课程")

        val result = SentinelScan.run(anchor = 1000, frontier = 9000, pending = emptyList()) { registry.fetch(it) }

        assertTrue(result.named.containsKey(9001))
        assertFalse("不该回到基点附近重扫", result.named.keys.any { it <= 1000 })
    }

    @Test
    fun `待查集不受连续无名早停影响`() = runTest {
        val registry = FakeRegistry()
        // 待查集里全是无名（这本就是它们进待查集的原因），后面才出现名称。
        registry.publish(7005, m1168)

        val result = SentinelScan.run(anchor = 1000, frontier = 7000, pending = listOf(6001, 6002, 7001, 7002)) {
            registry.fetch(it)
        }

        // 4 个待查编号全部被复查（若把连续无名当停止条件，这里会一个都扫不到）。
        assertTrue(result.named.containsKey(7005) || result.requests >= 4)
        assertTrue("待查编号必须逐个复查", result.stillNameless.containsAll(listOf(6001, 6002, 7001, 7002)))
        assertTrue("待查集复查后仍需继续向前扫", result.highestNamed >= 7005)
    }

    // ---------------------------------------------------------------- 讲座判定

    @Test
    fun `学期初批量导入的讲座课程容器不算一场讲座`() = runTest {
        val registry = FakeRegistry()
        // 实测形态：同名成串 4–16 条，不带期次号，是课程占位而不是场次。
        registry.publish(1001, "全球地缘与中国国情专题讲座")
        registry.publish(1002, "全球地缘与中国国情专题讲座")
        registry.publish(1003, "发现生命奥秘专题讲座")
        registry.publish(1004, m1167)

        val result = SentinelScan.run(anchor = 1000, frontier = 0, pending = emptyList()) { registry.fetch(it) }

        assertEquals("容器不应被当成场次", setOf(1004), result.lectureSessions.keys)
        assertEquals("但名称仍然要读到（索引仍有价值）", 4, result.named.size)
    }

    @Test
    fun `艺术系列的期次号也能被识别`() = runTest {
        val registry = FakeRegistry()
        registry.publish(1001, "艺术与人文修养讲座系列第215讲：京剧的人物塑造")

        val result = SentinelScan.run(anchor = 1000, frontier = 0, pending = emptyList()) { registry.fetch(it) }

        assertEquals(setOf(1001), result.lectureSessions.keys)
        assertEquals("第215讲", LectureNaming.sessionLabel(result.lectureSessions.getValue(1001)))
    }

    // ---------------------------------------------------------------- 状态机本身

    @Test
    fun `ForwardWalk 的取号与停机制`() {
        val walk = ForwardWalk(from = 10, budget = 5, stopAfterEmpty = 2)

        assertEquals(10, walk.next())
        walk.accept("有名称")
        assertEquals(11, walk.next())
        walk.accept(null)
        assertEquals(12, walk.next())
        walk.accept(null)
        // 连续 2 个无名 ⇒ 停。
        assertNull(walk.next())
        assertTrue(walk.reachedAllocationEnd)
        assertEquals(3, walk.requests)

        val budgetWalk = ForwardWalk(from = 10, budget = 2, stopAfterEmpty = 99)
        assertNotNull(budgetWalk.next())
        budgetWalk.accept("x")
        assertNotNull(budgetWalk.next())
        budgetWalk.accept("y")
        assertNull(budgetWalk.next())
        assertFalse(budgetWalk.reachedAllocationEnd)
        assertEquals(ForwardWalk.REASON_BUDGET, budgetWalk.haltReason)
    }

    @Test
    fun `ForwardWalk 不会越过编号上限`() {
        val walk = ForwardWalk(from = ForwardWalk.MAX_CID, budget = 10, stopAfterEmpty = 1)
        assertEquals(ForwardWalk.MAX_CID, walk.next())
        walk.accept("最后一条")
        assertNull(walk.next())
        assertEquals(ForwardWalk.REASON_MAX_CID, walk.haltReason)
    }
}
