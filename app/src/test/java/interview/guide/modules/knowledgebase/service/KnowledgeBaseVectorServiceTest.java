package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * KnowledgeBaseVectorService 单元测试
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>向量化存储（vectorizeAndStore）- 分批处理逻辑、metadata 设置、删除旧数据</li>
 *   <li>相似度搜索（similaritySearch）- 基本搜索、知识库ID过滤、topK限制</li>
 *   <li>删除向量数据（deleteByKnowledgeBaseId）</li>
 * </ul>
 *
 * <p>注意：TextSplitter 未被 Mock，测试依赖 TokenTextSplitter 的真实行为。
 * 这是有意为之，因为分词逻辑是向量化的核心部分，应该进行集成测试。
 * 如需完全隔离，可将 TextSplitter 改为构造函数注入。
 */
@DisplayName("知识库向量服务测试")
@SuppressWarnings("unchecked") // Mockito ArgumentCaptor 泛型警告
class KnowledgeBaseVectorServiceTest {

    private KnowledgeBaseVectorService vectorService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorRepository vectorRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vectorService = new KnowledgeBaseVectorService(vectorStore, vectorRepository);
    }

    // ==================== 共享辅助方法 ====================

    /**
     * 生成足够长的内容，确保 TokenTextSplitter 产生 chunks
     * TokenTextSplitter 默认配置下，需要较长的文本才会分块
     */
    private String generateLongContent(int paragraphs) {
        StringBuilder contentBuilder = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            contentBuilder.append("这是第 ").append(i).append(" 段内容。")
                .append("Spring Boot 是一个优秀的 Java 框架，它简化了 Spring 应用的开发。")
                .append("通过自动配置和起步依赖，开发者可以快速构建生产级别的应用。")
                .append("Spring AI 提供了与各种 AI 模型交互的能力，包括 embedding 和 chat 功能。")
                .append("PostgreSQL 是一个强大的开源关系数据库，支持向量存储和相似度搜索。")
                .append("通过 pgvector 扩展，可以实现高效的向量索引和检索功能。")
                .append("知识库系统可以将文档内容向量化，然后进行语义搜索，提高检索的准确性。")
                .append("\n\n");
        }
        return contentBuilder.toString();
    }

    /**
     * 创建模拟文档列表
     * @param count 文档数量
     * @param kbId 知识库ID（String 类型），null 表示不设置
     */
    private List<Document> createMockDocuments(int count, String kbId) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> metadata = new HashMap<>();
            if (kbId != null) {
                metadata.put("kb_id", kbId);
            }
            documents.add(new Document("文档内容 " + i, metadata));
        }
        return documents;
    }

    /**
     * 创建模拟文档列表（无 kb_id）
     */
    private List<Document> createMockDocuments(int count) {
        return createMockDocuments(count, null);
    }

    /**
     * 创建使用 Long 类型 kb_id 的文档（模拟旧数据格式）
     */
    private Document createDocumentWithLongKbId(Long kbId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", kbId); // Long 类型
        return new Document("Long kb_id 文档", metadata);
    }

    /**
     * 创建包含无效 kb_id 的文档
     */
    private Document createDocumentWithInvalidKbId(String invalidKbId) {
        Map<String, Object> metadata = new HashMap<>();
        if (invalidKbId != null) {
            metadata.put("kb_id", invalidKbId);
        }
        return new Document("无效 kb_id 文档", metadata);
    }

    /**
     * 创建一个支持过滤的 Mock Answer
     * 简化版本：仅用于测试，手动过滤结果
     * @param allDocuments 所有文档
     * @param allowedKbIds 允许的 kb_id 列表（如果为 null，则返回所有文档）
     */
    private static List<Document> filterDocuments(List<Document> allDocuments, List<Long> allowedKbIds) {
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return allDocuments;
        }

        return allDocuments.stream()
            .filter(doc -> {
                Object kbId = doc.getMetadata().get("kb_id");
                if (kbId == null) {
                    return false;
                }
                String kbIdStr = kbId.toString();
                // 处理 Long 类型 kb_id
                try {
                    Long kbIdLong = Long.parseLong(kbIdStr);
                    return allowedKbIds.contains(kbIdLong);
                } catch (NumberFormatException e) {
                    // String 类型 kb_id，尝试匹配
                    return allowedKbIds.stream().anyMatch(id -> id.toString().equals(kbIdStr));
                }
            })
            .collect(java.util.stream.Collectors.toList());
    }

    // ==================== 测试类 ====================

    @Nested
    @DisplayName("向量化存储测试")
    class VectorizeAndStoreTests {

        @Test
        @DisplayName("文本向量化存储 - 验证基本流程")
        void testVectorizeSmallContent() {
            // Given: 生成足够长的文本以确保产生 chunks
            Long knowledgeBaseId = 1L;
            String content = generateLongContent(5);

            // When: 执行向量化
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 验证先删除旧数据
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);

            // 验证 VectorStore.add 被调用（文本足够长时应产生 chunks）
            verify(vectorStore, atLeastOnce()).add(anyList());
        }

        @Test
        @DisplayName("大文本分批处理 - 验证每批不超过限制")
        void testVectorizeLargeContentInBatches() {
            // Given: 生成非常长的文本，确保产生多个 chunks
            Long knowledgeBaseId = 2L;
            // 生成 200 段内容，确保产生足够多的 chunks
            String content = generateLongContent(200);

            // 记录 add 调用
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

            // When: 执行向量化
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 捕获所有 add 调用
            verify(vectorStore, atLeastOnce()).add(captor.capture());

            // 验证每批不超过 10 个（MAX_BATCH_SIZE）
            List<List<Document>> allBatches = captor.getAllValues();
            for (List<Document> batch : allBatches) {
                assertTrue(batch.size() <= 10,
                    "每批次不应超过 10 个文档，实际: " + batch.size());
            }
        }

        @Test
        @DisplayName("验证 metadata 正确设置 kb_id")
        void testMetadataContainsKnowledgeBaseId() {
            // Given: 使用足够长的内容确保产生 chunks
            Long knowledgeBaseId = 123L;
            String content = generateLongContent(10);

            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

            // When
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 捕获添加的文档，验证 metadata
            verify(vectorStore, atLeastOnce()).add(captor.capture());

            List<List<Document>> allBatches = captor.getAllValues();
            assertFalse(allBatches.isEmpty(), "应该有文档被添加");

            for (List<Document> batch : allBatches) {
                for (Document doc : batch) {
                    assertEquals(knowledgeBaseId.toString(), doc.getMetadata().get("kb_id"),
                        "metadata 中的 kb_id 应该等于知识库ID的字符串形式");
                }
            }
        }

        @Test
        @DisplayName("向量化前应先删除旧数据")
        void testDeleteOldDataBeforeVectorize() {
            // Given: 使用足够长的内容确保产生 chunks
            Long knowledgeBaseId = 1L;
            String content = generateLongContent(10);

            // When
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 验证 delete 在 add 之前执行（通过 inOrder 严格顺序验证）
            var inOrder = inOrder(vectorRepository, vectorStore);
            inOrder.verify(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);
            inOrder.verify(vectorStore, atLeastOnce()).add(anyList());
        }

        @Test
        @DisplayName("向量化失败时抛出异常")
        void testVectorizeFailureThrowsException() {
            // Given: 使用足够长的内容确保产生 chunks
            Long knowledgeBaseId = 1L;
            String content = generateLongContent(10);

            doThrow(new RuntimeException("VectorStore 连接失败"))
                .when(vectorStore).add(anyList());

            // When & Then
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> vectorService.vectorizeAndStore(knowledgeBaseId, content)
            );

            assertTrue(exception.getMessage().contains("向量化知识库失败"));
        }

        @Test
        @DisplayName("空内容处理 - 应该删除旧数据但不添加新数据")
        void testVectorizeEmptyContent() {
            // Given
            Long knowledgeBaseId = 1L;
            String content = "";

            // When
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 即使是空内容，也应该删除旧数据
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);
            // 空内容不会产生 chunks，所以 add 不会被调用
            verify(vectorStore, never()).add(anyList());
        }
    }

    @Nested
    @DisplayName("相似度搜索测试")
    class SimilaritySearchTests {

        @Test
        @DisplayName("基本搜索 - 无过滤条件")
        void testBasicSearchWithoutFilter() {
            // Given
            String query = "Java 开发经验";
            int topK = 5;

            List<Document> mockResults = createMockDocuments(10, null);
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch(query, null, topK, 0.0);

            // Then
            assertEquals(topK, results.size(), "应该返回 topK 个结果");
            verify(vectorStore, times(1)).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
        }

        @Test
        @DisplayName("搜索结果按知识库ID过滤 - String类型kb_id")
        void testSearchWithKnowledgeBaseIdFilterString() {
            // Given
            String query = "Spring Boot";
            List<Long> knowledgeBaseIds = List.of(1L, 2L);
            int topK = 10;

            // 创建混合的搜索结果（包含不同 kb_id）
            List<Document> allMockResults = new ArrayList<>();
            allMockResults.addAll(createMockDocuments(3, "1"));  // kb_id = "1"
            allMockResults.addAll(createMockDocuments(3, "2"));  // kb_id = "2"
            allMockResults.addAll(createMockDocuments(4, "3"));  // kb_id = "3" (应被过滤)

            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenAnswer(invocation -> {
                    // 模拟：根据 knowledge base IDs 过滤结果
                    return filterDocuments(allMockResults, knowledgeBaseIds);
                });

            // When
            List<Document> results = vectorService.similaritySearch(query, knowledgeBaseIds, topK, 0.0);

            // Then: 只返回 kb_id 为 1 或 2 的文档
            assertEquals(6, results.size(), "应该只返回匹配知识库ID的文档");

            for (Document doc : results) {
                String kbId = (String) doc.getMetadata().get("kb_id");
                assertTrue(kbId.equals("1") || kbId.equals("2"),
                    "结果应该只包含指定知识库的文档");
            }
        }

        @Test
        @DisplayName("搜索结果按知识库ID过滤 - Long类型kb_id（向后兼容）")
        void testSearchWithKnowledgeBaseIdFilterLong() {
            // Given
            String query = "Python 开发";
            List<Long> knowledgeBaseIds = List.of(100L);
            int topK = 5;

            // 创建使用 Long 类型 kb_id 的文档（模拟旧数据）
            List<Document> allMockResults = new ArrayList<>();
            allMockResults.add(createDocumentWithLongKbId(100L));
            allMockResults.add(createDocumentWithLongKbId(100L));
            allMockResults.add(createDocumentWithLongKbId(200L)); // 应被过滤

            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenAnswer(invocation -> filterDocuments(allMockResults, knowledgeBaseIds));

            // When
            List<Document> results = vectorService.similaritySearch(query, knowledgeBaseIds, topK, 0.0);

            // Then
            assertEquals(2, results.size(), "应该只返回 kb_id=100 的文档");
        }

        @Test
        @DisplayName("topK 限制生效")
        void testTopKLimit() {
            // Given
            String query = "测试查询";
            int topK = 3;

            List<Document> mockResults = createMockDocuments(10, "1");
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch(query, List.of(1L), topK, 0.0);

            // Then
            assertEquals(topK, results.size(), "结果数量应该被 topK 限制");
        }

        @Test
        @DisplayName("搜索失败时抛出异常")
        void testSearchFailureThrowsException() {
            // Given
            String query = "测试";
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new RuntimeException("搜索服务不可用"));

            // When & Then
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> vectorService.similaritySearch(query, null, 5, 0.0)
            );

            assertTrue(exception.getMessage().contains("向量搜索失败"));
        }

        @Test
        @DisplayName("空知识库ID列表 - 不进行过滤")
        void testSearchWithEmptyKnowledgeBaseIdList() {
            // Given
            String query = "查询";
            List<Long> emptyList = List.of();
            int topK = 5;

            List<Document> mockResults = createMockDocuments(10, "1");
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch(query, emptyList, topK, 0.0);

            // Then: 空列表应该返回所有结果（受 topK 限制）
            assertEquals(topK, results.size());
        }

        @Test
        @DisplayName("搜索结果为空")
        void testSearchReturnsEmpty() {
            // Given
            String query = "不存在的内容";
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());

            // When
            List<Document> results = vectorService.similaritySearch(query, null, 10, 0.0);

            // Then
            assertTrue(results.isEmpty(), "搜索结果应该为空");
        }

        @Test
        @DisplayName("过滤后结果为空")
        void testFilteredResultsEmpty() {
            // Given
            String query = "测试";
            List<Long> knowledgeBaseIds = List.of(999L); // 不存在的 kb_id

            List<Document> allMockResults = createMockDocuments(5, "1");
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenAnswer(invocation -> filterDocuments(allMockResults, knowledgeBaseIds));

            // When
            List<Document> results = vectorService.similaritySearch(query, knowledgeBaseIds, 10, 0.0);

            // Then
            assertTrue(results.isEmpty(), "没有匹配的知识库ID，结果应为空");
        }

        @Test
        @DisplayName("处理无效的 kb_id 格式")
        void testHandleInvalidKbIdFormat() {
            // Given
            String query = "测试";
            List<Long> knowledgeBaseIds = List.of(1L);

            // 创建包含无效 kb_id 的文档
            List<Document> allMockResults = new ArrayList<>();
            allMockResults.add(createDocumentWithInvalidKbId("not_a_number"));
            allMockResults.add(createDocumentWithInvalidKbId(null));
            allMockResults.addAll(createMockDocuments(2, "1")); // 有效的文档

            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenAnswer(invocation -> filterDocuments(allMockResults, knowledgeBaseIds));

            // When
            List<Document> results = vectorService.similaritySearch(query, knowledgeBaseIds, 10, 0.0);

            // Then: 无效的 kb_id 应该被过滤掉，只返回有效的
            assertEquals(2, results.size(), "只应返回有效 kb_id 的文档");
        }
    }

    @Nested
    @DisplayName("删除向量数据测试")
    class DeleteVectorDataTests {

        @Test
        @DisplayName("成功删除向量数据")
        void testDeleteByKnowledgeBaseId() {
            // Given
            Long knowledgeBaseId = 1L;
            when(vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)).thenReturn(5);

            // When
            vectorService.deleteByKnowledgeBaseId(knowledgeBaseId);

            // Then
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);
        }

        @Test
        @DisplayName("删除失败不抛出异常（静默处理）")
        void testDeleteFailureSilentlyHandled() {
            // Given
            Long knowledgeBaseId = 1L;
            doThrow(new RuntimeException("数据库错误"))
                .when(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);

            // When & Then: 不应该抛出异常
            assertDoesNotThrow(() -> vectorService.deleteByKnowledgeBaseId(knowledgeBaseId));
        }

        @Test
        @DisplayName("删除不存在的知识库数据")
        void testDeleteNonExistentKnowledgeBase() {
            // Given
            Long knowledgeBaseId = 999L;
            when(vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)).thenReturn(0);

            // When
            vectorService.deleteByKnowledgeBaseId(knowledgeBaseId);

            // Then: 应该正常执行，不抛出异常
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("知识库ID为null时 - 应抛出异常并包含有意义的错误信息")
        void testNullKnowledgeBaseId() {
            // Given
            String content = generateLongContent(5);

            // When & Then: null knowledgeBaseId 应该导致 RuntimeException
            // 因为 content.length() 调用在 knowledgeBaseId.toString() 之前，
            // 实际会在设置 metadata 时抛出 NullPointerException，被包装为 RuntimeException
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> vectorService.vectorizeAndStore(null, content)
            );

            assertTrue(exception.getMessage().contains("向量化知识库失败"),
                "异常消息应包含'向量化知识库失败'");
        }

        @Test
        @DisplayName("内容为null时 - 应抛出 NullPointerException")
        void testNullContent() {
            // Given
            Long knowledgeBaseId = 1L;

            // When & Then: null content 在调用 content.length() 时会抛出 NPE
            // 注意：NPE 发生在 try 块之外，不会被包装
            assertThrows(
                NullPointerException.class,
                () -> vectorService.vectorizeAndStore(knowledgeBaseId, null)
            );
        }

        @Test
        @DisplayName("查询字符串为空")
        void testEmptyQuery() {
            // Given
            String emptyQuery = "";
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());

            // When
            List<Document> results = vectorService.similaritySearch(emptyQuery, null, 5, 0.0);

            // Then
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("topK 为 0")
        void testTopKZero() {
            // Given
            String query = "测试";
            List<Document> mockResults = createMockDocuments(5);
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch(query, null, 0, 0.0);

            // Then
            assertTrue(results.isEmpty(), "topK=0 应该返回空结果");
        }

        @Test
        @DisplayName("topK 大于实际结果数")
        void testTopKGreaterThanResults() {
            // Given
            String query = "测试";
            int topK = 100;
            List<Document> mockResults = createMockDocuments(5);
            when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch(query, null, topK, 0.0);

            // Then
            assertEquals(5, results.size(), "应该返回所有可用结果");
        }
    }
}
