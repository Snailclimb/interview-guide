package interview.guide.modules.knowledgebase;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus) {
        
        VectorStatus status = null;
        if (vectorStatus != null && !vectorStatus.isBlank()) {
            try {
                status = VectorStatus.valueOf(vectorStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的向量化状态: " + vectorStatus);
            }
        }
        
        return Result.success(listService.listKnowledgeBases(status, sortBy));
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return listService.getKnowledgeBase(id)
                .map(Result::success)
                .orElse(Result.error("知识库不存在"));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success(null);
    }

    /**
     * 基于知识库回答问题（支持多知识库）
     */
    @PostMapping("/api/knowledgebase/query")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    /**
     * 基于知识库回答问题（流式SSE，支持多知识库）
     */
    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        log.debug("收到知识库流式查询请求: kbIds={}, question={}, 线程: {} (虚拟线程: {})",
            request.knowledgeBaseIds(), request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());
        return queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question());
    }

    // ========== 分类管理 API ==========

    /**
     * 获取所有分类
     */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    /**
     * 根据分类获取知识库列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(listService.listByCategory(category));
    }

    /**
     * 获取未分类的知识库
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(listService.listByCategory(null));
    }

    /**
     * 更新知识库分类
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        listService.updateCategory(id, body.get("category"));
        return Result.success(null);
    }

    // ========== 上传下载 API ==========

    /**
     * 上传知识库文件
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(uploadService.uploadKnowledgeBase(file, name, category));
    }

    /**
     * 下载知识库文件
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        var entity = listService.getEntityForDownload(id);
        byte[] fileContent = listService.downloadFile(id);

        String filename = entity.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .header(HttpHeaders.CONTENT_TYPE,
                        entity.getContentType() != null ? entity.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(fileContent);
    }

    // ========== 搜索 API ==========

    /**
     * 搜索知识库
     */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(listService.search(keyword));
    }

    // ========== 统计 API ==========

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    // ========== 向量化管理 API ==========

    /**
     * 重新向量化知识库（手动重试）
     * 用于向量化失败后的重试
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> revectorize(@PathVariable Long id) {
        uploadService.revectorize(id);
        return Result.success(null);
    }

}
