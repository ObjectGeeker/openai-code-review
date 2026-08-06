package com.object.ai.middleware.sdk;

import com.object.ai.middleware.sdk.infrastructure.git.GitCommand;
import com.object.ai.middleware.sdk.infrastructure.openai.util.AiChatUtil;

public class OpenAiCodeReview {

    public static void main(String[] args) {
        // 全部配置通过环境变量注入（GitHub Actions 的 env 块），不再使用命令行参数
        String workspaceId = requireEnv("MAAS_WORKSPACE_ID");
        String apiKey = requireEnv("DASHSCOPE_API_KEY");
        String reviewLogUri = requireEnv("GITHUB_REVIEW_LOG_URI");
        String githubToken = requireEnv("GITHUB_TOKEN");
        String author = requireEnv("CODE_REVIEW_AUTHOR");
        String email = requireEnv("CODE_REVIEW_EMAIL");
        String project = requireEnv("CODE_REVIEW_PROJECT");

        GitCommand gitCommand = new GitCommand(reviewLogUri, githubToken, "main", author, email, project);
        String diffCode = gitCommand.diff();

        String analysisResult = codeReview(workspaceId, apiKey, diffCode);
        System.out.println("评审意见：" + analysisResult);

        gitCommand.commitAndPush(analysisResult, "Add new file");
        System.out.println("评审日志提交完成!");
    }

    /**
     * 读取必需的环境变量，未配置时快速失败并给出明确提示
     */
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("环境变量 " + name + " 未配置");
        }
        return value;
    }

    private static String codeReview(String workSpaceId, String apiKey, String diffCode) {
        return AiChatUtil.chat(workSpaceId, apiKey, "你是一名专业的代码评审师，你需要根据传入的代码给出评审意见", diffCode);
    }

}
