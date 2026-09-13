package services

/** 语言代码与字符偏移校验（规则5） */
object Validators {

  /** 常见 ISO 639-1/2 主子标签白名单 */
  private val PrimarySubtags: Set[String] = Set(
    "zh", "en", "ja", "ko", "fr", "de", "es", "ru", "ar", "pt", "it", "nl", "pl", "tr",
    "vi", "th", "id", "ms", "hi", "bn", "ta", "ur", "fa", "he", "uk", "cs", "sv", "fi",
    "no", "da", "el", "hu", "ro", "bg", "sr", "hr", "sk", "sl", "lt", "lv", "et", "sw",
    "tl", "mr", "te", "kn", "ml", "pa", "gu", "my", "km", "lo", "ne", "si", "am", "az",
    "be", "ka", "hy", "kk", "ky", "mn", "tg", "tt", "uz", "bs", "mk", "sq", "is", "ga",
    "cy", "eu", "ca", "gl", "af", "zu", "xh", "yo", "ig", "ha", "so", "ps", "ku", "bo",
    "ug", "yi", "lb", "mt", "la", "eo", "jv", "su", "ceb", "ny", "sn", "st", "tn", "ts"
  )

  /** BCP-47 形态：主标签 2-3 小写字母，后续子标签 2-8 位字母数字 */
  private val Bcp47 = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$".r

  def isValidLanguageCode(code: String): Boolean = code match {
    case null              => false
    case Bcp47(_*)         => PrimarySubtags.contains(code.takeWhile(_ != '-').toLowerCase)
    case _                 => false
  }

  /** Unicode 码点长度（JVM String.length 是 UTF-16 码元数，含代理对会偏多） */
  def codePointLength(s: String): Int = s.codePointCount(0, s.length)

  /** 偏移合法性：0 <= start < end <= 文本码点长度 */
  def offsetsValid(start: Int, end: Int, contentLength: Int): Boolean =
    start >= 0 && end > start && end <= contentLength
}
