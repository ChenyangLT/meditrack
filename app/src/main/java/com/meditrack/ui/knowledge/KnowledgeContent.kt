package com.meditrack.ui.knowledge

/**
 * One article in the offline knowledge base.
 *
 * @property id stable identifier; used as the list key and by [KnowledgeContent.EMERGENCY_ARTICLE_ID].
 * @property category which section the article belongs to.
 * @property title the headline shown in a list.
 * @property summary one-line summary shown collapsed.
 * @property body body paragraphs. Lines starting with "- " render as bullets; "## " as a sub-heading.
 * @property keywords search terms that should also match this article.
 */
data class KnowledgeArticle(
    val id: String,
    val category: KnowledgeCategory,
    val title: String,
    /** One-line summary shown collapsed. */
    val summary: String,
    /** Body paragraphs. Lines starting with "- " render as bullets; "## " as a sub-heading. */
    val body: String,
    /** Search terms that should also match this article. */
    val keywords: List<String> = emptyList(),
)

/**
 * The sections of the knowledge base, in the order they are shown.
 *
 * Declaration order is the display order - [KnowledgeContent.grouped] relies on it, so a new
 * category should be inserted where it belongs to be read, not where it is convenient to add.
 */
enum class KnowledgeCategory(val label: String, val blurb: String) {
    TIMING("服药时机", "饭前饭后、空腹随餐，到底在讲究什么"),
    MISSED_DOSE("漏服怎么办", "补还是不补，通用原则与常见错误"),
    INTERACTION("药物相互作用", "为什么要把你吃的一切都告诉医生"),
    STORAGE("存放与有效期", "阴凉干燥避光，开封之后另有讲究"),
    MYTHS("常见误区", "十四条最常听到的想当然"),
    LEAFLET("怎样看说明书", "五项重点，教你自己读懂它"),
    EMERGENCY("紧急情况", "过敏、过量、吃错药，先做什么"),
    PREPARATION("去医院前的准备", "带什么、怎么说、复查问什么"),
}

/**
 * The offline knowledge base: general medication common sense, written for everyone.
 *
 * ## What this is
 *
 * A curated reference that ships inside the APK. It answers the questions people actually ask a
 * pharmacist - 饭前到底是多前、漏了一次要不要补、冰箱能不能放药 - and it teaches the reader to
 * read their own 说明书 instead of memorising anything from here.
 *
 * ## What this is deliberately not
 *
 * - **Not personalised.** No article knows what the reader takes.
 * - **Not drug-specific.** No brand names, no doses, no numbers to copy.
 * - **Not a substitute for anything.** Every article ends with [ARTICLE_FOOTER], the screen opens
 *   with [DISCLAIMER], and nothing here ever tells a reader to start, stop or change a prescription.
 *
 * Each article is a few hundred characters, so it can be read standing in a pharmacy queue.
 */
object KnowledgeContent {

    /**
     * The one prominent disclaimer shown at the top of the screen.
     *
     * It is a single string rather than a list of lines because it is rendered as one paragraph: a
     * disclaimer that has been split into bullet points reads like a feature list, and this one has
     * to read like a sentence somebody means.
     */
    const val DISCLAIMER: String =
        "这里讲的是所有人都适用的用药常识，不针对某种药、某种病或某个人，也不能替代医生和药师的判断。" +
            "任何关于剂量、停药、换药的决定，请以医嘱和随药说明书为准；身体出现不适，请及时就医。"

    /** Appended to the end of every article body by the shared renderer, never repeated by hand. */
    const val ARTICLE_FOOTER: String = "以上为一般性科普，不能替代医嘱或药品说明书。"

    /** Shown at the end of a category section, so every section closes with a disclaimer. */
    const val CATEGORY_FOOTER: String = "以上为该分类的一般性科普，不能替代医嘱或药品说明书。"

    /** The one article the screen pins above everything else. */
    const val EMERGENCY_ARTICLE_ID: String = "emergency_call_120"

    val articles: List<KnowledgeArticle> = listOf(

        // ------------------------------------------------------------------ 服药时机
        KnowledgeArticle(
            id = "timing_why_it_matters",
            category = KnowledgeCategory.TIMING,
            title = "为什么同一种药，服用时间会影响效果",
            summary = "饭前、饭后、空腹、随餐不是随手写的，它们决定药吸收多少、刺激多大。",
            body = """
                「饭前服用」「饭后服用」不是习惯写法，而是三件事的简称：药能不能被吸收、对胃的刺激有多大、它在血液里什么时候达到最高浓度。

                ## 时间点到底在控制什么
                - 吸收：有些药遇到食物会吸收得更多或更少，血里的浓度因此偏高或偏低。
                - 刺激：有些药本身刺激胃黏膜，食物可以起到缓冲作用。
                - 代谢：进食后肝脏血流和胃肠蠕动都会改变，药物被处理的速度也随之变化。

                ## 为什么不能靠感觉安排
                少数药的安全范围很窄：浓度稍微低一点就没有效果，高一点又容易不舒服。这类药对时间的要求最严格，随意挪动的代价也最大。

                ## 具体到你的药怎么办
                不要凭这篇文章自己挪时间。药盒、说明书或处方上写着什么，就按什么执行；不清楚时把药名和包装一起拿给药师，问一句「这个药是饭前还是饭后」，是最省事的办法。
            """.trimIndent(),
            keywords = listOf("时间", "饭前", "饭后", "空腹", "随餐", "吸收", "血药浓度"),
        ),
        KnowledgeArticle(
            id = "timing_before_after_meal",
            category = KnowledgeCategory.TIMING,
            title = "饭前、饭后、空腹、随餐，各是什么意思",
            summary = "饭前通常指用餐之前的一小段时间，空腹指胃里基本没有食物。",
            body = """
                这几个词看起来相近，实际指向不同的胃内状态。

                ## 空腹
                指胃里基本没有食物的时候，例如清晨起床后，或上一餐之后经过了一段较长的时间。空腹强调的是「不受食物干扰」。

                ## 饭前
                一般指用餐之前的一小段时间，目的是让药在进食前先进入胃肠，趁着胃排空快、吸收顺畅。

                ## 饭后
                指吃完饭后的一小段时间内服用。食物在这里不是干扰，而是一层缓冲：既减轻对胃的刺激，也可能帮助某些药吸收。

                ## 随餐 / 与食物同服
                指把药和饭一起、或在进餐过程中服用，例如吃到一半时服用。它和「饭后」并不完全等同：随餐依靠的是食物正在胃里的那段时间。

                ## 睡前
                通常指准备上床之前服用，多用于需要夜间起效、或白天服用容易让人困倦的药。

                ## 拿不准的时候
                不同医院、不同医生对「饭前」的提前量理解可能略有差别。与其猜，不如在开药或取药时当面问清一次，之后照着执行。
            """.trimIndent(),
            keywords = listOf("饭前", "饭后", "空腹", "随餐", "睡前", "同服", "空肚子"),
        ),
        KnowledgeArticle(
            id = "timing_water_and_posture",
            category = KnowledgeCategory.TIMING,
            title = "用什么送服，吃完能不能马上躺下",
            summary = "温开水是最稳的选择；吞下药片后立刻平躺，可能让它卡在食管上。",
            body = """
                ## 送服的液体
                温开水是最不容易出错的选择：它不干扰药物吸收，也不会额外刺激胃。

                - 茶、咖啡、可乐等含咖啡因的饮料可能让人心慌、影响睡眠，也可能干扰某些药的代谢。
                - 牛奶会与一部分药物结合、影响吸收，除非说明书明确说可以。
                - 酒精与很多药物都会互相影响，服药期间最好不饮酒。
                - 果汁里最需要留意的是西柚（葡萄柚），它的影响可以持续很久，详见「药物相互作用」分类。

                ## 服药的姿势
                站着或坐着服药更好。吞下药片后马上躺平，药片有可能停在食管里，局部溶解后刺激食管黏膜，引起灼痛或吞咽不适。服完药后保持坐立一会儿，让药片顺利进入胃里。

                ## 吞咽困难怎么办
                如果药片很大、或你吞咽比较费劲，先问药师能不能掰开、能不能换成其他剂型。有些缓释片、肠溶片一旦掰开，就失去了原本的设计，既影响效果也增加风险。
            """.trimIndent(),
            keywords = listOf("送服", "温水", "牛奶", "茶水", "姿势", "躺下", "吞咽", "掰开"),
        ),
        KnowledgeArticle(
            id = "timing_interval_and_meals",
            category = KnowledgeCategory.TIMING,
            title = "「一天几次」是怎么安排的，和吃饭撞上怎么办",
            summary = "分次的意义在于让血里的浓度保持平稳；实在错不开时，稳定比精确更重要。",
            body = """
                ## 间隔的用意
                把一天的剂量分成几次，是为了让血液里的药物浓度维持在一个有效的区间里，不至于一下太高、一下太低。间隔越均匀，浓度越平稳。

                ## 常见的日间分布
                很多方案会顺着三餐来安排，这样不容易忘。但顺着三餐安排，间隔本身就不完全均匀，这是临床上普遍接受的折中。

                ## 和吃饭撞上了怎么办
                - 如果药需要空腹，而你马上要吃饭，通常可以按说明书或医嘱提前一点服用。
                - 如果药需要随餐，而你正好在吃饭，那就趁现在吃。
                - 如果方案要求严格按固定间隔，优先保证间隔规律，其次才是和饭点对齐。

                ## 该问谁
                不同药对时间的要求不同，具体到你的方案，以处方和说明书为准。如果一天里实在排不开，把整套时间表拿给药师看一眼，通常能帮你排出一个更可行的顺序。
            """.trimIndent(),
            keywords = listOf("一天几次", "间隔", "三餐", "时间表", "安排"),
        ),

        // ------------------------------------------------------------------ 漏服怎么办
        KnowledgeArticle(
            id = "missed_three_rules",
            category = KnowledgeCategory.MISSED_DOSE,
            title = "漏服了，通用原则只有三条",
            summary = "想起了就补，靠近下一次就跳过，永远不要自行加倍。",
            body = """
                发现漏服时先别慌。通用的处理思路可以概括成三句话。

                ## 第一句：想起来就补
                如果距离原定时间还不算久，通常可以立刻补服，然后把后面的时间大致顺延。补得越早，越接近原本的节奏。

                ## 第二句：快到下一次就跳过
                如果已经接近下一次服药时间，一般就直接跳过这一次，按原计划吃下一次。两次挨得太近，等于短时间内吃了两份。

                ## 第三句：不要自行加倍
                把漏掉的量加到下一次上，是最常见也最危险的错误。它会让血药浓度短时间内冲高，不舒服的风险随之增加。

                ## 为什么没有统一答案
                不同药物的半衰期和安全范围都不一样：有的漏一次几乎无影响，有的则不能漏。所以「补还是不补」的最终依据，是这种药的说明书和医生当时的交代。

                ## 记下来比补救更重要
                把漏服如实记在应用里。连续几次漏服会被医生看见，从而调整方案，这比你自己悄悄补上更有价值。
            """.trimIndent(),
            keywords = listOf("漏服", "忘记", "补服", "跳过", "原则"),
        ),
        KnowledgeArticle(
            id = "missed_why_not_double",
            category = KnowledgeCategory.MISSED_DOSE,
            title = "为什么「下次吃双份」是错的",
            summary = "加倍不是把欠的补上，而是把血药浓度推到可能有害的高度。",
            body = """
                ## 药物在体内不是一本账
                很多人把服药想成记账：少了一次，下次补上就平了。但药物浓度是随时间上升和下降的曲线，不是可以累加的余额。

                ## 加倍会发生什么
                - 短时间内血药浓度可能超出安全范围，出现头晕、恶心、心慌、嗜睡等不适。
                - 有些药的安全范围很窄，浓度一高就可能造成实质损伤，例如影响听力、肾脏或心律。
                - 副作用出现后，人往往自行停药，原本的治疗被打断，反而更糟。

                ## 正确的顺序
                1. 先看这种药的说明书里有没有「漏服」这一节，很多说明书会写明处理办法。
                2. 说明书没写、或你看不懂时，打电话问开药的医院、药师或家庭医生。
                3. 如果都联系不上，宁可跳过这一次，也不要自行加倍。

                ## 一句能记住的话
                漏服是少了一次，加倍可能是一次事故。补与不补，都请听医嘱。
            """.trimIndent(),
            keywords = listOf("加倍", "双份", "补服", "过量", "漏服"),
        ),
        KnowledgeArticle(
            id = "missed_by_regimen",
            category = KnowledgeCategory.MISSED_DOSE,
            title = "不同吃法漏了以后，大致怎么想",
            summary = "长期维持、按需服用、疗程用药，处理思路并不相同。",
            body = """
                下面说的是思路，不是规定。真正执行前，请看说明书或问医生药师。

                ## 长期维持用药
                这类药往往需要体内一直保持稳定的浓度，漏服的影响相对明显。想起来得早，通常补上；离下一次很近，就跳过并恢复原节奏。

                ## 按需服用
                只在有症状时服用的药，本身就不按固定节奏走。症状已经过去了，通常不必为了「补上」而再吃一次。

                ## 有明确疗程的用药
                例如一段时间的抗感染治疗，最怕时断时续。漏服以后尽快补上、并接着按原计划走，比直接停掉更符合治疗目标。是否补、怎么补，仍以医嘱为准。

                ## 一天一次的药
                这种方案本身间隔很长，想起来时通常离下一次还有一段时间，因此补服的机会更多；但如果已经快到第二天的服药时间，就不宜再补。

                ## 记录本身就是补救
                在应用里如实标记这次漏服或跳过。复查时医生看到真实的执行情况，才能判断是方案不合适，还是提醒方式需要调整。
            """.trimIndent(),
            keywords = listOf("长期", "按需", "疗程", "抗生素", "记录", "复诊"),
        ),

        // ------------------------------------------------------------------ 药物相互作用
        KnowledgeArticle(
            id = "interaction_tell_doctor_everything",
            category = KnowledgeCategory.INTERACTION,
            title = "为什么要告诉医生你吃的每一种东西",
            summary = "处方药、自己买的药、保健品、中药，都可能互相影响。",
            body = """
                ## 「相互作用」说的是什么
                简单说，就是两种或更多物质同时进入身体后，彼此的效果发生了变化：一种让另一种吸收得更多或更少，或者让它在体内停留得更久、排出得更快。结果是药效变弱，或者不舒服的风险变高。

                ## 为什么医生需要知道全部
                医生开药时，是在你当前身体状态的基础上做加减。如果你没说自己在吃别的东西，他看到的就不是完整的画面。

                ## 需要主动说出口的
                - 其他医院、其他科室开的处方药。
                - 自己买的非处方药，尤其是止痛药、感冒药、胃药。
                - 中成药、中药饮片、偏方。
                - 保健品与营养补充剂，例如钙片、铁剂、维生素、鱼油、褪黑素。
                - 偶尔才用的药：安眠药、抗过敏药、外用药膏。
                - 饮酒与吸烟的习惯。

                ## 一个好习惯
                把正在用的东西列一张清单，存在手机里或写在纸上，每次看病、每次买药都拿出来。清单不需要很专业，写清名字和大概用法就够。
            """.trimIndent(),
            keywords = listOf("相互作用", "冲突", "清单", "保健品", "中药", "配伍"),
        ),
        KnowledgeArticle(
            id = "interaction_grapefruit",
            category = KnowledgeCategory.INTERACTION,
            title = "西柚（葡萄柚）为什么会和很多药冲突",
            summary = "它会抑制体内负责分解药物的酶，让药物在血里停留得更久。",
            body = """
                ## 机制
                肠道和肝脏里有一类酶，负责把很多药物分解掉一部分，让它们不至于在体内堆积。西柚以及部分柚子类水果会抑制这类酶的工作。

                ## 结果
                酶被抑制后，药物分解变慢，血液里的浓度可能升高，相当于在不知情的情况下加大了量。这种影响可以持续较长时间，不是「喝一点点没关系」那么简单。

                ## 需要注意的形式
                - 新鲜西柚、西柚汁。
                - 混合果汁里含西柚成分的。
                - 部分柚子（文旦、蜜柚）也可能有类似作用，程度因品种而异。

                ## 怎么办
                - 正在服药的人，最稳妥的做法是先把西柚类水果放一放。
                - 如果你很爱吃，或者不确定自己吃的药是否受影响，请在开药、取药时直接问：「我吃的这些药，能不能吃西柚？」
                - 不要因为看到这篇文章就自行停掉任何一种处方药。
            """.trimIndent(),
            keywords = listOf("西柚", "葡萄柚", "柚子", "果汁", "相互作用", "酶"),
        ),
        KnowledgeArticle(
            id = "interaction_alcohol",
            category = KnowledgeCategory.INTERACTION,
            title = "服药期间喝酒，风险在哪",
            summary = "酒精会放大镇静、伤胃、伤肝的作用，也可能让药效失控。",
            body = """
                ## 酒精本身就有作用
                酒精会抑制中枢神经、扩张血管、刺激胃黏膜，还要由肝脏代谢。药物往往作用于同样的系统，两者叠加，效果不是简单相加，而是互相放大。

                ## 常见的几类风险
                - 与镇静催眠类、部分抗过敏药、部分止痛药同用：嗜睡、反应变慢的风险上升。
                - 与解热镇痛类同用：胃黏膜和肝脏的负担加重。
                - 与降糖类同用：血糖波动更难预料。
                - 与部分抗感染药同用：可能出现明显的不适反应。

                ## 现实一点的建议
                - 服药期间尽量不饮酒；如果一定要喝，先问医生或药师这种药能不能与酒精同用。
                - 「少喝一点」不等于安全：有些反应与喝了多少关系不大。
                - 药酒、含酒精的饮料，以及某些含酒精的药品（例如部分止咳糖浆、藿香正气水一类）都要算进去。

                ## 记住
                是否必须戒酒，取决于你在吃什么药。这个判断请交给医生或药师，不要自己估计。
            """.trimIndent(),
            keywords = listOf("酒精", "喝酒", "饮酒", "药酒", "相互作用"),
        ),
        KnowledgeArticle(
            id = "interaction_otc_and_supplements",
            category = KnowledgeCategory.INTERACTION,
            title = "自己买的止痛药、感冒药和保健品也要算进「正在服用」",
            summary = "重复的成分、叠加的止痛药，是最常见也最容易避免的相互作用。",
            body = """
                ## 最容易踩的坑：同一种成分吃两遍
                很多复方感冒药里都含有解热镇痛成分。如果同时再吃一片止痛药，或者同时吃两种感冒药，同一种成分就被吃了两份，而你可能完全没意识到。

                ## 保健品不是「没有作用」
                - 钙、铁、镁等矿物质会与部分药物结合、影响吸收，通常需要与药物错开一段时间服用。
                - 某些草本制品会影响肝脏代谢酶的活性，进而改变其他药物在体内的浓度。
                - 维生素、鱼油等也并非对所有人都合适，例如正在使用抗凝相关药物的人需要先咨询医生。

                ## 错开时间有用吗
                对「在胃肠道里互相结合」这类影响，错开服用时间通常有效；对「影响肝脏代谢」这类影响，错开时间基本没用。所以哪些能靠错开解决，要具体问药师。

                ## 每次买药前的一句话
                「我正在吃这些药，这个能不能一起吃？」把清单给药师看，是最快也最便宜的安全检查。
            """.trimIndent(),
            keywords = listOf("止痛药", "感冒药", "保健品", "钙片", "铁剂", "维生素", "错开", "重复成分"),
        ),

        // ------------------------------------------------------------------ 存放与有效期
        KnowledgeArticle(
            id = "storage_cool_dry_dark",
            category = KnowledgeCategory.STORAGE,
            title = "阴凉、干燥、避光，为什么不能放卫生间",
            summary = "湿度和温度是药品最常见的两个杀手，而卫生间两样都占。",
            body = """
                ## 三个条件各自防什么
                - 阴凉：温度偏高会加快药物分解，糖浆、栓剂、部分生物制品尤其明显。
                - 干燥：潮湿会让片剂吸潮、变软、发霉，胶囊壳也可能粘连变形。
                - 避光：部分药物见光后结构会改变，所以它们装在棕色瓶或铝箔里。

                ## 为什么卫生间最不合适
                洗澡带来的水汽让卫生间长期湿度偏高，温度也随热水波动。把药放在镜柜或洗手台上，等于每天给它做一次湿热处理。

                ## 更合适的位置
                - 卧室或客厅里一个固定的抽屉、柜子，远离暖气、灶台和窗台。
                - 尽量用原包装保存，吃完立刻盖紧瓶盖，不要把瓶里的干燥剂倒掉。
                - 分装盒只放当次或当天的量，不要提前把一周的药都剥出来。

                ## 放在固定位置的好处
                除了保存更稳妥，还能减少「我到底吃没吃」的疑问，并且让孩子和宠物够不到。
            """.trimIndent(),
            keywords = listOf("存放", "保存", "卫生间", "阴凉", "干燥", "避光", "潮湿"),
        ),
        KnowledgeArticle(
            id = "storage_fridge",
            category = KnowledgeCategory.STORAGE,
            title = "哪些药要放冰箱，哪些千万别放",
            summary = "只有包装上写了冷藏的才需要放冰箱，而且通常是保鲜层，不是冷冻层。",
            body = """
                ## 判断依据只有一条
                看包装或说明书上的「贮藏」一项。写了「冷藏」「2～8℃」之类的，才需要放进冰箱；没有写的，通常放在阴凉干燥处就够。

                ## 可能需要冷藏的常见类型
                - 部分胰岛素类制剂与某些生物制品。
                - 部分滴眼液、滴鼻液、糖浆。
                - 部分栓剂，天热时容易变软。

                ## 不适合放冰箱的情况
                - 普通片剂、胶囊放冰箱反而容易吸潮，取出来时表面结露，更容易变质。
                - 糖浆类在低温下可能析出结晶或变得不均匀。
                - 冷冻层一律不适合：结冰会破坏很多药物的结构。

                ## 放冰箱的讲究
                - 放保鲜层，不要放在冰箱门上，因为开关门时温度波动大。
                - 密封好，与食物分开，避免串味和污染。
                - 从冰箱取出的滴眼液之类，按说明书要求回温后再用，不要直接点进去。

                ## 拿不准
                把包装拿给药师看一眼，问「这个要不要放冰箱」，比凭印象决定可靠得多。
            """.trimIndent(),
            keywords = listOf("冰箱", "冷藏", "保鲜", "胰岛素", "滴眼液", "栓剂", "贮藏"),
        ),
        KnowledgeArticle(
            id = "storage_desiccant_and_packaging",
            category = KnowledgeCategory.STORAGE,
            title = "干燥剂、棉花和分装盒",
            summary = "干燥剂要留在瓶里；把一周的药提前剥出来，会同时丢掉包装和日期。",
            body = """
                ## 瓶里的干燥剂
                干燥剂的作用是吸收瓶内的水汽，只要药还在瓶里，它就有用，不要随手扔掉。每次取药后尽快盖紧瓶盖，比什么措施都有效。

                ## 瓶口的棉花
                有些瓶口塞着棉花，是为了运输途中防止药片磕碰。开封后如果说明书没有特别要求，通常可以取出，因为棉花会吸附水汽并把它留在瓶内。

                ## 分装盒的边界
                - 分装盒适合放当天或次日的药，方便外出时携带。
                - 不适合把一周甚至更长时间的药全部剥出来：离开铝箔和瓶子的保护，药片会更快受潮、氧化、见光分解。
                - 分装盒本身要保持清洁干燥，定期清洗晾干，不要长期残留碎屑。

                ## 也不要在药盒上随手写字
                有人习惯直接在药盒上标注别的用法，容易与原本的标签混淆。需要提醒自己时，写一张单独的纸条，或直接记录在应用的备注里。
            """.trimIndent(),
            keywords = listOf("干燥剂", "棉花", "分装盒", "药盒", "剥药", "原包装"),
        ),
        KnowledgeArticle(
            id = "storage_expiry_and_disposal",
            category = KnowledgeCategory.STORAGE,
            title = "有效期、开封后使用期，以及过期药怎么处理",
            summary = "有效期说的是未开封、按规定保存的前提；开封之后另有一套计时方式。",
            body = """
                ## 有效期的前提
                包装上的有效期，成立的条件是「未开封」并且「按说明书的条件保存」。一旦开封，或者你把它放在了不合适的地方，这个日期就不再是可靠的保证。

                ## 开封后的使用期
                有些药在说明书里单独写了开封后的使用期限，例如部分滴眼液、糖浆、胰岛素类制剂。这类药一旦开封，就按它自己的规则计时，而不是继续看外包装上的大日期。

                ## 怎么判断
                - 看包装和说明书上有没有「启用后」「开封后」之类的字样。
                - 拿笔在盒子上写下开封日期，是最简单也最有效的办法。
                - 药片出现变色、斑点、裂纹、粘连，胶囊变软发黏，液体浑浊或有异味，就不要再用了。

                ## 过期药怎么处理
                - 不要冲进马桶或水槽，也不要随手丢进厨房垃圾桶。
                - 最稳妥的是交给药店的过期药回收点，或社区的药品回收箱。
                - 如果没有回收点：把药片从包装里取出，混进不易被人和动物取食的废弃物中，密封后再按当地要求丢弃。
                - 处理前把标签撕掉或用笔涂掉个人信息，保护自己的隐私。
            """.trimIndent(),
            keywords = listOf("有效期", "过期", "开封", "使用期", "回收", "处理", "变质"),
        ),

        // ------------------------------------------------------------------ 常见误区
        KnowledgeArticle(
            id = "myth_about_the_medicine",
            category = KnowledgeCategory.MYTHS,
            title = "误区四则：关于「药」本身的想当然",
            summary = "中药并非没有副作用，保健品不能替代药，贵的、新的也不等于更适合你。",
            body = """
                ## 误区一：中药、中成药没有副作用
                「天然」不等于「无害」。中药同样有药理作用，也可能伤肝、伤肾，或与其他药相互影响。部分中成药里还含有西药成分，容易与其他药叠加。用之前同样要告诉医生。

                ## 误区二：保健品可以替代药
                保健品的作用是补充或辅助，不是治疗。该用药物控制的病情，用保健品顶替，代价可能是病情进展。

                ## 误区三：进口的、贵的、新的药一定更好
                药物的选择取决于病情、既往反应、肝肾功能、合并用药和可及性。一种药对别人效果好，不代表对你合适。

                ## 误区四：多吃几种药好得快
                同时使用多种药物，相互作用与不舒服的风险都会明显上升，还可能因为分不清是哪种药在起作用而难以调整。方案越简单越好，这也是医生追求的目标。
            """.trimIndent(),
            keywords = listOf("误区", "中药", "副作用", "保健品", "进口药", "多吃几种"),
        ),
        KnowledgeArticle(
            id = "myth_about_how_to_take",
            category = KnowledgeCategory.MYTHS,
            title = "误区四则：关于「怎么吃」的想当然",
            summary = "「饭前」不等于随便什么时候，「症状好转就停药」是很多治疗失败的起点。",
            body = """
                ## 误区一：饭前就是空着肚子，什么时候都行
                「饭前」指的是与进餐相关的一个时间点，不是「任何没吃饭的时候」。刚吃完一大袋零食，胃里并不空，同样会影响吸收。

                ## 误区二：漏服了下次加倍补
                加倍会让血药浓度突然升高，风险大于收益。正确做法是看说明书或问医生药师，必要时宁可跳过这一次。

                ## 误区三：症状好转就可以停药
                很多治疗需要完成整个疗程，症状消失只是表面现象。尤其是抗感染治疗，中途停药容易导致反复，甚至让病原体产生耐药。

                ## 误区四：药片大了就掰开，胶囊难吞就倒出来
                缓释、控释、肠溶的设计都在剂型里。掰开或倒出内容物，可能让药物一次性释放，既影响效果也增加风险。能不能掰，看说明书或问药师。
            """.trimIndent(),
            keywords = listOf("误区", "停药", "饭前", "加倍", "掰开", "胶囊", "疗程"),
        ),
        KnowledgeArticle(
            id = "myth_about_other_people",
            category = KnowledgeCategory.MYTHS,
            title = "误区三则：拿别人的经验和自己的感觉当依据",
            summary = "别人吃得好不等于你能吃；自我感觉良好也不等于可以随意调整。",
            body = """
                ## 误区一：别人吃这个药效果好，我也能吃
                同一种病在不同人身上，病因、分期、合并症可能完全不同。别人剩下的药，对你既可能无效，也可能有害，而且缺少医生对剂量的判断。

                ## 误区二：输液比吃药快，所以更好
                输液把药直接送进血管，起效确实快。但它也绕过了身体的多道屏障，过敏和不良反应来得更快、更重，还需要穿刺和医疗环境。是否输液由医生根据病情决定，不是可以自己选的「加强版」。

                ## 误区三：感觉好了就减量，感觉不好就加量
                自我调量会让血药浓度忽高忽低。多数药物需要稳定一段时间才能判断效果，凭一两天的主观感受调整，往往既看不出真实疗效，又增加了风险。

                ## 一条共同的原则
                别人的经验值得听，但不能照搬；自己的感觉值得说，但要告诉医生，而不是自己动手改方案。
            """.trimIndent(),
            keywords = listOf("误区", "别人", "输液", "自我调量", "加量", "减量"),
        ),
        KnowledgeArticle(
            id = "myth_about_expiry_and_keeping",
            category = KnowledgeCategory.MYTHS,
            title = "误区三则：关于过期、外观和「省着用」",
            summary = "过期药外观没变也可能已经失效；剩下的处方药不等于下次还能吃。",
            body = """
                ## 误区一：过期药看着没变，应该还能吃
                化学分解往往先发生、后显形。等到药片变色、变味时，可能已经分解得相当严重；而在外观变化之前，药效也可能早就下降了。过期药的价值，不值得拿疗效和安全去换。

                ## 误区二：放在卫生间随手就能拿到，最方便
                卫生间又湿又热，是药品最不适合长期存放的地方之一。方便取用和保存得当并不冲突：在干燥阴凉处设一个固定位置，就能兼顾。

                ## 误区三：剩下的药留着，下次有同样症状再吃
                自行复用剩下的处方药有两个问题：这次的情况未必和上次相同；剩余的药也可能已经过期或保存不当。是否适合再用，需要医生判断。

                ## 一个更稳妥的习惯
                定期整理一次药箱：把过期的挑出来规范处理，把还在用的按原包装收好，把正在服用的清单更新一遍。这件事一年做两次就够，收益很大。
            """.trimIndent(),
            keywords = listOf("误区", "过期", "外观", "复用", "剩药", "整理药箱"),
        ),

        // ------------------------------------------------------------------ 怎样看说明书
        KnowledgeArticle(
            id = "leaflet_first_five_sections",
            category = KnowledgeCategory.LEAFLET,
            title = "拿到说明书，先看这五项",
            summary = "适应症、用法用量、不良反应、禁忌、注意事项，是信息量最大的五节。",
            body = """
                说明书的字很小、很长，但结构是固定的。掌握顺序以后，查一次只要一两分钟。

                ## 【适应症】或【功能主治】
                说明这个药用来治什么、缓解什么。先确认与自己情况是否吻合，不吻合就不要自行使用。

                ## 【用法用量】
                写的是怎么用、什么时候用、用多少。这是最需要看懂的一节，也是「遵医嘱」这句话的落点。

                ## 【不良反应】
                列出使用后可能出现的各种反应，包括常见的和罕见的。列出来不等于一定会发生，它的用途是让你在身体出现异常时能对上号。

                ## 【禁忌】
                写的是什么情况下不能用。这一节比不良反应更要紧，属于红线。

                ## 【注意事项】
                介于两者之间的提醒：特殊人群、需要监测的指标、与其他药的相互影响、饮食禁忌等。

                ## 其余部分
                【规格】【贮藏】【有效期】【批准文号】【生产企业】用于核对、保存和追溯。批准文号是这盒药通过审批的凭证，可以在包装和说明书上对照。
            """.trimIndent(),
            keywords = listOf("说明书", "适应症", "用法用量", "不良反应", "禁忌", "注意事项", "批准文号"),
        ),
        KnowledgeArticle(
            id = "leaflet_dosage_vs_doctor",
            category = KnowledgeCategory.LEAFLET,
            title = "【用法用量】和医生说的不一样，听谁的",
            summary = "说明书是面向所有人的通用文本，医嘱是针对你的具体决定。",
            body = """
                ## 为什么会有差别
                说明书上的用量是经过审批的通用范围，要覆盖尽可能多的人。医生会根据你的体重、年龄、肝肾功能、病情严重程度和合并用药，在合理范围内作出个体化的安排。所以「和说明书不完全一样」并不罕见。

                ## 该听谁的
                以开药的医生或药师当面交代的为准。药盒上药房贴的用法标签，通常就是医嘱的转写，是最直接的依据。

                ## 有两件事要立刻确认
                - 如果你发现医嘱明显超出说明书的范围，或者你对此有疑问，请直接回去问开药的医生，而不是自己按说明书改。
                - 如果你拿到的药与处方上的名字、规格不一致，先别服用，回药房核对。

                ## 一个实用做法
                把医嘱写在药盒上或记在应用的备注里，包括时间以及与进食的关系。复查时带着清单去，医生能快速看清你在怎么用。

                ## 不要做的两件事
                不要自己加量，也不要自己减量或停药。任何调整都通过医生或药师。
            """.trimIndent(),
            keywords = listOf("说明书", "用法用量", "医嘱", "剂量", "遵医嘱"),
        ),
        KnowledgeArticle(
            id = "leaflet_adverse_and_contraindications",
            category = KnowledgeCategory.LEAFLET,
            title = "【不良反应】【禁忌】【注意事项】怎么区分",
            summary = "禁忌是红线，不良反应是可能出现的情况，注意事项是需要额外小心的提醒。",
            body = """
                ## 【禁忌】：不能用的情形
                通常包括对成分过敏、某些疾病状态、与特定药物合用等。这一节是红线，属于「不能用」，而不是「小心用」。

                ## 【不良反应】：可能出现的反应
                按系统或按发生率排列，常见、少见、罕见依次列出。看到一长串不必恐慌：多数反应发生率很低，而且停药或对症处理之后可以恢复。它真正的用途，是让你在身体出现异常时知道该怀疑什么。

                ## 【注意事项】：需要额外照顾的情形
                - 儿童、孕妇及哺乳期、老年人、肝肾功能不全者是否需要调整。
                - 用药期间需要监测哪些指标、多久复查一次。
                - 与哪些食物、饮料、其他药物需要错开或避免。
                - 用药后是否影响开车、操作机械。

                ## 怎么用这三节
                用药前，用【禁忌】和【注意事项】给自己做一次筛查；用药后，用【不良反应】对照身体的反应。出现严重或持续加重的反应，不要等，直接就医并把药带去。
            """.trimIndent(),
            keywords = listOf("不良反应", "禁忌", "注意事项", "过敏", "监测", "说明书"),
        ),

        // ------------------------------------------------------------------ 紧急情况
        KnowledgeArticle(
            id = "emergency_call_120",
            category = KnowledgeCategory.EMERGENCY,
            title = "出现这些情况，先打 120",
            summary = "呼吸困难、面唇肿胀、意识改变、剧烈不适——不要等，也不要自己开车。",
            body = """
                ## 需要立刻呼叫急救的表现
                - 呼吸困难、喘不上气、喉咙发紧、声音突然嘶哑。
                - 面部、口唇、舌头或咽喉肿胀。
                - 全身大片皮疹，同时伴头晕、心慌、出冷汗。
                - 意识模糊、叫不醒、抽搐。
                - 剧烈胸痛、严重心悸，或眼前发黑、站不住。
                - 大量误服药物，或孩子误服了成人药。

                ## 打 120 时说什么
                说清地址（楼栋、单元、楼层、门牌）、患者目前的状况，以及「怀疑药物过敏」或「可能服药过量」。保持电话畅通，按调度员的指示做。

                ## 等待期间
                - 让患者平卧，头部偏向一侧，防止呕吐物堵住气道；呼吸不畅时可采取半坐位。
                - 松开衣领和腰带，保持通风。
                - 不要自行喂水、喂药或催吐，除非调度员或医生明确要求。
                - 有人陪同，记录症状出现的时间与变化。

                ## 记住
                这类情况的处理窗口很短。宁可白跑一趟，也不要在家观察。
            """.trimIndent(),
            keywords = listOf("120", "急救", "紧急", "休克", "呼吸困难", "急诊"),
        ),
        KnowledgeArticle(
            id = "emergency_allergy",
            category = KnowledgeCategory.EMERGENCY,
            title = "药物过敏：从皮疹到休克，有一个升级过程",
            summary = "皮疹往往只是开始；一旦出现呼吸或循环的症状，就是急症。",
            body = """
                ## 轻度表现
                皮肤瘙痒、散在红疹、荨麻疹、局部轻微肿胀。多数在用药后一段时间内出现。

                ## 需要警惕的升级信号
                - 皮疹迅速蔓延到全身。
                - 嘴唇、眼皮、舌头或咽喉肿起来。
                - 胸闷、喘、喉咙有堵塞感。
                - 头晕、心慌、出冷汗、脸色苍白。
                - 恶心呕吐，并伴有上面任何一项。

                ## 这时候怎么做
                出现升级信号，按急症处理：立刻拨打 120，不要自己开车去医院，也不要先观察半小时。如果医生此前给你开过肾上腺素自动注射笔之类的急救药，按他教过的方式使用，并且仍然要叫救护车。

                ## 之后一定要做的事
                - 记下这次用的药名、出现症状的时间与表现，最好拍照留存。
                - 就诊时明确告诉每一位医生「我对某种药过敏」，并请对方在病历上留下记录。
                - 去药房取药时主动说明过敏史，避免再次拿到同类药物。

                ## 不要自己下结论
                到底对哪种成分过敏，需要医生判断。曾经出现过反应，就把这件事当成永久的病史对待。
            """.trimIndent(),
            keywords = listOf("过敏", "皮疹", "荨麻疹", "休克", "喉头水肿", "急救"),
        ),
        KnowledgeArticle(
            id = "emergency_overdose",
            category = KnowledgeCategory.EMERGENCY,
            title = "吃多了：第一时间做什么",
            summary = "不要催吐，带上药盒立刻就医；时间就是关键。",
            body = """
                ## 第一步：停下来，清点
                把吃过的药、剩下的药、包装都找出来，记录大概吃了多少、什么时候吃的。信息越准确，医生处理得越快。

                ## 第二步：联系急救或直接就医
                - 出现任何不适，或服用量明显超出平常，拨打 120，或立刻前往最近的急诊。
                - 如果误服的是孩子，即使看起来没事也要就医，儿童对很多成分更敏感。
                - 打电话时可以直接说明「疑似药物过量」，便于对方提前准备。

                ## 第三步：带上药
                把药盒、说明书、剩下的药，以及你记录的时间一起带去医院。医生需要知道具体成分和剂型，才能判断怎么处理。

                ## 不要做的事
                - 不要自行催吐。有些药物腐蚀性强，或容易造成误吸，催吐会造成二次伤害。
                - 不要用牛奶、豆浆之类「中和」，也不要自行服用所谓的解毒偏方。
                - 不要因为「感觉还好」就在家观察。不少药物的作用会延迟出现。

                ## 预防
                把药放在孩子够不到的地方，不与糖果混放，分装时看清标签。
            """.trimIndent(),
            keywords = listOf("过量", "吃多", "催吐", "急诊", "误服", "120"),
        ),
        KnowledgeArticle(
            id = "emergency_wrong_medicine",
            category = KnowledgeCategory.EMERGENCY,
            title = "吃错药，或者把外用药吃了进去",
            summary = "先确认吃的是什么、吃了多少，再决定是观察还是立即就医。",
            body = """
                ## 先确认三件事
                1. 吃下去的是哪一种药、什么剂型（片剂、胶囊、糖浆、外用药）。
                2. 大概多少量、什么时候吃的。
                3. 现在有没有不舒服。

                ## 处理顺序
                - 立刻停止继续服用，把药和包装收好。
                - 拨打 120 或联系就近急诊，把上面三条信息告诉医生，由医生判断是否需要立即就诊。
                - 即使暂时没有症状，也要按医生的意见观察，因为有些反应会延迟出现。

                ## 特别要当心的几类
                - 外用药误服：碘伏、酒精、清凉油、外用激素药膏等，都不适合进入消化道。
                - 眼药水、滴鼻液被当成口服药：浓度和辅料完全不同。
                - 缓释片、肠溶片被掰开或嚼碎后一次吃下。
                - 儿童误服成人药。

                ## 之后
                把这次的情况告诉你的医生，并在家里重新整理药品：外用药与口服药分开放，标签朝外，照明充足。
            """.trimIndent(),
            keywords = listOf("吃错药", "误服", "外用药", "碘伏", "眼药水", "急诊"),
        ),

        // ------------------------------------------------------------------ 去医院前的准备
        KnowledgeArticle(
            id = "prep_what_to_bring",
            category = KnowledgeCategory.PREPARATION,
            title = "去医院前，带上这四样东西",
            summary = "一份用药清单、所有药盒、最近的化验单，以及你想问的问题。",
            body = """
                ## 一、正在服用的用药清单
                写清每一种药的名字、用法、用了多久，包括自己买的药、保健品和中药。手写一张纸就够，也可以从应用里导出记录打印出来。

                ## 二、药盒或包装
                原包装上有规格、厂家和批准文号，比口述准确得多。看不清名字的时候，直接把药盒带上是最省事的办法。

                ## 三、最近的化验单和检查报告
                医生需要看趋势，而不是只看一次结果。把最近几次的化验单按时间排好，能省下大量重复检查。

                ## 四、你想问的问题
                提前写下来，避免一进诊室就忘。例如「这个药要吃多久」「和我在吃的其他药会不会冲突」「出现什么情况要马上来医院」。

                ## 额外的小提醒
                - 复查前照常服药，不要为了「让检查更准」而自行停药，除非医生明确要求。
                - 带上医保卡、就诊卡与既往病历。
                - 如果由家属代为描述，最好有一个人全程陪同并记下医嘱。
            """.trimIndent(),
            keywords = listOf("去医院", "就诊", "清单", "化验单", "药盒", "复诊"),
        ),
        KnowledgeArticle(
            id = "prep_describe_symptoms",
            category = KnowledgeCategory.PREPARATION,
            title = "怎么把症状说清楚，让医生少走弯路",
            summary = "时间、变化、与用药的关系，比形容词更有用。",
            body = """
                ## 用时间线代替形容词
                「很难受」不如「从三天前的晚上开始，饭后一小时左右出现，持续半小时」。医生最需要的是时间线：什么时候开始、怎么变化、什么时候加重或缓解。

                ## 值得主动说清的四件事
                1. 症状出现的时间，以及和服药时间的关系：是刚加药之后，还是一直都有。
                2. 症状的性质与部位，有没有放射到别处。
                3. 有没有伴随的表现：发热、皮疹、呕吐、心慌、乏力。
                4. 以前有没有出现过同样的情况，当时是怎么处理的。

                ## 一定要说明的用药情况
                - 最近有没有新加药、换药、停药。
                - 有没有漏服，大概漏了几次。
                - 有没有同时吃别的药、保健品，或饮酒。

                ## 就诊时的小技巧
                - 带一张纸把要点按顺序写好，照着说。
                - 医生说的关键结论，当场复述一遍确认。
                - 离开前问清：药怎么吃、吃到什么时候、什么时候复查、出现什么情况要提前来。
            """.trimIndent(),
            keywords = listOf("症状", "描述", "时间线", "就诊", "用药史", "沟通"),
        ),
        KnowledgeArticle(
            id = "prep_review_questions",
            category = KnowledgeCategory.PREPARATION,
            title = "复查时可以问的几个问题",
            summary = "把复查当成一次核对：方案是否还合适，下一步怎么走。",
            body = """
                ## 关于当前的方案
                - 现在的药还需要继续吗？大概要用到什么时候？
                - 剂量和服用时间需要调整吗？
                - 有没有可以简化、减少种类或次数的空间？

                ## 关于效果与安全
                - 目前这些指标说明效果怎么样？
                - 需要重点监测哪些指标，多久查一次？
                - 出现哪些情况要立刻来医院，哪些可以下次复查再说？

                ## 关于相互作用与新药
                - 我最近在吃这些东西（出示清单），有没有冲突？
                - 如果其他科室要给我开新药，需要注意什么？
                - 有没有哪些常见的感冒药、止痛药我需要避开？

                ## 复查之外的两件事
                - 把医生的话记在用药清单上，回家后逐条落实。
                - 复查提醒可以在应用里为每种药分开设置，到时间会提醒你，不必靠记性。

                ## 最后
                复查不是「没消息就是好消息」的等待，而是一次主动的核对：把问题带去，把答复带回来。
            """.trimIndent(),
            keywords = listOf("复查", "复诊", "提问", "方案", "监测", "清单"),
        ),
    )

    /**
     * Search over title, summary, body and keywords; a category label or blurb also matches.
     *
     * Empty matches: a blank query returns everything, and the query is trimmed first, so a stray
     * space from the keyboard does not turn the screen into an empty result list. Matching is
     * case-insensitive so that an English keyword typed in either case still hits.
     */
    fun search(query: String): List<KnowledgeArticle> {
        val needle = query.trim()
        if (needle.isEmpty()) return articles
        return articles.filter { article ->
            article.title.contains(needle, ignoreCase = true) ||
                article.summary.contains(needle, ignoreCase = true) ||
                article.body.contains(needle, ignoreCase = true) ||
                article.category.label.contains(needle, ignoreCase = true) ||
                article.category.blurb.contains(needle, ignoreCase = true) ||
                article.keywords.any { it.contains(needle, ignoreCase = true) }
        }
    }

    /**
     * Articles grouped by category in enum declaration order, omitting empty categories.
     *
     * Empty categories are dropped rather than shown as an empty card: when the screen hands in a
     * filtered list (it pins the emergency article above the list), a section with nothing in it
     * would be a header that opens onto nothing.
     */
    fun grouped(articles: List<KnowledgeArticle>): List<Pair<KnowledgeCategory, List<KnowledgeArticle>>> =
        KnowledgeCategory.entries.mapNotNull { category ->
            val inCategory = articles.filter { it.category == category }
            if (inCategory.isEmpty()) null else category to inCategory
        }
}
