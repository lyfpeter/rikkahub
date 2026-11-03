package me.rerere.rikkahub.data.skills

/**
 * 预定义的第三方 MCP / Skill 服务目录。
 *
 * 每个服务有一个唯一 [id]，展示名称 [name]、官网地址 [url] 和所属分类 [category]。
 * 用户在 MCP 服务目录页面可以逐个启停，启停状态持久化在 Settings 中。
 */
data class McpServiceEntry(
    val id: String,
    val name: String,
    val url: String,
    val category: McpServiceCategory,
)

enum class McpServiceCategory(val title: String) {
    TRAVEL_LIFE("出行和生活"),
    OFFICE_COLLAB("办公和协作"),
    NOTES_READING("笔记、阅读和网盘"),
    SEARCH_CONTENT("搜索、内容和企业数据"),
    DESIGN_CREATE("设计和创作"),
    PAYMENT_FINANCE("支付和金融"),
    CLOUD_DEV("云和开发"),
}

object McpServiceCatalog {
    val services: List<McpServiceEntry> = listOf(
        // 出行和生活
        McpServiceEntry(
            "amap",
            "高德地图",
            "https://developer.amap.com/api/mcp-server/summary",
            McpServiceCategory.TRAVEL_LIFE
        ),
        McpServiceEntry(
            "baidu_map",
            "百度地图",
            "https://lbs.baidu.com/docs/ai?title=mcpserver/base",
            McpServiceCategory.TRAVEL_LIFE
        ),
        McpServiceEntry(
            "qq_map",
            "腾讯地图",
            "https://lbs.qq.com/service/MCPServer/MCPServerGuide/overview",
            McpServiceCategory.TRAVEL_LIFE
        ),
        McpServiceEntry("didi", "滴滴", "https://mcp.didichuxing.com", McpServiceCategory.TRAVEL_LIFE),
        McpServiceEntry(
            "meituan",
            "美团",
            "https://developer.meituan.com/ai-hub/mcp-list",
            McpServiceCategory.TRAVEL_LIFE
        ),
        McpServiceEntry(
            "taobao",
            "淘宝",
            "https://tblifecdn.taobao.com/taobaopc/skills/taobao-native/SKILL.md",
            McpServiceCategory.TRAVEL_LIFE
        ),
        McpServiceEntry("mcd", "麦当劳中国", "https://open.mcd.cn/mcp/doc", McpServiceCategory.TRAVEL_LIFE),
        McpServiceEntry("luckin", "瑞幸", "https://open.lkcoffee.com/docs", McpServiceCategory.TRAVEL_LIFE),
        McpServiceEntry("fliggy", "飞猪", "https://github.com/alibaba-flyai/flyai-skill", McpServiceCategory.TRAVEL_LIFE),
        McpServiceEntry(
            "tmc_ly",
            "同程商旅",
            "https://tmc.ly.com/zh/resources/updates/open-mcp-skills-cli",
            McpServiceCategory.TRAVEL_LIFE
        ),

        // 办公和协作
        McpServiceEntry("feishu", "飞书", "https://github.com/larksuite/cli", McpServiceCategory.OFFICE_COLLAB),
        McpServiceEntry(
            "dingtalk",
            "钉钉",
            "https://open.dingtalk.com/document/ai-dev/dingtalk-server-api-mcp-overview",
            McpServiceCategory.OFFICE_COLLAB
        ),
        McpServiceEntry("wecom", "企业微信", "https://work.weixin.qq.com/nl/index/aicli", McpServiceCategory.OFFICE_COLLAB),
        McpServiceEntry(
            "tencent_docs",
            "腾讯文档",
            "https://docs.qq.com/open/document/mcp/",
            McpServiceCategory.OFFICE_COLLAB
        ),
        McpServiceEntry(
            "tencent_meeting",
            "腾讯会议",
            "https://cloud.tencent.com/document/product/1095/133826",
            McpServiceCategory.OFFICE_COLLAB
        ),
        McpServiceEntry(
            "wps",
            "WPS",
            "https://open.wps.cn/documents/app-integration-dev/mcp-server/introduction",
            McpServiceCategory.OFFICE_COLLAB
        ),
        McpServiceEntry("kdocs", "金山文档", "https://github.com/kdocs-app/kdocs-skill", McpServiceCategory.OFFICE_COLLAB),
        McpServiceEntry("yuque", "语雀", "https://github.com/yuque/yuque-mcp-server", McpServiceCategory.OFFICE_COLLAB),
        McpServiceEntry("qq_mail", "QQ 邮箱", "https://github.com/Tencent/AgentlyMail", McpServiceCategory.OFFICE_COLLAB),

        // 笔记、阅读和网盘
        McpServiceEntry("weread", "微信读书", "https://weread.qq.com/r/weread-skills", McpServiceCategory.NOTES_READING),
        McpServiceEntry(
            "tencent_ima",
            "腾讯 ima",
            "https://ima.qq.com/agent-interface",
            McpServiceCategory.NOTES_READING
        ),
        McpServiceEntry("get_note", "Get 笔记", "https://biji.com/skill", McpServiceCategory.NOTES_READING),
        McpServiceEntry("flomo", "flomo", "https://help.flomoapp.com/advance/mcp", McpServiceCategory.NOTES_READING),
        McpServiceEntry(
            "yinxiang",
            "印象笔记",
            "https://skillhub.cn/skills/yinxiangskill",
            McpServiceCategory.NOTES_READING
        ),
        McpServiceEntry(
            "youdao_note",
            "有道云笔记",
            "https://note.youdao.com/help-center/skill-guide.html",
            McpServiceCategory.NOTES_READING
        ),
        McpServiceEntry(
            "quark_drive",
            "夸克网盘",
            "https://quark.cn/documents/help/quark-drive-skill",
            McpServiceCategory.NOTES_READING
        ),
        McpServiceEntry("weiyun", "腾讯微云", "https://weiyun.com/act/openclaw", McpServiceCategory.NOTES_READING),
        McpServiceEntry(
            "baidu_netdisk",
            "百度网盘",
            "https://pan.baidu.com/union/doc/mcp-server/",
            McpServiceCategory.NOTES_READING
        ),

        // 搜索、内容和企业数据
        McpServiceEntry("zhihu", "知乎", "https://developer.zhihu.com/docs", McpServiceCategory.SEARCH_CONTENT),
        McpServiceEntry(
            "bocha",
            "博查搜索",
            "https://github.com/BochaAI/bocha-search-mcp",
            McpServiceCategory.SEARCH_CONTENT
        ),
        McpServiceEntry("qichacha", "企查查", "https://agent.qcc.com/guide", McpServiceCategory.SEARCH_CONTENT),
        McpServiceEntry("tianyancha", "天眼查", "https://github.com/tyc-tech/tyc-cli", McpServiceCategory.SEARCH_CONTENT),
        McpServiceEntry(
            "douyin",
            "抖音开放平台",
            "https://developer.open-douyin.com/docs/resource/zh-CN/dop/ability/mcp-service/mcp-service-desc",
            McpServiceCategory.SEARCH_CONTENT
        ),
        McpServiceEntry("oceanengine", "巨量引擎", "https://open.oceanengine.com/mcp", McpServiceCategory.SEARCH_CONTENT),

        // 设计和创作
        McpServiceEntry(
            "mastergo",
            "MasterGo",
            "https://mastergo.com/help/ai-features/magic-mcp.html",
            McpServiceCategory.DESIGN_CREATE
        ),
        McpServiceEntry("modao", "墨刀", "https://modao.cc/feature/ai-mcp.html", McpServiceCategory.DESIGN_CREATE),
        McpServiceEntry(
            "pixso",
            "Pixso",
            "https://pixso.net/developer/zh/mcp/remote-mcp.html",
            McpServiceCategory.DESIGN_CREATE
        ),
        McpServiceEntry("kling", "可灵", "https://github.com/klingai-tech/skills", McpServiceCategory.DESIGN_CREATE),
        McpServiceEntry(
            "minimax",
            "MiniMax",
            "https://github.com/MiniMax-AI/MiniMax-MCP",
            McpServiceCategory.DESIGN_CREATE
        ),
        McpServiceEntry("netease_music", "网易云音乐", "https://music.163.com/st/ncmcli", McpServiceCategory.DESIGN_CREATE),

        // 支付和金融
        McpServiceEntry("alipay", "支付宝", "https://opendocs.alipay.com/open/0h3gdq", McpServiceCategory.PAYMENT_FINANCE),
        McpServiceEntry(
            "wechat_pay",
            "微信支付",
            "https://pay.weixin.qq.com/doc/v3/merchant/4035576700",
            McpServiceCategory.PAYMENT_FINANCE
        ),
        McpServiceEntry(
            "unionpay",
            "银联",
            "https://open.unionpay.com/tjweb/solution/detail?solId=613",
            McpServiceCategory.PAYMENT_FINANCE
        ),
        McpServiceEntry("ifind", "同花顺 iFinD", "https://mcp.51ifind.com", McpServiceCategory.PAYMENT_FINANCE),
        McpServiceEntry("wencai", "同花顺问财", "https://search.10jqka.com.cn/skillhub", McpServiceCategory.PAYMENT_FINANCE),
        McpServiceEntry(
            "dfcfs",
            "东方财富妙想",
            "https://marketing.dfcfs.com/views/finskillshub/",
            McpServiceCategory.PAYMENT_FINANCE
        ),

        // 云和开发
        McpServiceEntry("aliyun", "阿里云", "https://api.aliyun.com/mcp", McpServiceCategory.CLOUD_DEV),
        McpServiceEntry(
            "tencent_cloud",
            "腾讯云",
            "https://cloud.tencent.com/document/product/1278/131433",
            McpServiceCategory.CLOUD_DEV
        ),
        McpServiceEntry(
            "huaweicloud",
            "华为云",
            "https://github.com/huaweicloud/huaweicloud-mcp",
            McpServiceCategory.CLOUD_DEV
        ),
        McpServiceEntry("volcengine", "火山引擎", "https://github.com/volcengine/mcp-server", McpServiceCategory.CLOUD_DEV),
        McpServiceEntry(
            "baidu_cloud",
            "百度智能云",
            "https://ai.baidu.com/ai-doc/REFERENCE/mm9tjwod0",
            McpServiceCategory.CLOUD_DEV
        ),
        McpServiceEntry("qiniu", "七牛云", "https://github.com/qiniu/qiniu-mcp-server", McpServiceCategory.CLOUD_DEV),
        McpServiceEntry(
            "zhipu",
            "智谱",
            "https://docs.bigmodel.cn/cn/coding-plan/mcp/search-mcp-server",
            McpServiceCategory.CLOUD_DEV
        ),
        McpServiceEntry(
            "gitee",
            "Gitee",
            "https://blog.gitee.com/2026/01/22/gitee-official-mcp-server-ai-code-repo/",
            McpServiceCategory.CLOUD_DEV
        ),
        McpServiceEntry(
            "jihulab",
            "极狐 GitLab",
            "https://gitlab.cn/news/cmvg3kvmv000nv30iuo1aifgg",
            McpServiceCategory.CLOUD_DEV
        ),
    )

    /** 按分类分组返回服务列表，保持 [McpServiceCategory] 枚举顺序 */
    fun grouped(): Map<McpServiceCategory, List<McpServiceEntry>> =
        McpServiceCategory.entries.associateWith { cat ->
            services.filter { it.category == cat }
        }
}
