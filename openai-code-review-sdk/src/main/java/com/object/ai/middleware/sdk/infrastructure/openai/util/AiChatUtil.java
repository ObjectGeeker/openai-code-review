package com.object.ai.middleware.sdk.infrastructure.openai.util;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼（DashScope）OpenAI 兼容模式 Chat Completions 调用工具类
 * <p>
 * 对应请求：
 * POST https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions
 * Authorization: Bearer $DASHSCOPE_API_KEY
 */
public class AiChatUtil {

    /** 接口地址模板，{WorkspaceId} 会被替换为实际的工作空间 ID */
    private static final String ENDPOINT_TEMPLATE =
            "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions";

    /** API Key 对应的环境变量名 */
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    /** 默认模型 */
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    /** 请求超时时间（毫秒） */
    private static final int TIMEOUT_MS = 60_000;

    private AiChatUtil() {
    }

    /**
     * 使用默认模型发起对话，API Key 从环境变量 DASHSCOPE_API_KEY 读取
     *
     * @param workspaceId 百炼工作空间 ID
     * @param systemPrompt 系统提示词（可为 null）
     * @param userPrompt 用户输入
     * @return 模型回复的文本内容
     */
    public static String chat(String workspaceId, String systemPrompt, String userPrompt) {
        return chat(workspaceId, requireApiKeyFromEnv(), DEFAULT_MODEL, systemPrompt, userPrompt);
    }

    /**
     * 使用默认模型发起对话
     *
     * @param workspaceId 百炼工作空间 ID
     * @param apiKey API Key（Bearer Token）
     * @param systemPrompt 系统提示词（可为 null）
     * @param userPrompt 用户输入
     * @return 模型回复的文本内容
     */
    public static String chat(String workspaceId, String apiKey, String systemPrompt, String userPrompt) {
        return chat(workspaceId, apiKey, DEFAULT_MODEL, systemPrompt, userPrompt);
    }

    /**
     * 使用自定义 messages 发起对话，适用于多轮会话或自定义角色编排
     *
     * @param workspaceId 百炼工作空间 ID
     * @param apiKey API Key（Bearer Token）
     * @param model 模型名称，如 qwen3.8-max
     * @param messages 消息列表，每项为 {role: xxx, content: xxx}
     * @return 模型回复的文本内容
     */
    public static String chat(String workspaceId, String apiKey, String model, List<Map<String, String>> messages) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);

        JsonArray messageArray = new JsonArray();
        for (Map<String, String> message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.get("role"));
            item.addProperty("content", message.get("content"));
            messageArray.add(item);
        }
        requestBody.add("messages", messageArray);

        return doRequest(buildEndpoint(workspaceId), apiKey, requestBody);
    }

    /**
     * 单轮对话：system + user 两条消息
     */
    public static String chat(String workspaceId, String apiKey, String model, String systemPrompt, String userPrompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);

        JsonArray messageArray = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messageArray.add(buildMessage("system", systemPrompt));
        }
        messageArray.add(buildMessage("user", userPrompt));
        requestBody.add("messages", messageArray);

        return doRequest(buildEndpoint(workspaceId), apiKey, requestBody);
    }

    private static JsonObject buildMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String buildEndpoint(String workspaceId) {
        if (workspaceId == null || workspaceId.isEmpty()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        return ENDPOINT_TEMPLATE.replace("{WorkspaceId}", workspaceId);
    }

    private static String requireApiKeyFromEnv() {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("环境变量 " + API_KEY_ENV + " 未配置");
        }
        return apiKey;
    }

    /**
     * 发送请求并解析响应，提取 choices[0].message.content
     */
    private static String doRequest(String endpoint, String apiKey, JsonObject requestBody) {
        HttpResponse response = HttpRequest.post(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .timeout(TIMEOUT_MS)
                .execute();

        String body = response.body();
        if (!response.isOk()) {
            throw new RuntimeException("调用百炼接口失败，HTTP " + response.getStatus() + "，响应：" + body);
        }

        JsonObject responseJson = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = responseJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("百炼接口未返回 choices，响应：" + body);
        }

        JsonElement message = choices.get(0).getAsJsonObject().get("message");
        if (message == null || !message.getAsJsonObject().has("content")) {
            throw new RuntimeException("百炼接口响应缺少 message.content，响应：" + body);
        }
        return message.getAsJsonObject().get("content").getAsString();
    }

}
