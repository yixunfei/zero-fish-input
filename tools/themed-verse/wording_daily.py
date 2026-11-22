"""Build compact daily-note slots against the pinned Wanxiang base lexicon."""
from hashlib import sha256
from pathlib import Path
from struct import pack
import json

ROOT = Path(__file__).resolve().parents[2]
BASE = "dicts/jichu.dict.yaml"
TIMES = """
清晨 早上 晨间 凌晨 上午 中午 午后 下午 傍晚 黄昏 入夜 晚上 深夜 夜里
今早 今晨 今夜 明早 明晚 明天 昨天 今天 今晚 后天 周末 平时 平日 假日
雨天 晴天 阴天 雪天 春天 夏天 秋天 冬天 午休 下班 收工 饭后 餐后
睡前 出门 回家 进门 到家 闲时 稍后 待会 回头
"""
# The second clause stays in the first clause's topic. Each row shares
# one set of verbs with objects for which those verbs are idiomatic.
TOPICS = (
    (
        ("备好 带上 拿上 装好 收好 拿出 取出 放好 准备",
         """外套 雨伞 帽子 手套 围巾 口罩 鞋套 纸巾 毛巾 手帕 水壶 水杯 饭盒 餐具
         茶杯 背包 提包 钱包 钥匙 门卡 车票 钢笔 铅笔 耳机 电池 手机 相机 手表 眼镜
         书签 字典 画笔 画册 草稿 纸袋 书包 笔袋 雨衣 拖鞋 便签 地图 手册 胶带
         台历 文件 笔记 习题 图册 饮料 水果 饼干 盒饭 便当 面包 零食 干粮 名片
         硬币 镜子 梳子 牙刷 牙膏 扑克 收据 账本 灯具 证件 皮夹 刷子 纸笔"""),
        ("拿来 带回 换上 叠好 摆好",
         "衬衣 裙子 衣服 裤子 袜子 睡衣 短袖 衬衫 毛衣 床单 被套 枕套 长裤 校服"),
    ),
    (
        ("核对 查看 翻阅 翻看 整理 查阅 查找",
         """材料 资料 文档 备忘 记录 文件 公告 通知 邮件 信件 报表 图表 账目
         账单 清单 目录 订单 报告 日志 申请 表格 单据 收据 发票 笔记 样本
         样稿 稿件 稿纸 图纸 档案 方案 教案 计划 提纲 日报 周报 月报 摘要
         证书 票据 凭证 样张 样刊 合同 条款 账册 账簿 账本 页码 名录 名单
         留言 邮件 批注 附件 备注 文稿 底稿 题目"""),
        ("归档 保存 备份 标记 摘录 更新",
         "材料 资料 文档 记录 文件 公告 通知 邮件 信件 报表 图表 清单 订单 报告 日志 笔记 稿件 方案 教案 计划 日报 周报 月报 提纲 摘要 票据 名录 名单 留言 备注 文稿 底稿"),
    ),
    (
        ("擦拭 擦洗 擦净 清理 清洁 检查",
         """窗台 桌面 台面 灶台 地板 地面 木门 房门 铁门 门框 门板 门把
         窗框 窗户 玻璃 屏幕 镜面 镜子 浴缸 水槽 水池 盆架 鞋架 书架
         墙面 柜台 椅子 凳子 桌子 抽屉 柜门 水箱 水桶 脸盆 瓷砖 扶手
         楼梯 栏杆 护栏 杯架 钢架 桌角 柜顶 水管 灯罩 餐桌 隔板 灶具
         油壶 锅盖 壶盖 杯盖 煎锅 蒸锅 托盘 烤盘 花盆 菜盆 餐盘 茶盘"""),
        ("洗净 清洗 刷洗 冲洗 晾干",
         "衣袖 裤腿 手绢 手套 袖套 围裙 抹布 浴巾 床罩 被单 桌布 椅套 坐垫 地垫 毛刷 软刷 锅铲 饭勺 漏勺 碗碟 盘子 砂锅 案板 砧板 保温杯"),
    ),
    (
        ("查看 检查 关注 留意 留心",
         """天气 风向 风速 雨量 水位 潮汐 潮位 温度 气温 湿度 云量 能见度
         路面 路况 路线 车况 车次 班次 时刻 车票 站牌 路标 标志 信号
         路况 路灯 路段 道路 车站 出口 入口 站台 车位 公路 街道 坡道 隧道
         泊位 码头 航班 里程 指示 车窗 轮胎 胎压 脚架 刹车 油量 电量 车灯"""),
        ("确认 记下 记录 留意 核对",
         "日程 行程 地址 时间 地点 位置 路口 站名 方向 班车 班次 座位 车次 航班 里程 车位 票价 费用 价格 路线 路标"),
    ),
)


def daily_asset():
    locked = json.loads((ROOT / "tools/dictionaries/sources.lock.json").read_bytes())["preparedFiles"][BASE]
    source = ROOT / "engine-rime/build/generated/publicDictionaryAssets/rime" / BASE
    raw = source.read_bytes()
    if sha256(raw).hexdigest() != locked:
        raise ValueError("daily wording lexicon does not match the lockfile")
    terms = {word for word in TIMES.split() if len(word) == 2}
    for topic in TOPICS:
        for verbs, objects in topic:
            terms.update(word for word in (verbs + " " + objects).split() if len(word) == 2)
    available = set()
    for line in raw.split(b"\n"):
        word, tab, _ = line.partition(b"\t")
        if tab and len(word) == 6:
            text = word.decode()
            if text in terms:
                available.add(text)
    def entries(text):
        return {word for word in text.split() if word in available}

    def section(words):
        return pack(">H", len(words)) + "".join(sorted(words)).encode("utf-16-be")

    seen = set()
    topics = []
    for topic in TOPICS:
        phrases = {verb + obj for verbs, objects in topic for verb in entries(verbs) for obj in entries(objects)} - seen
        seen.update(phrases)
        topics.append(phrases)
    return section(entries(TIMES)) + bytes([len(topics)]) + b"".join(map(section, topics))
