using System.Text;

namespace CorpusReviewClient.Services;

/// <summary>文本工具：Unicode 码点偏移 ↔ UTF-16 索引、时间格式化</summary>
public static class TextUtil
{
    /// <summary>按码点偏移截取（后端偏移均为 Unicode 码点；C# 字符串是 UTF-16）</summary>
    public static string SliceByCodePoints(string s, int startCp, int endCp)
    {
        var sb = new StringBuilder();
        var i = 0;
        var cp = 0;
        while (i < s.Length && cp < endCp)
        {
            var len = char.IsHighSurrogate(s[i]) && i + 1 < s.Length ? 2 : 1;
            if (cp >= startCp) sb.Append(s, i, len);
            i += len;
            cp++;
        }
        return sb.ToString();
    }

    /// <summary>ISO-8601 时间字符串 → 本地可读格式；解析失败原样返回</summary>
    public static string FmtTime(string iso)
    {
        if (DateTimeOffset.TryParse(iso, out var dto))
            return dto.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");
        return iso;
    }
}
