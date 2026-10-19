package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.DeviceBounds
import dev.zeroinput.ai.api.DeviceElement

/** Conservative local exclusions; model instructions are never an authorization boundary. */
internal object DeviceAgentSafety {
    fun blockedPackage(value: String, ownPackage: String): Boolean {
        val name = value.lowercase()
        return name == ownPackage || name == "android" || name.startsWith("dev.zeroinput.ime") &&
            name != "dev.zeroinput.ime.debug.test" || BLOCKED_PACKAGES.any(name::contains)
    }

    fun sensitiveLabel(value: CharSequence?): Boolean = matches(value, SENSITIVE)
    fun blockedEffect(value: CharSequence?): Boolean = matches(value, EFFECTS)
    fun safeSearchAction(value: String?): Boolean = value?.trim()?.lowercase() in setOf("search", "搜索", "搜尋")

    fun safePointer(element: DeviceElement): Boolean = element.enabled && !element.editable &&
        !element.sensitive && !element.password &&
        !listOf(element.label, element.text).all { it.isNullOrBlank() } &&
        !listOf(element.label, element.text).any(::blockedEffect)

    fun contains(bounds: DeviceBounds, x: Int, y: Int): Boolean =
        x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom

    fun intersects(a: DeviceBounds, b: DeviceBounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun matches(value: CharSequence?, words: Set<String>): Boolean {
        val text = value?.toString()?.lowercase().orEmpty()
        val compact = text.filter(Char::isLetterOrDigit)
        return words.any { word ->
            if (word.all { it in 'a'..'z' } && word.length <= 4) {
                Regex("(?:^|[^a-z])${Regex.escape(word)}(?:$|[^a-z])").containsMatchIn(text)
            } else compact.contains(word)
        }
    }

    private val BLOCKED_PACKAGES = setOf(
        "com.android.systemui", "com.android.settings", "permissioncontroller", "packageinstaller",
        "bank", "wallet", "alipay", "tencent.mm", "tencent.mobileqq", "whatsapp", "telegram",
        "messaging", "mms", "dialer", "contacts", "calendar", "email", "gmail", "outlook",
        "documentsui", "filemanager", "password", "authenticator", "keychain",
    )
    private val SENSITIVE = setOf(
        "password", "passwd", "passcode", "pin", "密码", "口令", "payment", "支付", "cvv", "cvc",
        "verificationcode", "otp", "验证码", "email", "邮箱", "uri", "url", "网址", "链接",
        "creditcard", "银行卡", "身份证", "privatekey", "密钥", "recoveryphrase", "助记词",
    )
    private val EFFECTS = setOf(
        "send", "publish", "post", "pay", "buy", "purchase", "transfer", "delete", "remove",
        "install", "uninstall", "grant", "allow", "permission", "submit", "confirm", "login",
        "signin", "download", "upload", "share", "call", "copy", "paste", "cut", "checkout",
        "subscribe", "reset", "save", "account", "order", "credential", "message", "notification",
        "发送", "发布", "付款", "支付", "购买", "转账", "删除", "移除", "安装", "卸载", "授权",
        "允许", "权限", "提交", "确认", "确定", "登录", "登陆", "下载", "上传", "分享", "共享",
        "呼叫", "拨号", "复制", "粘贴", "剪切", "结算", "订阅", "重置", "保存", "账户", "账号",
        "订单", "短信", "消息", "通知", "联系人", "文件", "日历", "邮件", "银行",
    )
}
