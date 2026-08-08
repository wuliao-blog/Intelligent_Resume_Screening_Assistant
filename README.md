# SpringAI_IRSA · 智能简历筛选助手

> Intelligent Resume Screening Assistant — 基于 **Spring AI + 阿里云百炼（DashScope）** 构建的招聘场景智能问答助手，
> 融合 **RAG 检索增强生成** 与 **Function Calling 工具调用** 两大能力。

面向 HR / 招聘场景：把候选人简历导入本地向量知识库，用户用自然语言提问（例如"张三适合算法岗吗？"），
系统先从简历中检索出相关片段，再交由大模型结合业务工具给出专业、有依据的筛选建议——
既避免了大模型"凭空编造"，也让回答始终基于真实简历内容。

---

## 目录

- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [RAG 技术详解](#rag-技术详解)
- [Function Calling 工具调用](#function-calling-工具调用)
- [快速开始](#快速开始)
- [API 说明](#api-说明)
- [项目结构](#项目结构)
- [配置项说明](#配置项说明)
- [常见问题](#常见问题)
- [安全须知](#安全须知)

---

## 核心特性

| 特性 | 说明 |
| --- | --- |
| 📄 简历知识库 | 启动时自动加载简历文本，切分并向量化写入向量库 |
| 🔍 语义检索 | 基于向量相似度检索，理解语义而非关键词匹配 |
| 🧠 检索增强生成 | 把检索片段注入提示词，让模型基于真实资料作答 |
| 🛠️ 工具调用 | 模型可自主调用岗位查询函数，补充知识库之外的实时信息 |
| 🎭 角色化提示词 | 通过系统提示词约束角色边界，拒答非招聘类问题 |
| ⚙️ 参数化配置 | 分块大小、检索条数、模型名称等均可通过配置文件调整 |

---

## 技术栈

| 类别 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Java | 17 |
| 应用框架 | Spring Boot | 3.3.8 |
| AI 框架 | Spring AI | 1.0.0-M5 |
| 模型接入 | Spring AI Alibaba Starter（DashScope） | 1.0.0-M5.1 |
| 对话模型 | 通义千问 `qwen-max` | — |
| 向量模型 | `text-embedding-v2` | — |
| 向量存储 | `SimpleVectorStore`（内存） | — |
| 构建工具 | Maven | 3.6+ |

---

## 系统架构

```
                        ┌──────────────── 离线索引阶段（应用启动时一次性执行）────────────────┐
                        │                                                                    │
  张三简历.txt  ──▶  TextReader  ──▶  TokenTextSplitter  ──▶  EmbeddingModel  ──▶  VectorStore
                     文档加载          文本分块              向量化             向量库(内存)
                        │                                                                    │
                        └────────────────────────────────────────────────────────────────────┘

                        ┌──────────────── 在线问答阶段（每次请求执行）────────────────────────┐
                        │                                                                    │
  用户提问 ──▶ 向量化查询 ──▶ 相似度检索 top-K ──▶ 拼接上下文 ──▶ 构造 Prompt ──▶ ChatModel ──▶ 回答
   query        Embedding       VectorStore         Augment      System+User      qwen-max
                                                                       │              │
                                                                       │              ▼
                                                                       │      需要额外信息？
                                                                       │              │
                                                                       └──── Function Calling
                                                                          recruitServiceFunction
                        └────────────────────────────────────────────────────────────────────┘
```

---

## RAG 技术详解

**RAG（Retrieval-Augmented Generation，检索增强生成）** 解决的核心问题是：
大模型的知识来自训练语料，既不包含企业私有数据（比如这份简历），也可能过时或产生幻觉。
RAG 的思路是——**先检索，后生成**：在模型回答前，先从私有知识库中捞出相关资料塞进提示词，
让模型"开卷考试"，从而使回答有据可依。

本项目完整实现了 RAG 的五个环节，代码集中在 `RagConfig`（离线索引）与 `ChatController`（在线问答）。

### 1️⃣ 文档加载（Load）

使用 Spring AI 的 `TextReader` 读取纯文本简历，并写入自定义元数据便于溯源：

```java
TextReader textReader = new TextReader(resumePath);   // 支持 classpath: / file: 前缀
textReader.getCustomMetadata().put("filepath", resumePath);
List<Document> documents = textReader.get();
```

`Document` 是 Spring AI 的统一文档抽象，包含正文 `content` 与元数据 `metadata`。
元数据会随分块一起保留，检索命中后可回溯到原始文件来源。

> 💡 **扩展**：Spring AI 还提供 `PagePdfDocumentReader`、`TikaDocumentReader`（支持 Word/PPT/HTML）等，
> 替换 Reader 即可支持 PDF 简历等真实场景格式。

### 2️⃣ 文本分块（Split）

为什么必须切分？三个原因：

- **上下文窗口有限**：整份文档塞进提示词既超限又昂贵；
- **检索精度**：块越小语义越聚焦，检索命中的内容噪声更少；
- **向量表达能力**：一个向量难以准确表达长文本的多个主题。

本项目使用 `TokenTextSplitter`，按 **token** 而非字符切分（与模型计费口径一致）：

```java
TokenTextSplitter splitter = new TokenTextSplitter(
        chunkSize,               // 1200 目标块大小（token）
        minChunkSizeChars,       // 350  单块最少字符数，不足则与后文合并
        minChunkLengthToEmbed,   // 5    过短的块直接丢弃，避免碎片入库
        maxNumChunks,            // 100  单文档最大块数
        true);                   // 保留段落分隔符
List<Document> chunks = splitter.apply(documents);
```

> ⚠️ **易踩的坑**：`splitter.apply(documents)` 是**无副作用的转换操作**，
> 它返回新的分块列表，**不会修改入参**。若写成 `splitter.apply(documents);` 而丢弃返回值，
> 后续 `add(documents)` 存入的仍是**未切分的整篇文档**，分块参数全部形同虚设。
> 本项目已修复该问题，务必接收返回值再入库。

### 3️⃣ 向量化与存储（Embed & Store）

`SimpleVectorStore` 在写入时自动调用 `EmbeddingModel`（`text-embedding-v2`）
把每个文本块转换为高维浮点向量，语义相近的文本在向量空间中距离更近：

```java
SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
store.add(chunks);   // 内部完成 embedding 调用 + 向量入库
```

`SimpleVectorStore` 是**内存实现**，数据随应用重启丢失，适合演示与小规模知识库。
生产环境可无缝替换为持久化向量数据库（接口不变）：

| 向量库 | 适用场景 |
| --- | --- |
| `SimpleVectorStore` | 本项目采用，零依赖、开箱即用，重启丢失 |
| Redis Stack / Milvus | 中大规模、需持久化与高并发检索 |
| PgVector | 已有 PostgreSQL 技术栈，运维成本低 |
| Elasticsearch | 需要向量 + 关键词混合检索 |

> 🔑 **关键约束**：写入与查询必须使用**同一个向量模型**。
> 更换 embedding 模型后，历史向量全部失效，必须重建索引。

### 4️⃣ 检索（Retrieve）

用户提问时，先把 query 向量化，再在向量库中做相似度搜索，取回最相关的 top-K 个片段：

```java
List<Document> documents = vectorStore.similaritySearch(
        SearchRequest.builder().query(query).topK(topK).build());
```

`topK` 是精度与成本的权衡：太小可能漏掉关键信息，太大则引入噪声并推高 token 消耗。
本项目默认 `top-k=3`，可通过 `app.rag.top-k` 调整。

> 💡 **进阶方向**：`SearchRequest` 还支持 `similarityThreshold`（相似度阈值过滤）
> 与 `filterExpression`（基于元数据的前置过滤，例如只在某候选人的简历中检索）。

### 5️⃣ 增强与生成（Augment & Generate）

把检索到的多个片段拼接为上下文，通过 `PromptTemplate` 注入用户提示词，
再与角色化系统提示词组装成完整 `Prompt` 交给大模型：

```java
String info = documents.isEmpty()
        ? "（未检索到相关简历资料）"
        : documents.stream().map(Document::getContent)
                   .collect(Collectors.joining("\n---\n"));

SystemMessage systemMessage = new SystemMessage(SYSTEM_PROMPT);
Message userMessage = new PromptTemplate(USER_PROMPT_TEMPLATE)
        .createMessage(Map.of("info", info, "query", query));

Prompt prompt = new Prompt(List.of(systemMessage, userMessage),
        DashScopeChatOptions.builder()
                .withFunctions(Set.of("recruitServiceFunction")).build());
```

系统提示词承担四项约束：**角色设定**（招聘助手）、**行为准则**（不侮辱候选人）、
**边界限制**（非招聘问题拒答）、**输出风格**（专业可靠、适度幽默）。
用户提示词则明确要求"资料中没有的信息如实说明"，进一步抑制幻觉。

---

## Function Calling 工具调用

RAG 解决的是"静态知识"问题，而 **Function Calling** 让模型能调用外部函数获取动态数据。
Spring AI 中只需把函数注册为 Bean，并用 `@Description` 描述用途——
这段描述会作为工具说明提交给模型，由模型自主判断何时调用：

```java
@Bean
@Description("根据候选人姓名查询其投递的岗位，用于判断某人是否有资格面试")
public Function<RecruitServiceFunction.Request, RecruitServiceFunction.Response> recruitServiceFunction() {
    return new RecruitServiceFunction();
}
```

```java
public class RecruitServiceFunction
        implements Function<RecruitServiceFunction.Request, RecruitServiceFunction.Response> {
    @Override
    public Response apply(Request request) {
        String position = request.name().contains("张三") ? "算法工程师" : "未知";
        return new Response(position);
    }
    public record Request(String name) { }
    public record Response(String position) { }
}
```

调用链路：模型判断需要工具 → 返回函数名与参数 → Spring AI 自动执行对应 Bean →
把结果回填给模型 → 模型生成最终答复。整个过程对开发者透明，无需手写解析逻辑。

> 当前实现为演示用的硬编码桩函数，实际项目中可替换为查询 ATS 招聘系统、
> 人才库数据库或 HR 内部 API。

---

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- 阿里云百炼（DashScope）API Key —— [前往申请](https://bailian.console.aliyun.com/)

### 1. 克隆项目

```bash
git clone https://github.com/wuliao-blog/Intelligent_Resume_Screening_Assistant.git
cd Intelligent_Resume_Screening_Assistant/SpringAI_IRSA
```

### 2. 配置 API Key（通过环境变量注入，切勿写入代码库）

**Windows PowerShell**
```powershell
$env:DASHSCOPE_API_KEY="sk-你的真实密钥"
```

**Linux / macOS**
```bash
export DASHSCOPE_API_KEY=sk-你的真实密钥
```

### 3. 启动

```bash
mvn spring-boot:run
```

启动日志中会输出知识库构建结果，例如：
```
RAG 知识库构建完成：source=classpath:张三简历.txt, 原始文档=1 份, 切分后=2 块
```

### 4. 提问验证

```bash
curl "http://localhost:8899/ai/agent?query=张三适合算法工程师岗位吗"
```

---

## API 说明

### `GET /ai/agent`

招聘问答接口，单次请求完成检索、增强与生成。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `query` | String | 是 | 用户的自然语言提问 |

**请求示例**

```bash
curl "http://localhost:8899/ai/agent?query=张三有几年算法经验"
```

**响应**：`text/plain`，模型生成的中文答复。

**可尝试的提问**

- `张三的教育背景是什么？`
- `张三适合算法工程师岗位吗？`
- `张三在推荐系统方面有什么经验？`
- `张三投递的是什么岗位？`（触发 Function Calling）
- `今天天气怎么样？`（触发角色边界拒答）

---

## 项目结构

```
Intelligent_Resume_Screening_Assistant/
├── README.md
├── .gitignore
└── SpringAI_IRSA/                        # 主模块（原 springai_all）
    ├── pom.xml                           # 独立继承 spring-boot-starter-parent
    └── src/main/
        ├── java/com/atguigu/ai/all/
        │   ├── SpringAiAllApplication.java   # 启动类
        │   ├── config/RagConfig.java         # RAG 知识库装配 + 工具注册
        │   ├── controller/ChatController.java# 检索增强问答接口
        │   └── func/RecruitServiceFunction.java # 岗位查询工具函数
        └── resources/
            ├── application.properties        # 模型与 RAG 参数配置
            └── 张三简历.txt                  # 示例简历知识库
```

---

## 配置项说明

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8899` | 服务端口 |
| `spring.ai.dashscope.api-key` | `${DASHSCOPE_API_KEY:}` | 百炼 API Key，环境变量注入 |
| `spring.ai.dashscope.chat.options.model` | `qwen-max` | 对话生成模型 |
| `spring.ai.dashscope.embedding.options.model` | `text-embedding-v2` | 向量模型，需与索引保持一致 |
| `app.rag.resume-path` | `classpath:张三简历.txt` | 简历文档位置 |
| `app.rag.chunk-size` | `1200` | 分块目标大小（token） |
| `app.rag.min-chunk-size-chars` | `350` | 单块最少字符数 |
| `app.rag.min-chunk-length-to-embed` | `5` | 低于该长度的块丢弃 |
| `app.rag.max-num-chunks` | `100` | 单文档最大分块数 |
| `app.rag.top-k` | `3` | 检索返回片段条数 |

---

## 常见问题

**Q：启动报 401 / API Key 无效？**
A：确认环境变量 `DASHSCOPE_API_KEY` 已正确设置，且在**设置后新开的终端**中启动应用（旧终端不会继承新变量）。

**Q：想换成自己的简历？**
A：把文本文件放进 `src/main/resources/`，修改 `app.rag.resume-path` 即可；
若为 PDF，需引入 `spring-ai-pdf-document-reader` 并把 `TextReader` 替换为 `PagePdfDocumentReader`。

**Q：回答里出现简历中没有的内容？**
A：可调大 `top-k` 提高召回，或在 `SearchRequest` 中设置 `similarityThreshold` 过滤低相关片段，
同时强化系统提示词中"资料外信息不得编造"的约束。

**Q：重启后知识库需要重新构建？**
A：是。`SimpleVectorStore` 为内存实现，重启即丢失。需要持久化请替换为 Redis / PgVector / Milvus。

**Q：`mvn` 构建报找不到父 POM？**
A：本项目已改为独立继承 `spring-boot-starter-parent`，不再依赖外部多模块工程，直接构建即可。
若仍报错，请确认 Maven 能访问 `https://repo.spring.io/milestone`（Spring AI 里程碑版本仓库）。

---

## 安全须知

- ❌ **切勿**把真实 API Key 提交到仓库；本项目配置文件中已改为 `${DASHSCOPE_API_KEY:}` 环境变量注入。
- 🔄 若密钥曾意外提交，仅删除文件不够——**必须去控制台轮换/作废该密钥**，并考虑用
  `git filter-repo` 重写历史后强推。
- 📋 示例简历 `张三简历.txt` 为虚构数据；接入真实候选人简历时请注意个人信息保护与合规要求。

---

## License

本项目仅用于学习与技术演示。
