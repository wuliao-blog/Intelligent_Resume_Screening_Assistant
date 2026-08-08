package com.atguigu.ai.all.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能简历筛选助手对外接口。
 * <p>
 * 单次请求完成：向量检索（Retrieval）-> 提示词增强（Augmentation）-> 大模型生成（Generation），
 * 生成阶段同时开放 recruitServiceFunction 工具供模型按需调用。
 */
@RestController
public class ChatController {

    private static final String SYSTEM_PROMPT = """
            角色与目标：你是一个招聘助手，会针对用户的问题，结合候选人经历、岗位匹配度等专业知识，为用户提供指导。
            指导原则：你需要确保给出的建议合理科学，不会对候选人的表现有言论侮辱。
            限制：在提供建议时，需要强调在个性化建议方面用户仍然需要线下寻求专业咨询。
            澄清：在与用户交互过程中，你需要明确回答用户关于招聘方面的问题；对于非招聘方面的问题，你的回应是'我只是一个招聘助手，不能回答这个问题哦'。
            个性化：在回答时，你需要以专业可靠的语气回答，偶尔可以带点幽默感，调节气氛。
            工具与资料：系统会给你提供简历检索结果作为数据参考，并为你开放岗位投递查询工具。
            请你根据数据参考与工具返回结果回复用户的请求。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            给你提供一些数据参考：
            {info}

            请回答我的问题：{query}
            请你根据上述数据参考与工具返回结果回复用户的请求；若资料中没有相关信息，请如实说明。
            """;

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    /** 检索返回的片段条数 */
    @Value("${app.rag.top-k:3}")
    private int topK;

    public ChatController(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    @GetMapping("/ai/agent")
    public String agent(@RequestParam("query") String query) {
        // 1. 检索：在向量库中做相似度搜索，取回 topK 个最相关片段
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());

        // 2. 增强：把检索到的片段拼接为上下文，注入提示词
        String info = (documents == null || documents.isEmpty())
                ? "（未检索到相关简历资料）"
                : documents.stream()
                        .map(Document::getContent)
                        .collect(Collectors.joining("\n---\n"));

        SystemMessage systemMessage = new SystemMessage(SYSTEM_PROMPT);
        Message userMessage = new PromptTemplate(USER_PROMPT_TEMPLATE)
                .createMessage(Map.of("info", info, "query", query));

        // 3. 生成：附带可调用工具，交由大模型输出最终答复
        Prompt prompt = new Prompt(
                List.of(systemMessage, userMessage),
                DashScopeChatOptions.builder()
                        .withFunctions(Set.of("recruitServiceFunction"))
                        .build());

        List<Generation> results = chatModel.call(prompt).getResults();
        return results.stream()
                .map(x -> x.getOutput().getContent())
                .collect(Collectors.joining());
    }
}
