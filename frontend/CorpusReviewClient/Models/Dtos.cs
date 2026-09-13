namespace CorpusReviewClient.Models;

// 与后端 JSON（camelCase）对应；System.Text.Json Web 默认大小写不敏感

public record AppUser(long Id, string Username, string DisplayName, string Role);
public record Corpus(long Id, string Name);

public record Segment(
    long Id, long CorpusId, string LanguageCode, string Content,
    int CharLength, string? ExternalId, string CreatedAt);

public record Tag(long Id, string Name, long? ParentId);
public record TagNode(long Id, string Name, List<TagNode> Children);

public record Batch(long Id, string Name);

public record AnnotationTask(
    long Id, long BatchId, string Name, string Status, long? CreatedBy, string CreatedAt);

public record Assignment(long Id, long TaskId, long UserId, long BatchId, string AssignedAt, string Status);
public record AssignmentView(long Id, long TaskId, long UserId, string Username, long BatchId, string AssignedAt, string Status);

public record AnnotationVersion(long Id, long AssignmentId, int VersionNo, string Status, string CreatedAt);

public record Annotation(long Id, long VersionId, long SegmentId, long TagId,
    int StartOffset, int EndOffset, string? Note);

public record AnnotationSource(long AnnotationId, long VersionId, string Annotator, string? Note);

/// <summary>规则1：合并后的标注展示单元</summary>
public record MergedAnnotation(long TagId, string TagName, int StartOffset, int EndOffset,
    int Count, List<AnnotationSource> Sources);

public record ConclusionAnnotation(long Id, long TagId, string TagName,
    int StartOffset, int EndOffset, string? Note);

public record Arbitration(long Id, long SegmentId, long ArbitratorId, string ArbitratorName,
    string? Comment, string CreatedAt, List<long> SourceVersionIds, List<ConclusionAnnotation> Conclusions);

public record AnnotationEvent(string Time, string EventId, string EventType,
    long? SegmentId, long? VersionId, long? ActorId, string Payload);

public record ImportRowError(int Row, string Message);
public record ImportResult(int Imported, int Skipped, List<ImportRowError> Errors);

public record ApiErrorBody(string Error);

// ---- 请求体 ----

public record CreateTagRequest(string Name, long? ParentId);
public record ReparentRequest(long? ParentId);
public record CreateBatchRequest(string Name);
public record CreateTaskRequest(long BatchId, string Name, long? CreatedBy, List<long> SegmentIds);
public record AssignRequest(long UserId);
public record AutoAssignRequest(int Count);
public record AnnotationInput(long SegmentId, long TagId, int StartOffset, int EndOffset, string? Note);
public record SubmitVersionRequest(long AssignmentId, List<AnnotationInput> Annotations);
public record ConclusionInput(long TagId, int StartOffset, int EndOffset, string? Note);
public record CreateArbitrationRequest(long SegmentId, long ArbitratorId, List<long> SourceVersionIds,
    string? Comment, List<ConclusionInput> Conclusions);
public record CreateUserRequest(string Username, string DisplayName, string Role);
