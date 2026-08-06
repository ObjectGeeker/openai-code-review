package com.object.ai.middleware.sdk;

import com.object.ai.middleware.sdk.infrastructure.git.GitCommand;
import com.object.ai.middleware.sdk.infrastructure.openai.util.AiChatUtil;

public class OpenAiCodeReview {

    public static void main(String[] args) {
        GitCommand gitCommand = new GitCommand(args[2], args[3], "main", args[4], args[5], args[6]);
        String diffCode = gitCommand.diff();

        String analysisResult = codeReview(args[0], args[1], diffCode);
        System.out.println("评审意见：" + analysisResult);

        gitCommand.commitAndPush(analysisResult, "Add new file");
        System.out.println("评审日志提交完成!");
    }

    private static String codeReview(String workSpaceId, String apiKey, String diffCode) {
        return AiChatUtil.chat(workSpaceId, apiKey, "你是一名专业的代码评审师，你需要根据传入的代码给出评审意见", diffCode);
    }

}
