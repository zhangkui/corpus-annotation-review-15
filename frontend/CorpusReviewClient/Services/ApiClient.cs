using System.Net.Http.Json;
using System.Text.Json;
using CorpusReviewClient.Models;

namespace CorpusReviewClient.Services;

/// <summary>后端 API 错误（携带 HTTP 状态码与服务端错误消息）</summary>
public class ApiException(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}

public class ApiClient(HttpClient http)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<T> GetAsync<T>(string url) => Parse<T>(await SendOrThrow(HttpMethod.Get, url, null));

    public async Task<T> PostAsync<T>(string url, object body) => Parse<T>(await SendOrThrow(HttpMethod.Post, url, body));

    public async Task<T> PutAsync<T>(string url, object body) => Parse<T>(await SendOrThrow(HttpMethod.Put, url, body));

    /// <summary>返回原始响应（成功标志 + 正文），用于 422 也携带业务数据的导入接口</summary>
    public async Task<(bool Ok, string Body)> PostRawAsync(string url, object body) =>
        await SendAsync(HttpMethod.Post, url, body);

    private async Task<(bool Ok, string Body)> SendOrThrow(HttpMethod method, string url, object? body)
    {
        var resp = await SendAsync(method, url, body);
        if (!resp.Ok) throw new ApiException(resp.Status, ExtractError(resp.Body));
        return resp;
    }

    private async Task<(bool Ok, int Status, string Body)> SendAsync(HttpMethod method, string url, object? body)
    {
        using var req = new HttpRequestMessage(method, url);
        if (body is not null)
            req.Content = JsonContent.Create(body, options: JsonOptions);
        using var resp = await http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        return (resp.IsSuccessStatusCode, (int)resp.StatusCode, text);
    }

    private static T Parse<T>((bool Ok, int Status, string Body) resp) =>
        JsonSerializer.Deserialize<T>(resp.Body, JsonOptions)
        ?? throw new ApiException(500, "响应反序列化失败");

    private static string ExtractError(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("error", out var e))
                return e.GetString() ?? body;
        }
        catch (JsonException) { /* 非 JSON 响应 */ }
        return body;
    }

    // ---- 便捷方法 ----

    public Task<List<AppUser>> Users() => GetAsync<List<AppUser>>("api/users");
    public Task<List<Corpus>> Corpora() => GetAsync<List<Corpus>>("api/corpora");
    public Task<List<Segment>> Segments(string? language = null, long? corpusId = null)
    {
        var qs = new List<string>();
        if (!string.IsNullOrWhiteSpace(language)) qs.Add($"language={Uri.EscapeDataString(language)}");
        if (corpusId is not null) qs.Add($"corpusId={corpusId}");
        var suffix = qs.Count > 0 ? "?" + string.Join("&", qs) : "";
        return GetAsync<List<Segment>>("api/segments" + suffix);
    }
    public Task<Segment> Segment(long id) => GetAsync<Segment>($"api/segments/{id}");
    public Task<List<MergedAnnotation>> Merged(long segmentId) =>
        GetAsync<List<MergedAnnotation>>($"api/segments/{segmentId}/annotations/merged");
    public Task<List<AnnotationVersion>> VersionsBySegment(long segmentId) =>
        GetAsync<List<AnnotationVersion>>($"api/segments/{segmentId}/versions");
    public Task<List<Arbitration>> ArbitrationsBySegment(long segmentId) =>
        GetAsync<List<Arbitration>>($"api/segments/{segmentId}/arbitrations");
    public Task<List<Tag>> Tags() => GetAsync<List<Tag>>("api/tags");
    public Task<List<TagNode>> TagTree() => GetAsync<List<TagNode>>("api/tags/tree");
    public Task<List<Batch>> Batches() => GetAsync<List<Batch>>("api/batches");
    public Task<List<AnnotationTask>> Tasks() => GetAsync<List<AnnotationTask>>("api/tasks");
    public Task<List<AssignmentView>> Assignments(long taskId) =>
        GetAsync<List<AssignmentView>>($"api/tasks/{taskId}/assignments");
    public Task<List<Segment>> TaskSegments(long taskId) =>
        GetAsync<List<Segment>>($"api/tasks/{taskId}/segments");
    public Task<List<AnnotationEvent>> Events(int limit = 100) =>
        GetAsync<List<AnnotationEvent>>($"api/events?limit={limit}");
}
