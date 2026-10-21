package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.DeviceAgentLimits
import dev.zeroinput.ai.api.DeviceBounds
import dev.zeroinput.ai.api.DeviceElement

/** Conservative local exclusions; model instructions are never an authorization boundary. */
internal object DeviceAgentSafety {
    fun blockedPackage(value: String, ownPackage: String): Boolean {
        val name = value.lowercase()
        return name == ownPackage || name == "android" || name.startsWith("dev.zeroinput.ime") &&
            name != "dev.zeroinput.ime.debug.test" || name in BLOCKED_PACKAGE_NAMES ||
            BLOCKED_PACKAGES.any(name::contains)
    }

    fun blockedNotificationPackage(value: String, ownPackage: String): Boolean {
        val name = value.lowercase()
        return name == ownPackage || name == "android" || name in NOTIFICATION_SYSTEM_PACKAGES ||
            NOTIFICATION_SYSTEM_PACKAGES.any(name::contains) || name in BLOCKED_PACKAGE_NAMES ||
            NOTIFICATION_BLOCKED_SUBSTRINGS.any(name::contains)
    }

    fun sensitiveLabel(value: CharSequence?): Boolean = matches(value, SENSITIVE, DeviceAgentLimits.MAX_ELEMENT_TEXT)
    fun sensitiveLabel(value: CharSequence?, limit: Int): Boolean = matches(value, SENSITIVE, limit)
    fun sensitiveValue(value: CharSequence?, limit: Int = DeviceAgentLimits.MAX_ELEMENT_TEXT): Boolean {
        val text = boundedText(value, limit) ?: return false
        return EMAIL_VALUE.containsMatchIn(text) || URI_VALUE.containsMatchIn(text)
    }
    fun sensitivePointerLabel(value: CharSequence?): Boolean =
        matches(value, POINTER_SENSITIVE, DeviceAgentLimits.MAX_ELEMENT_TEXT)
    fun sensitivePointerLabel(value: CharSequence?, limit: Int): Boolean =
        matches(value, POINTER_SENSITIVE, limit)
    fun blockedEffect(value: CharSequence?): Boolean = matches(value, EFFECTS, DeviceAgentLimits.MAX_ELEMENT_TEXT)
    fun blockedPointerEffect(value: CharSequence?): Boolean =
        matches(value, POINTER_EFFECTS, DeviceAgentLimits.MAX_ELEMENT_TEXT)
    fun safeSearchAction(value: String?): Boolean = value?.trim()?.lowercase() in setOf("search", "搜索", "搜尋")

    fun safePointer(element: DeviceElement): Boolean {
        val labels = listOf(element.label, element.text)
        return element.enabled && !element.editable && !element.sensitive && !element.password &&
            labels.any { !it.isNullOrBlank() } &&
            labels.none { sensitiveValue(it) || sensitivePointerLabel(it) || blockedPointerEffect(it) }
    }

    /** Converts only a small, already bounded window of a platform CharSequence. */
    fun boundedText(value: CharSequence?, limit: Int = DeviceAgentLimits.MAX_ELEMENT_TEXT): String? {
        if (value == null || limit <= 0 || value.length == 0 || value.length > limit) return null
        val text = value.toString().trim()
        if (text.isEmpty() || text.length > limit) return null
        return text.takeIf { it.none { character -> character.isISOControl() && character !in "\n\r\t" } }
    }

    fun contains(bounds: DeviceBounds, x: Int, y: Int): Boolean =
        x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom

    /**
     * Bounded role label for one platform node. An empty class name would violate the
     * [DeviceElement] contract and abort the whole snapshot, so it falls back to "View".
     */
    fun roleLabel(className: CharSequence?): String =
        className?.toString()?.substringAfterLast('.')?.take(128)?.takeIf { it.isNotEmpty() } ?: "View"

    fun intersects(a: DeviceBounds, b: DeviceBounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun matches(value: CharSequence?, words: Set<String>, limit: Int): Boolean {
        val text = boundedText(value, limit)?.lowercase() ?: return false
        val compact = text.filter(Char::isLetterOrDigit)
        return words.any { word ->
            if (word.all { it in 'a'..'z' } && word.length <= 4) {
                SHORT_WORD_PATTERNS[word]?.containsMatchIn(text) == true
            } else compact.contains(word)
        }
    }

    private val BLOCKED_PACKAGES = setOf(
        "com.android.systemui", "com.android.settings", "permissioncontroller", "packageinstaller",
        "bank", "wallet", "alipay", "paypal", "venmo", "tencent.mm", "tencent.mobileqq", "whatsapp",
        "telegram", "messaging", "mms", "dialer", "contacts", "calendar", "email", "gmail", "outlook",
        "documentsui", "filemanager", "password", "authenticator", "keychain",
    )
    private val BLOCKED_PACKAGE_NAMES = setOf(
        "com.google.android.gm", "com.paypal.android.p2pmobile", "com.paypal.here",
        "com.venmo", "com.squareup.cash", "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail", "com.google.android.apps.docs",
        "com.google.android.apps.messaging", "com.android.messaging", "com.android.mms",
        "com.samsung.android.messaging", "com.google.android.dialer", "com.android.dialer",
        "com.chase.sig.android",
    )
    private val NOTIFICATION_SYSTEM_PACKAGES = setOf(
        "com.android.systemui", "com.android.settings", "permissioncontroller", "packageinstaller",
        "com.android.providers.settings", "keychain",
    )
    private val NOTIFICATION_BLOCKED_SUBSTRINGS = setOf(
        "gmail", "paypal", "bank", "wallet", "outlook", "venmo",
    )
    private val SENSITIVE = setOf(
        "password", "passwd", "passcode", "pin", "密码", "口令", "payment", "支付", "cvv", "cvc",
        "verificationcode", "securitycode", "logincode", "onetimecode", "2facode", "authcode",
        "authenticationcode", "otp", "验证码", "动态码", "校验码", "email", "邮箱", "uri", "url",
        "网址", "链接",
        "creditcard", "银行卡", "身份证", "privatekey", "密钥", "recoveryphrase", "助记词",
        "seedphrase", "mnemonic", "ssn", "socialsecurity", "passport", "护照", "bank", "banking", "wallet",
    )
    private val EFFECTS = setOf(
        "send", "publish", "post", "pay", "buy", "purchase", "transfer", "delete", "remove",
        "install", "uninstall", "update", "upgrade", "clear", "erase", "wipe", "reveal",
        "logout", "signout", "grant", "allow", "permission", "submit", "confirm", "login",
        "signin", "download", "upload", "share", "call", "copy", "paste", "cut", "checkout",
        "subscribe", "reset", "save", "account", "order", "credential", "message", "notification",
        "发送", "发布", "付款", "支付", "购买", "转账", "删除", "移除", "安装", "卸载", "更新",
        "升级", "清除", "清空", "擦除", "授权", "允许", "权限", "提交", "确认", "确定", "登录",
        "登陆", "注销", "退出登录", "下载", "上传", "分享", "共享", "呼叫", "拨号", "复制",
        "粘贴", "剪切", "结算", "订阅", "重置", "保存", "账户", "账号", "订单", "短信", "消息",
        "通知", "联系人", "文件", "日历", "邮件", "银行",
    )
    // Generic navigation labels must stay usable; destructive verbs and credential
    // nouns remain blocked on the same pointer path.
    private val POINTER_SENSITIVE = SENSITIVE - setOf("email", "邮箱", "uri", "url", "网址", "链接")
    private val POINTER_EFFECTS = EFFECTS - setOf(
        "account", "message", "notification", "账户", "账号", "消息", "通知",
    )
    private val SHORT_WORD_PATTERNS: Map<String, Regex> by lazy {
        (SENSITIVE + EFFECTS)
            .filter { word -> word.all { it in 'a'..'z' } && word.length <= 4 }
            .associateWith { word -> Regex("(?:^|[^a-z])${Regex.escape(word)}(?:$|[^a-z])") }
    }
    private val EMAIL_VALUE = Regex(
        "(?iu)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,63}(?![\\p{L}\\p{N}.-])",
    )
    private val URI_VALUE = Regex(
        "(?iu)(?:[a-z][a-z0-9+.-]{1,31}://|www\\.)[^\\s]+|(?<![\\p{L}\\p{N}-])(?:[a-z0-9-]+\\.)+[a-z]{2,63}(?:[/?:#][^\\s]*)?",
    )
}
