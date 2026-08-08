package com.atguigu.ai.all.config;

import com.atguigu.ai.all.func.RecruitServiceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.List;
import java.util.function.Function;

/**
 * RAG 知识库装配：文档加载 -> 分块 -> 向量化 -> 写入向量库。
 * <p>
 * 应用启动时一次性完成离线索引构建，运行期由 {@link VectorStore} 提供相似度检索。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /** 简历文档位置，支持 classpath: / file: 前缀 */
    @Value("${app.rag.resume-path:classpath:张三简历.txt}")
    private String resumePath;

    /** 目标分块大小（token 数） */
    @Value("${app.rag.chunk-size:1200}")
    private int chunkSize;

    /** 单块最少字符数，低于该值会继续与后文合并 */
    @Value("${app.rag.min-chunk-size-chars:350}")
    private int minChunkSizeChars;

    /** 分块长度低于该值则直接丢弃，避免无意义的碎片入库 */
    @Value("${app.rag.min-chunk-length-to-embed:5}")
    private int minChunkLengthToEmbed;

    /** 单个文档最多切分出的块数 */
    @Value("${app.rag.max-num-chunks:100}")
    private int maxNumChunks;

    /**
     * 构建内存向量库。
     * <p>
     * 注意：{@link TokenTextSplitter#apply(List)} 是无副作用的转换操作，
     * 必须接收其返回值，否则分块不会生效（历史 bug）。
     */
    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();

        // 1. 文档加载：读取纯文本简历
        TextReader textReader = new TextReader(resumePath);
        textReader.getCustomMetadata().put("filepath", resumePath);
        List<Document> documents = textReader.get();

        // 2. 文本分块：按 token 切分，保留段落分隔符
        TokenTextSplitter splitter = new TokenTextSplitter(
                chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, true);
        List<Document> chunks = splitter.apply(documents);

        // 3. 向量化写入：由 EmbeddingModel 生成向量后存入内存向量库
        simpleVectorStore.add(chunks);
        log.info("RAG 知识库构建完成：source={}, 原始文档={} 份, 切分后={} 块", resumePath, documents.size(), chunks.size());

        return simpleVectorStore;
    }

    /**
     * 注册为大模型可调用的工具（Function Calling）。
     * {@code @Description} 的内容会作为工具说明提交给模型，模型据此判断是否调用。
     */
    @Bean
    @Description("根据候选人姓名查询其投递的岗位，用于判断某人是否有资格面试")
    public Function<RecruitServiceFunction.Request, RecruitServiceFunction.Response> recruitServiceFunction() {
        return new RecruitServiceFunction();
    }
}
