package dev.zeroinput.copyprobe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Constructed public strings only. No acquired output is stored with these references. */
final class PublicOcrCorpus {
    static final class Sample {
        final int index; final String category; final String text;
        final int textSize, padding; final boolean dark, regression;
        Sample(int index, String category, String text, boolean regression) {
            this.index = index; this.category = category; this.text = text; this.regression = regression;
            textSize = regression ? 30 : new int[] {18, 24, 30, 42}[index % 4];
            padding = regression ? 0 : new int[] {0, 12, 30}[(index / 4) % 3];
            dark = !regression && (index / 4) % 2 != 0;
        }
    }
    private PublicOcrCorpus() { }
    static List<Sample> samples() {
        List<Sample> out = new ArrayList<>();
        add(out, "regression", true, "Hello world 123", "今天的天气很好", "订单编号：ABC20261031", "价格 128.50 元",
                "微信 QQ LINE 私密复制", "Hello  world", "https://example.com/a?x=1&y=2", "标点，。！？🙂");
        add(out, "chinese", false, "春天的花园很安静", "请在下一站下车", "这是一份公开测试", "欢迎使用离线识别", "明天上午九点出发", "桌上放着一本新书",
                "山间的小路通向湖边", "我们一起学习汉字", "会议将在周五举行", "书店今天正常营业", "天空中飘着几朵白云", "请确认后再保存内容");
        add(out, "english", false, "The quick brown fox", "A quiet public garden", "Please open the blue door", "Offline recognition sample",
                "Meet me at nine tomorrow", "A book is on the table", "Walking along the river", "Learning one word each day",
                "The meeting starts Friday", "The library is open today", "White clouds in the sky", "Check the text before saving");
        add(out, "mixed", false, "今日天气 Sunny", "会议 Room 204", "版本 Version 3.2", "北京 Beijing 2026", "公开 Public 测试",
                "使用 USB 连接", "订单 ID ABC789", "请输入 Hello 中文", "下载 File 12 完成", "欢迎 Welcome 回来", "第 5 页 Page five", "离线 OCR 识别测试");
        add(out, "amount", false, "总价：128.50元", "USD 19.99", "￥1,234.00", "2026-10-31", "时间 09:08:07", "折扣 12.5%",
                "余额 -0.01", "数量 00001234", "3.1415926535", "1/2 + 3/4 = 5/4", "电话 010-12345678", "EUR 2 345,67");
        add(out, "url", false, "https://example.org", "https://example.net/docs", "http://example.com/test", "example.org/a/b/c",
                "https://example.com/?q=test", "https://example.org/a#part", "https://example.net/a_b-c", "https://example.com/a%20b",
                "https://example.org/?a=1&b=2", "https://example.net:8443/x", "public@example.org", "https://example.com/路径");
        add(out, "spacing", false, "alpha  beta", "one   two   three", "中  文  空格", "首尾空格  ", "  开头空格", "A B  C   D",
                "金额  128.50  元", "a\u00a0b", "中文\u3000全角", "left\tright", "连续    四空格", "two\u00a0\u00a0spaces");
        add(out, "emoji", false, "你好🙂朋友", "开心😀测试", "旅行🚆出发", "家庭👨‍👩‍👧‍👦公开", "爱心❤️文字", "星星⭐测试",
                "旗帜🇨🇳样本", "欢迎👋回来", "完成✅任务", "天气☀️晴朗", "猫咪🐈睡觉", "数字1️⃣测试");
        add(out, "multiline", false, "第一行\n第二行", "Hello\nworld", "价格\n128.50元", "标题\n公开测试正文", "alpha\nbeta\ngamma",
                "A  B\nC  D", "上行 English\n下行中文", "2026\n10\n31", "短句一\n短句二", "https://example.org\n公开地址", "行尾空格  \n下一行", "空白行\n\n结束");
        add(out, "long", false, "This public sentence is deliberately longer than the short line recognition fixtures.",
                "今天我们沿着河边散步经过图书馆和博物馆最后在公园门口集合准备返回。",
                "Order ABC1234567890 contains 12 books and 34 pencils for the public demonstration.",
                "https://example.org/public/documentation/chapter/section?language=en&version=2026",
                "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十",
                "Pack my box with five dozen liquor jugs and then close the public storage room.",
                "公开长行测试同时包含 Chinese characters and English words 以及数字 123456789。",
                "0123456789 9876543210 0123456789 9876543210 0123456789 9876543210",
                "这段文字仅用于公开构造的字符识别实验不包含任何个人信息也不来自其他应用。",
                "The station opens at 07:30 and closes at 22:45 on weekdays and public holidays.",
                "alpha  beta  gamma  delta  epsilon  zeta  eta  theta  iota  kappa  lambda",
                "同一个位图会分别交给基线和候选处理以便比较长行压缩与留白对识别结果的影响。");
        add(out, "punctuation", false, "你好，世界。", "真的吗？是的！", "括号（公开）测试", "引号“中文”测试", "Hello, world!", "What's new?",
                "[item] {value} <text>", "a+b=c; x/y=z", "路径 C:/public/test", "破折号——省略号……", "甲、乙；丙：丁", "price=$12.50 & tax=0%");
        HashSet<String> unique = new HashSet<>();
        for (Sample sample : out) if (!unique.add(sample.text)) throw new AssertionError("Duplicate public fixture index");
        if (out.size() != 128) throw new AssertionError("Public corpus size");
        return out;
    }
    private static void add(List<Sample> out, String category, boolean regression, String... values) {
        for (String value : values) out.add(new Sample(out.size(), category, value, regression));
    }
}
