package com.object.ai.middleware.sdk.infrastructure.git;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Git 命令操作
 * <p>
 * 封装代码评审流程中的两类 Git 操作：
 * 1. 获取当前仓库最新一次提交的变更内容（diff），供 AI 评审分析；
 * 2. 将评审结果以 Markdown 日志的形式提交并推送到指定的日志仓库。
 * @author object
 */
public class GitCommand {

    private final Logger logger = LoggerFactory.getLogger(GitCommand.class);

    /** 日志仓库的克隆地址（HTTPS），如 https://github.com/xxx/code-review-log.git */
    private final String githubRepositoryUri;

    /** 日志仓库的访问令牌（GitHub PAT），作为推送凭证 */
    private final String githubToken;

    /** 日志仓库的目标分支，如 main */
    private final String branch;

    /** 提交人名称，用于 commit 的 author/committer 身份 */
    private final String author;

    /** 提交人邮箱，用于 commit 的 author/committer 身份 */
    private final String email;

    /** 项目名，用于拼接日志文件名，区分不同接入方 */
    private final String project;

    /**
     * @param githubRepositoryUri 日志仓库地址
     * @param githubToken 日志仓库访问令牌
     * @param branch 目标分支
     * @param author 提交人名称
     * @param email 提交人邮箱
     * @param project 项目名（用于日志文件命名）
     */
    public GitCommand(String githubRepositoryUri, String githubToken, String branch, String author, String email, String project) {
        this.githubRepositoryUri = githubRepositoryUri;
        this.githubToken = githubToken;
        this.branch = branch;
        this.author = author;
        this.email = email;
        this.project = project;
    }

    /**
     * 获取当前工作区最新一次提交的 diff 内容
     * <p>
     * 分两步执行：
     * 1. {@code git log -1 --pretty=format:%H} 取最新提交的完整 hash；
     * 2. {@code git diff <hash>^ <hash>} 对比该提交与其父提交，得到本次变更的全部差异。
     * 之所以先取 hash 再 diff（而不是直接用 HEAD~1..HEAD），是为了在
     * 浅克隆（fetch-depth 有限）场景下明确锁定已检出的提交，语义更稳定。
     *
     * @return diff 文本；获取失败时返回 null
     */
    public String diff() {
        ProcessBuilder processBuilder = new ProcessBuilder("git", "log", "-1", "--pretty=format:%H");
        processBuilder.directory(new File("."));
        processBuilder.redirectErrorStream(true);
        try {
            // 第一步：获取最新提交的完整 hash（%H 表示输出完整提交哈希）
            Process logProcess = processBuilder.start();
            BufferedReader logReader = new BufferedReader(new InputStreamReader(logProcess.getInputStream()));
            String latestCommitHash = logReader.readLine();
            logReader.close();
            logProcess.waitFor();

            // 第二步：对比 <hash>^（父提交）与 <hash>（当前提交），得到本次变更的 diff
            ProcessBuilder diffProcessBuilder = new ProcessBuilder("git", "diff", latestCommitHash + "^", latestCommitHash);
            diffProcessBuilder.redirectErrorStream(true);
            Process diffProcess = diffProcessBuilder.start();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(diffProcess.getInputStream()));
            String line;

            StringBuilder diffCode = new StringBuilder();
            while ((line = bufferedReader.readLine()) != null) {
                diffCode.append(line).append('\n');
            }

            int exitCode = diffProcess.waitFor();
            logger.info("Exited with code:" + exitCode);
            logger.info("评审代码: " + diffCode);
            return diffCode.toString();
        } catch (Exception e) {
            logger.error("代码提交记录获取失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 将评审结果写入日志仓库并推送
     * <p>
     * 流程：克隆日志仓库 -> 按日期建目录 -> 写入 Markdown 日志文件 ->
     * add/commit/push 到目标分支。日志文件按 {project}-{branch}-{author}{uuid}.md 命名，
     * 存放在 yyyy-MM-dd 日期目录下，保证多次评审不互相覆盖。
     *
     * @param analysisResult AI 评审结果，作为日志文件内容
     * @param commitMessage 提交信息
     */
    public void commitAndPush(String analysisResult, String commitMessage) {
        try {
            // 克隆日志仓库到本地 repo 目录，token 作为推送凭证
            Git git = Git.cloneRepository()
                    .setURI(githubRepositoryUri)
                    .setDirectory(new File("repo"))
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                    .call();

            // 按日期建目录（如 2026-08-06），不存在则创建
            String dataFolderName = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            File dataFolder = new File("repo/" + dataFolderName);
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            // 文件名拼接项目、分支、作者并用 UUID 保证唯一，避免同一天多次评审互相覆盖
            String fileName = project + "-" + branch + "-" + author + IdUtil.fastSimpleUUID() + ".md";
            File logFile = new File(dataFolder, fileName);
            try(FileWriter fileWriter = new FileWriter(logFile)) {
                fileWriter.write(analysisResult);
            } catch (Exception e) {
                System.out.println("评审日志写入报错: " + e.getMessage());
            }

            // 暂存新日志文件；路径必须是相对仓库根目录的相对路径
            git.add().addFilepattern(dataFolderName + "/" + fileName).call();
            // CI 容器可能没有全局 git 身份配置，显式指定提交人避免 commit 报错
            git.commit().setAuthor(author, email)
                    .setCommitter(author, email)
                    .setMessage(commitMessage).call();
            // JGit 是建造者模式，必须调用 call() 才会真正执行推送，否则静默不生效
            Iterable<PushResult> pushResults = git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                    .call();
            for (PushResult pushResult : pushResults) {
                System.out.println("推送结果: " + pushResult.getMessages());
            }
            // 从仓库地址截掉尾部 .git，拼出 GitHub 页面上的日志文件预览链接
            int index = StrUtil.lastIndexOf(githubRepositoryUri, ".git", 0, false);
            String githubPreviewUrl = StrUtil.sub(githubRepositoryUri, 0, index);
            logger.info("代码评审日志预览: " + githubPreviewUrl + "/blob/" + branch + "/" + dataFolderName + "/" + fileName);
        } catch (Exception e) {
            logger.error("代码评审记录提交失败: " + e.getMessage());
        }
    }
}
