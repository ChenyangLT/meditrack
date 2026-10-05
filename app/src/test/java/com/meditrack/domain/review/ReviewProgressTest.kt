package com.meditrack.domain.review

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.ReviewCountMode
import com.meditrack.data.local.entity.ReviewSearchEngine
import com.meditrack.data.local.entity.ReviewSearchQuery
import org.junit.Test

/**
 * The «复查提醒» arithmetic.
 *
 * Every rule the feature promises the user is asserted here, because the failure mode of a review
 * reminder is not "it was a bit off" - it is "the app told me to go to the doctor at the wrong time",
 * or worse, "it never told me at all". Both are silent in production and obvious in a test.
 */
class ReviewProgressTest {

    private fun progress(
        mode: ReviewCountMode = ReviewCountMode.DOSES,
        count: Double,
        threshold: Double,
        unit: String = mode.unitShort,
    ) = ReviewProgress(mode = mode, count = count, threshold = threshold, unitShort = unit)

    // ------------------------------------------------------------- configuration

    @Test
    fun `no threshold means no review can ever fire`() {
        // The feature is inert by default even though the per-medication arming flag defaults to on, and
        // this is the assertion that makes that safe.
        val unset = progress(count = 999.0, threshold = 0.0)

        assertThat(unset.isConfigured).isFalse()
        assertThat(unset.isReached).isFalse()
        assertThat(unset.shouldGiveAdvanceNotice(3)).isFalse()
        assertThat(unset.remainingLabel).isEqualTo("未设置")
        assertThat(unset.fraction).isEqualTo(0.0)
        assertThat(unset.remainingUnits).isEqualTo(0)
    }

    // ------------------------------------------------------------------- reaching

    @Test
    fun `the threshold is reached exactly at the threshold`() {
        assertThat(progress(count = 59.0, threshold = 60.0).isReached).isFalse()
        assertThat(progress(count = 60.0, threshold = 60.0).isReached).isTrue()
        assertThat(progress(count = 61.0, threshold = 60.0).isReached).isTrue()
    }

    @Test
    fun `floating point drift cannot withhold a review`() {
        // 0.5 added 120 times is 59.99999999999999, and a bare `>=` would silently never fire.
        var accumulated = 0.0
        repeat(120) { accumulated += 0.5 }

        assertThat(progress(count = accumulated, threshold = 60.0).isReached).isTrue()
    }

    @Test
    fun `progress below the threshold never fires`() {
        // Just outside the epsilon, so this is a genuine shortfall rather than drift.
        assertThat(progress(count = 59.9, threshold = 60.0).isReached).isFalse()
    }

    // ----------------------------------------------------------------- remaining

    @Test
    fun `remaining units round up so a partial unit still counts as one`() {
        assertThat(progress(count = 57.0, threshold = 60.0).remainingUnits).isEqualTo(3)
        // 2.5 left is "还差 3 次", not "还差 2 次": telling the user they are two doses away when they
        // need three is the kind of off-by-one that loses trust in the whole reminder.
        assertThat(progress(count = 57.5, threshold = 60.0).remainingUnits).isEqualTo(3)
        assertThat(progress(count = 58.0, threshold = 60.0).remainingUnits).isEqualTo(2)
    }

    @Test
    fun `remaining never goes negative past the threshold`() {
        val passed = progress(count = 70.0, threshold = 60.0)

        assertThat(passed.remaining).isEqualTo(0.0)
        assertThat(passed.remainingUnits).isEqualTo(0)
        assertThat(passed.fraction).isEqualTo(1.0)
    }

    @Test
    fun `the fraction is clamped to one`() {
        assertThat(progress(count = 30.0, threshold = 60.0).fraction).isEqualTo(0.5)
        assertThat(progress(count = 120.0, threshold = 60.0).fraction).isEqualTo(1.0)
    }

    @Test
    fun `the remaining label reads naturally per mode`() {
        assertThat(progress(count = 57.0, threshold = 60.0, unit = "次").remainingLabel)
            .isEqualTo("还差 3 次")
        assertThat(
            progress(
                mode = ReviewCountMode.DAYS,
                count = 28.0,
                threshold = 30.0,
                unit = "天",
            ).remainingLabel
        ).isEqualTo("还差 2 天")
        assertThat(
            progress(
                mode = ReviewCountMode.QUANTITY,
                count = 250.0,
                threshold = 300.0,
                unit = "ml",
            ).remainingLabel
        ).isEqualTo("还差 50 ml")
        assertThat(progress(count = 60.0, threshold = 60.0, unit = "次").remainingLabel)
            .isEqualTo("已到复查时间")
    }

    @Test
    fun `the progress label reports what has been counted`() {
        assertThat(progress(count = 47.0, threshold = 60.0, unit = "次").progressLabel)
            .isEqualTo("已 47 / 60 次")
        assertThat(
            progress(
                mode = ReviewCountMode.QUANTITY,
                count = 12.5,
                threshold = 300.0,
                unit = "ml",
            ).progressLabel
        ).isEqualTo("已累计 12.5 / 300 ml")
    }

    @Test
    fun `the reached label names the unit the round counted in`() {
        assertThat(progress(count = 62.0, threshold = 60.0).reachedLabel).isEqualTo("已服用 62 次")
        assertThat(
            progress(mode = ReviewCountMode.DAYS, count = 91.0, threshold = 90.0, unit = "天").reachedLabel
        ).isEqualTo("已用药 91 天")
        assertThat(
            progress(
                mode = ReviewCountMode.QUANTITY,
                count = 305.0,
                threshold = 300.0,
                unit = "ml",
            ).reachedLabel
        ).isEqualTo("已累计 305 ml")
    }

    // ------------------------------------------------------------ advance notice

    @Test
    fun `the advance notice appears inside the window and not before`() {
        val threeLeft = progress(count = 57.0, threshold = 60.0)
        val fourLeft = progress(count = 56.0, threshold = 60.0)

        assertThat(threeLeft.shouldGiveAdvanceNotice(3)).isTrue()
        assertThat(fourLeft.shouldGiveAdvanceNotice(3)).isFalse()
    }

    @Test
    fun `the advance notice never doubles up with the reached notice`() {
        val reached = progress(count = 60.0, threshold = 60.0)

        // Seeing "还差 0 次" next to "该复查了" would read as the app having lost count.
        assertThat(reached.shouldGiveAdvanceNotice(3)).isFalse()
    }

    @Test
    fun `a disabled advance notice stays disabled`() {
        assertThat(progress(count = 59.0, threshold = 60.0).shouldGiveAdvanceNotice(0)).isFalse()
        assertThat(progress(count = 59.0, threshold = 60.0).shouldGiveAdvanceNotice(-1)).isFalse()
    }

    // ---------------------------------------------------------------- calculator

    @Test
    fun `day mode counts the starting day as day one`() {
        // A "30 天" prescription that reported "还差 1 天" on its own start date would be off by one in
        // the direction that matters: the doctor said thirty days of medication, not thirty starting
        // tomorrow.
        val firstDay = ReviewProgressCalculator.progressOf(
            mode = ReviewCountMode.DAYS,
            storedCount = 0.0,
            threshold = 30.0,
            startedEpochDay = 20_000L,
            epochDay = 20_000L,
        )
        assertThat(firstDay.count).isEqualTo(1.0)
        assertThat(firstDay.remainingUnits).isEqualTo(29)

        val lastDay = ReviewProgressCalculator.progressOf(
            mode = ReviewCountMode.DAYS,
            storedCount = 0.0,
            threshold = 30.0,
            startedEpochDay = 20_000L,
            epochDay = 20_029L,
        )
        assertThat(lastDay.count).isEqualTo(30.0)
        assertThat(lastDay.isReached).isTrue()
    }

    @Test
    fun `day mode ignores whatever was stored as a count`() {
        // The stored number is authoritative for doses and quantity, and meaningless for days: a round
        // that had a dose-based count written into it must not double count after a mode change.
        val days = ReviewProgressCalculator.progressOf(
            mode = ReviewCountMode.DAYS,
            storedCount = 500.0,
            threshold = 30.0,
            startedEpochDay = 20_000L,
            epochDay = 20_005L,
        )

        assertThat(days.count).isEqualTo(6.0)
    }

    @Test
    fun `a clock moved backwards cannot make the day count zero or negative`() {
        val backwards = ReviewProgressCalculator.progressOf(
            mode = ReviewCountMode.DAYS,
            storedCount = 0.0,
            threshold = 30.0,
            startedEpochDay = 20_100L,
            epochDay = 20_000L,
        )

        assertThat(backwards.count).isEqualTo(1.0)
    }

    @Test
    fun `dose mode advances by exactly one per taken dose`() {
        assertThat(
            ReviewProgressCalculator.advance(ReviewCountMode.DOSES, storedCount = 5.0, takenQuantity = 2.0)
        ).isEqualTo(6.0)
    }

    @Test
    fun `quantity mode advances by the amount taken`() {
        assertThat(
            ReviewProgressCalculator.advance(ReviewCountMode.QUANTITY, storedCount = 100.0, takenQuantity = 2.5)
        ).isEqualTo(102.5)
    }

    @Test
    fun `day mode does not advance on a dose`() {
        // Otherwise the second dose of the same day would count as a second day.
        assertThat(
            ReviewProgressCalculator.advance(ReviewCountMode.DAYS, storedCount = 7.0, takenQuantity = 1.0)
        ).isEqualTo(7.0)
    }

    @Test
    fun `a negative amount can never move the count backwards`() {
        assertThat(
            ReviewProgressCalculator.advance(ReviewCountMode.QUANTITY, storedCount = 10.0, takenQuantity = -5.0)
        ).isEqualTo(10.0)
    }

    @Test
    fun `only a dose leaving zero counts`() {
        // Already taken, re-confirmed: no second count.
        assertThat(ReviewProgressCalculator.countsTowardReview(previouslyTaken = 1.0, nowTaken = 1.0)).isFalse()
        // Untouched -> taken: this is the one that counts.
        assertThat(ReviewProgressCalculator.countsTowardReview(previouslyTaken = 0.0, nowTaken = 1.0)).isTrue()
        // A partial dose counts the moment it leaves zero, and not again as it grows.
        assertThat(ReviewProgressCalculator.countsTowardReview(previouslyTaken = 0.0, nowTaken = 0.5)).isTrue()
        assertThat(ReviewProgressCalculator.countsTowardReview(previouslyTaken = 0.5, nowTaken = 1.0)).isFalse()
        // Nothing taken: nothing counted.
        assertThat(ReviewProgressCalculator.countsTowardReview(previouslyTaken = 0.0, nowTaken = 0.0)).isFalse()
    }
}

/**
 * The query the «去搜索» button opens.
 *
 * The text is what the user sees in their browser's search box, so it is asserted literally rather than
 * loosely: a query that reads "阿司匹林复查" instead of "阿司匹林 吃多久需要去复查" is a different question,
 * and search engines rank the two very differently.
 */
class ReviewSearchQueryTest {

    @Test
    fun `the default query is the question the feature promises`() {
        assertThat(ReviewSearchQuery.DEFAULT_QUESTION).isEqualTo("吃多久需要去复查")
    }

    @Test
    fun `the query prefixes the medication name`() {
        assertThat(ReviewSearchQuery.text("阿司匹林")).isEqualTo("阿司匹林 吃多久需要去复查")
    }

    @Test
    fun `a custom question replaces the default`() {
        assertThat(ReviewSearchQuery.text("二甲双胍", "多久复查一次"))
            .isEqualTo("二甲双胍 多久复查一次")
    }

    @Test
    fun `the suffix is appended last`() {
        assertThat(ReviewSearchQuery.text("二甲双胍", "多久复查一次", "糖尿病"))
            .isEqualTo("二甲双胍 多久复查一次 糖尿病")
    }

    @Test
    fun `blank parts are omitted rather than leaving double spaces`() {
        assertThat(ReviewSearchQuery.text("阿司匹林", "", "")).isEqualTo("阿司匹林")
        assertThat(ReviewSearchQuery.text("阿司匹林", "  ", "  ")).isEqualTo("阿司匹林")
        assertThat(ReviewSearchQuery.text("  阿司匹林  ", "多久复查一次"))
            .isEqualTo("阿司匹林 多久复查一次")
    }

    @Test
    fun `the url is the engine base plus the encoded query`() {
        val url = ReviewSearchQuery.url(ReviewSearchEngine.BAIDU, "阿司匹林")

        assertThat(url).startsWith("https://www.baidu.com/s?wd=")
        // Encoded, so the spaces and Chinese characters survive whatever browser opens it. A literal space
        // is the normal case here - the query is "drug name<space>question" - and an unescaped one makes
        // some browsers turn it into "+" and others reject the request.
        assertThat(url).contains("%20")
        assertThat(url).doesNotContain(" ")
        // Chinese characters survive percent-encoding rather than being dropped.
        assertThat(url).contains("%E9%98%BF") // 阿
    }

    @Test
    fun `the encoded query separates the drug name from the question`() {
        // This is the bug the encoding rule exists for: without escaping, the two halves of the query are
        // not distinguishable to the search engine.
        val url = ReviewSearchQuery.url(ReviewSearchEngine.BAIDU, "阿司匹林", "多久复查一次")

        assertThat(url).contains("%20")
        assertThat(url.substringAfter("wd=")).contains("%20")
    }

    @Test
    fun `a square bracket in a question is escaped`() {
        // Legal in a path, meaningless in a query, and enough to make some engines return nothing.
        val encoded = ReviewSearchQuery.encode("药[片剂] 复查")

        assertThat(encoded).doesNotContain("[")
        assertThat(encoded).doesNotContain("]")
        assertThat(encoded).contains("%5B")
        assertThat(encoded).contains("%5D")
    }

    @Test
    fun `each engine gets its own base`() {
        assertThat(ReviewSearchQuery.url(ReviewSearchEngine.BING, "药")).startsWith("https://www.bing.com/search?q=")
        assertThat(ReviewSearchQuery.url(ReviewSearchEngine.SOGOU, "药")).startsWith("https://www.sogou.com/web?query=")
    }

    @Test
    fun `baidu is the default engine`() {
        assertThat(ReviewSearchEngine.DEFAULT).isEqualTo(ReviewSearchEngine.BAIDU)
        assertThat(ReviewSearchEngine.fromName(null)).isEqualTo(ReviewSearchEngine.BAIDU)
        assertThat(ReviewSearchEngine.fromName("NOT_AN_ENGINE")).isEqualTo(ReviewSearchEngine.BAIDU)
    }

    @Test
    fun `engines round trip by name`() {
        for (engine in ReviewSearchEngine.entries) {
            assertThat(ReviewSearchEngine.fromName(engine.name)).isEqualTo(engine)
        }
    }

    @Test
    fun `every suggested phrasing is non-blank and unique`() {
        assertThat(ReviewSearchQuery.SUGGESTED).isNotEmpty()
        assertThat(ReviewSearchQuery.SUGGESTED.toSet()).hasSize(ReviewSearchQuery.SUGGESTED.size)
        assertThat(ReviewSearchQuery.SUGGESTED.none { it.isBlank() }).isTrue()
        // The requirements' own phrasing must be among the things the user can tap.
        assertThat(ReviewSearchQuery.SUGGESTED).contains(ReviewSearchQuery.DEFAULT_QUESTION)
    }
}

/** The counting-mode enum's stored representation, which a backup file depends on. */
class ReviewCountModeTest {

    @Test
    fun `a stored mode round trips`() {
        for (mode in ReviewCountMode.entries) {
            assertThat(ReviewCountMode.fromName(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `an unknown stored mode falls back to counting doses`() {
        assertThat(ReviewCountMode.fromName(null)).isEqualTo(ReviewCountMode.DOSES)
        assertThat(ReviewCountMode.fromName("NOT_A_MODE")).isEqualTo(ReviewCountMode.DOSES)
    }

    @Test
    fun `only the quantity mode has no short unit of its own`() {
        // It borrows the medication's unit (片 / ml / 喷), which is why this one is blank.
        assertThat(ReviewCountMode.QUANTITY.unitShort).isEmpty()
        assertThat(ReviewCountMode.DOSES.unitShort).isEqualTo("次")
        assertThat(ReviewCountMode.DAYS.unitShort).isEqualTo("天")
    }
}
